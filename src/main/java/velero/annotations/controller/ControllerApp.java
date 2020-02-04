/**
 * Velero Annotations Controller class.
 * This is the main entrypoint for the Java application.
 */
package velero.annotations.controller;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.informers.SharedInformerFactory;
import io.fabric8.kubernetes.client.informers.cache.Lister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;

/**
 * The Java application embedding the logic of the Velero Annotations Controller.
 * It is expected to be run a the Java application entrypoint.
 */
public class ControllerApp {
    private static final String VELERO_ANNOTATION = "backup.velero.io/backup-volumes";
    private static final String NS_FILTER_ENV_VAR_NAME = "VELERO_ANNOTATIONS_CONTROLLER_NS_FILTER";
    private static final String PVCS_ONLY_ENV_VAR_NAME = "VELERO_ANNOTATIONS_CONTROLLER_PVCS_ONLY";

    private static final Logger LOGGER = LoggerFactory.getLogger(ControllerApp.class);

    public static void main(String[] args) {
        try (KubernetesClient client = new DefaultKubernetesClient()) {
            LOGGER.info("Preparing the controller ...");

            // Allow to filter namespaces to watch using an environment variable.
            String namespacesFilterStr = System.getenv(NS_FILTER_ENV_VAR_NAME);
            List<String> namespacesFilter;
            if (namespacesFilterStr != null && !namespacesFilterStr.isEmpty()) {
                namespacesFilter = Arrays.asList(namespacesFilterStr.split(","));
                LOGGER.info("Environment variable {} is set, the controller will watch only following namespaces: {}", NS_FILTER_ENV_VAR_NAME, namespacesFilter);
            } else {
                LOGGER.info("Controller is configured to watch all namespaces.");
                namespacesFilter = null;
            }

            // Allow to synchronize annotations for all volumes instead of only volumes with PVC using an environment variable.
            boolean reconcilePVCsAnnotationsOnly = !"false".equals(System.getenv(PVCS_ONLY_ENV_VAR_NAME));
            if (reconcilePVCsAnnotationsOnly) {
                LOGGER.info("Environment variable {} != \"false\": the controller will watch only volumes with a PVC.", PVCS_ONLY_ENV_VAR_NAME);
            } else {
                LOGGER.info("Environment variable {} == \"false\": the controller will watch all volumes.", PVCS_ONLY_ENV_VAR_NAME);
            }

            SharedInformerFactory informerFactory = client.informers();
            SharedIndexInformer<Pod> podSharedIndexInformer = informerFactory.sharedIndexInformerFor(Pod.class, PodList.class, 10 * 60 * 1000);

            PodController podController = new PodController(client, podSharedIndexInformer, namespacesFilter, reconcilePVCsAnnotationsOnly);

            podController.create();
            informerFactory.startAllRegisteredInformers();

            LOGGER.info("Starting the controller ...");
            podController.run();
        } finally {
            LOGGER.info("Ending the controller ...");
            System.exit(0);
        }
    }

    static class Request implements Serializable {
        public final String namespace;
        public final String name;

        public Request (String namespace, String name) {
            this.namespace = namespace;
            this.name = name;
        }
    }

    static class PodController {
        private final KubernetesClient client;
        private final SharedIndexInformer<Pod> sharedIndexInformer;
        private final BlockingQueue<Request> workQueue;
        private final Lister<Pod> podLister;
        private final List<String> namespacesFilter;
        private final boolean reconcilePVCsAnnotationsOnly;

        public PodController(KubernetesClient client, SharedIndexInformer<Pod> sharedIndexInformer,
                             List<String> namespacesFilter, boolean reconcilePVCsAnnotationsOnly) {
            this.client = client;
            this.sharedIndexInformer = sharedIndexInformer;
            this.podLister = new Lister<>(sharedIndexInformer.getIndexer());
            this.workQueue = new ArrayBlockingQueue<>(1024);
            this.namespacesFilter = namespacesFilter;
            this.reconcilePVCsAnnotationsOnly = reconcilePVCsAnnotationsOnly;
        }

        public void create() {
            this.sharedIndexInformer.addEventHandler(new ResourceEventHandler<Pod>() {
                @Override
                public void onAdd(Pod obj) {
                    enqueuePod(obj);
                }

                @Override
                public void onUpdate(Pod oldObj, Pod newObj) {
                    enqueuePod(newObj);
                }

                @Override
                public void onDelete(Pod obj, boolean deletedFinalStateUnknown) {
                    // Do nothing.
                }
            });
        }

        public void run() {
            while(sharedIndexInformer.hasSynced());

            while(true) {
                try {
                    Request request = workQueue.take();
                    boolean retry = reconcile(request);
                    if (retry) {
                        workQueue.add(request);
                    }
                } catch (InterruptedException interruptedException) {
                    LOGGER.error("Controller is interrupted.");
                    return;
                }
            }
        }

        private void enqueuePod(Pod pod) {
            Request request = new Request(pod.getMetadata().getNamespace(), pod.getMetadata().getName());
            workQueue.add(request);
        }

        private boolean reconcile(Request request) {
            if (namespacesFilter != null && !namespacesFilter.contains(request.namespace)) {
                // If namespaces are filtered, finish the reconciliation right now for namespaces
                // which do not match the filter.
                LOGGER.debug(
                        "Skip reconciliation in namespace {} since namespacesFilter is set, and {} is not part of it.",
                        request.namespace, request.namespace);
                return false;
            }

            LOGGER.debug("Reconciliation loop for {}/{}", request.namespace, request.name);
            Pod pod = this.podLister.namespace(request.namespace).get(request.name);

            if (pod.getStatus() == null || !"Running".equals(pod.getStatus().getPhase())) {
                // Unready Pods cannot be processed properly, because some volumes may not been attached yet.
                // So we exit the reconciliation loop now, and ask for the Pod to be requeued for a future loop.
                return true;
            }

            // Retrieve current velero annotations.
            Map<String, String> podAnnotations = pod.getMetadata().getAnnotations();
            String veleroAnnotationsStr = podAnnotations != null ? podAnnotations.get(VELERO_ANNOTATION): null;
            List<String> veleroAnnotations;
            if (veleroAnnotationsStr == null) {
                veleroAnnotations = Collections.emptyList();
            } else {
                veleroAnnotations = Arrays.asList(veleroAnnotationsStr.split(","));
                Collections.sort(veleroAnnotations);
            }

            // Retrieve current volumes that should be annotated.
            List<String> persistentVolumes = pod.getSpec().getVolumes().stream()
                    .filter(volume -> !reconcilePVCsAnnotationsOnly || volume.getPersistentVolumeClaim() != null)
                    .map(Volume::getName)
                    .sorted()
                    .collect(Collectors.toList());

            if (veleroAnnotations.isEmpty() && persistentVolumes.isEmpty()) {
                // Nothing to do if there is no annotation and no volume.
                return false;
            }

            // Execute sync logic if there is some missing annotations only.
            List<String> missingAnnotations = persistentVolumes.stream()
                    .filter(annotation -> !veleroAnnotations.contains(annotation))
                    .collect(Collectors.toList());

            if (!missingAnnotations.isEmpty()) {
                LOGGER.info(
                        "Reconciling pod {}/{}: persistentVolumes='{}', veleroAnnotation='{}', missingAnnotations={}",
                        request.namespace, request.name, persistentVolumes, veleroAnnotations, missingAnnotations);
                List<String> newAnnotations = new ArrayList<>(veleroAnnotations);
                newAnnotations.addAll(missingAnnotations);
                String value = String.join(",", newAnnotations);

                client.pods().inNamespace(request.namespace).withName(request.name).edit()
                        .editMetadata()
                            .addToAnnotations(VELERO_ANNOTATION, value)
                        .endMetadata()
                        .done();
            }

            throw new NullPointerException();
        }
    }
}
