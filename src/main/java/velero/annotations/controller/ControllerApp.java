/**
 * Velero Annotations Controller class.
 * This is the main entrypoint for the Java application.
 */
package velero.annotations.controller;

import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.extended.controller.Controller;
import io.kubernetes.client.extended.controller.LeaderElectingController;
import io.kubernetes.client.extended.controller.builder.ControllerBuilder;
import io.kubernetes.client.extended.controller.reconciler.Reconciler;
import io.kubernetes.client.extended.controller.reconciler.Request;
import io.kubernetes.client.extended.controller.reconciler.Result;
import io.kubernetes.client.extended.leaderelection.LeaderElectionConfig;
import io.kubernetes.client.extended.leaderelection.LeaderElector;
import io.kubernetes.client.extended.leaderelection.resourcelock.EndpointsLock;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.informer.cache.Lister;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.openapi.models.V1Volume;
import io.kubernetes.client.util.CallGeneratorParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The Java application embedding the logic of the Velero Annotations Controller.
 * It is expected to be run a the Java application entrypoint.
 */
public class ControllerApp {
    private static final String VELERO_ANNOTATION = "backup.velero.io/backup-volumes";
    private static final String VELERO_ANNOTATION_SANITIZED = "backup.velero.io~1backup-volumes";
    private static final String NS_FILTER_ENV_VAR_NAME = "VELERO_ANNOTATIONS_CONTROLLER_NS_FILTER";
    private static final String PVCS_ONLY_ENV_VAR_NAME = "VELERO_ANNOTATIONS_CONTROLLER_PVCS_ONLY";

    private static final Logger LOGGER = LoggerFactory.getLogger(ControllerApp.class);

    public static void main(String[] args) {
        LOGGER.info("Preparing the controller ...");
        Controller controller = generateController(new CoreV1Api());

        LOGGER.info("Starting the controller ...");
        controller.run();
    }

    /**
     * Create a controller ready to be started.
     *
     * @param coreV1Api a well-configured {@link CoreV1Api} instance, to be able to watch pods on the cluster
     * @return a {@link Controller} instance ready to be started
     */
    static Controller generateController(CoreV1Api coreV1Api) {
        // Generate an informer for pods, and starts its watch.
        SharedInformerFactory informerFactory = new SharedInformerFactory();
        SharedIndexInformer<V1Pod> podInformer = informerFactory.sharedIndexInformerFor(
                (CallGeneratorParams params) ->
                        coreV1Api.listPodForAllNamespacesCall(
                                null, null, null, null, null,
                                null, params.resourceVersion, params.timeoutSeconds, params.watch, null),
                V1Pod.class,
                V1PodList.class);

        informerFactory.startAllRegisteredInformers();

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
        PodVeleroAnnotationsReconciler podReconciler = new PodVeleroAnnotationsReconciler(podInformer, coreV1Api, reconcilePVCsAnnotationsOnly, namespacesFilter);

        // Create the controller itself. It needs a working queue builder and a reconciler instance.
        Controller controller = ControllerBuilder.defaultBuilder(informerFactory)
                .watch(workQueue -> ControllerBuilder.controllerWatchBuilder(V1Pod.class, workQueue)
                        .withOnDeleteFilter((V1Pod deletedNode, Boolean stateUnknown) -> false)
                        .build())
                .withReconciler(podReconciler)
                .withName("pod-velero-annotations-controller")
                .withReadyFunc(podInformer::hasSynced)
                .withWorkerCount(64)
                .build();

        // We do not return the controller directly. Instead, we return a LeaderElectingController instance, for HA purpose.
        // Indeed, LeaderElectingController is able to coordinate several controller instances through several Pods, and query
        // a Leader election to select the actual controller instance responsible to o actions. This way, loosing a Pod allows
        // an automated transfer of Leader role (and operations) to another Pod.
        UUID identity = UUID.randomUUID();
        LOGGER.info("Controller instance identity for Leader election is: {}", identity);
        return new LeaderElectingController(
                new LeaderElector(
                        new LeaderElectionConfig(
                                new EndpointsLock("kube-system", "velero-annotations-controller", identity.toString()),
                                Duration.ofMillis(10000),
                                Duration.ofMillis(8000),
                                Duration.ofMillis(5000))),
                ControllerBuilder.controllerManagerBuilder(informerFactory)
                    .addController(controller)
                    .build()
        );
    }

    /**
     * This class creates a reconciliation loop usable by a controller.
     * It contains all the logic for the Velero Annotations Controller. For a given Pod:
     *  1) Check if the Pod is ready
     *  2) Get its annotations
     *  3) Get its volumes declared as persistent volume claims (or all volumes if reconcilePVCsAnnotationsOnly = true
     *  4) Add any missing velero annotations in the Pod metadata
     *  5) Invoke the Kubernetes client to update the Pod in the cluster
     */
    static class PodVeleroAnnotationsReconciler implements Reconciler {
        private final Lister<V1Pod> podLister;
        private final CoreV1Api coreV1Api;
        private final List<String> namespacesFilter;
        private final boolean reconcilePVCsAnnotationsOnly;

        /**
         * Create an instance of the {@link Reconciler}
         * @param podInformer the {@link SharedIndexInformer} already configured to watch Pods
         * @param coreV1Api an instance of {@link CoreV1Api} able to patch Pods in the cluster
         * @param reconcilePVCsAnnotationsOnly set to False to watch all volumes instead of volumes with a PVC (default)
         * @param namespacesFilter a list of namespaces that needs to be watch, if null all namespaces are watched
         */
        public PodVeleroAnnotationsReconciler(
                SharedIndexInformer<V1Pod> podInformer,
                CoreV1Api coreV1Api,
                boolean reconcilePVCsAnnotationsOnly,
                @Nullable List<String> namespacesFilter) {
            this.podLister = new Lister<>(podInformer.getIndexer());
            this.coreV1Api = coreV1Api;
            this.namespacesFilter = namespacesFilter;
            this.reconcilePVCsAnnotationsOnly = reconcilePVCsAnnotationsOnly;
        }

        /**
         * See {@link Reconciler#reconcile}.
         *
         * @param request current request to reconcile
         * @return a {@link Result}, with requeue=true if current request needs to be reconciled again (eg. an error occurred during current reconciliation)
         */
        @Override
        public Result reconcile(Request request) {
            if (namespacesFilter != null && !namespacesFilter.contains(request.getNamespace())) {
                // If namespaces are filtered, finish the reconciliation right now for namespaces
                // which do not match the filter.
                LOGGER.debug(
                        "Skip reconciliation in namespace {} since namespacesFilter is set, and {} is not part of it.",
                        request.getNamespace(), request.getNamespace());
                return new Result(false);
            }

            LOGGER.debug("Reconciliation loop for {}/{}", request.getNamespace(), request.getName());

            V1Pod pod = this.podLister.namespace(request.getNamespace()).get(request.getName());
            if (!"Running".equals(pod.getStatus().getPhase())) {
                // Unready Pods cannot be processed properly, because some volumes may not been attached yet.
                // So we exit the reconciliation loop now, and ask for the Pod to be requeued for a future loop.
                return new Result(true);
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
                    .map(V1Volume::getName)
                    .sorted()
                    .collect(Collectors.toList());

            if (veleroAnnotations.isEmpty() && persistentVolumes.isEmpty()) {
                // Nothing to do if there is no annotation and no volume.
                return new Result(false);
            }

            // Execute sync logic if there is some missing annotations only.
            List<String> missingAnnotations = persistentVolumes.stream()
                    .filter(annotation -> !veleroAnnotations.contains(annotation))
                    .collect(Collectors.toList());

            if (!missingAnnotations.isEmpty()) {
                LOGGER.info(
                        "Reconciling pod {}/{}: persistentVolumes='{}', veleroAnnotation='{}', missingAnnotations={}",
                        request.getNamespace(), request.getName(), persistentVolumes, veleroAnnotations, missingAnnotations);
                String action = veleroAnnotations.isEmpty() ? "add" : "replace";
                List<String> newAnnotations = new ArrayList<>(veleroAnnotations);
                newAnnotations.addAll(missingAnnotations);
                String value = String.join(",", newAnnotations);

                String patch;
                // The patch to apply is not the same depending of if there are already some annotations or not.
                // If yes, we patch the annotation field itself. If not, we create the annotation field with an initial value.
                if (podAnnotations == null) {
                    patch = "[{\"op\":\"add\",\"path\":\"/metadata/annotations\",\"value\":{\"" + VELERO_ANNOTATION + "\":\""+ value + "\"}}]";
                } else {
                    patch = "[{\"op\":\"" + action + "\",\"path\":\"/metadata/annotations/" + VELERO_ANNOTATION_SANITIZED + "\",\"value\":\"" + value + "\"}]";
                }

                try {
                    LOGGER.debug("Patching {}/{} with: {}", request.getNamespace(), request.getName(), patch);
                    coreV1Api.patchNamespacedPod(request.getName(), request.getNamespace(), new V1Patch(patch), null, null, null, null);
                } catch (ApiException e) {
                    LOGGER.error("Exception occurred while patching the pod {}/{} with patch {}.", request.getNamespace(), request.getName(), patch, e);
                    new Result(true);
                }
            }

            return new Result(false);
        }
    }
}
