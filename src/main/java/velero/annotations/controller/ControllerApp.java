package velero.annotations.controller;

import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.Configuration;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.extended.controller.Controller;
import io.kubernetes.client.extended.controller.ControllerManager;
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
import io.kubernetes.client.models.V1Pod;
import io.kubernetes.client.models.V1PodList;
import io.kubernetes.client.models.V1Volume;
import io.kubernetes.client.util.CallGeneratorParams;
import io.kubernetes.client.util.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class ControllerApp {
    private static final String VELERO_ANNOTATION = "backup.velero.io/backup-volumes";
    private static final String VELERO_ANNOTATION_SANITIZED = "backup.velero.io~1backup-volumes";
    private static final String NS_FILTER_ENV_VAR_NAME = "VELERO_ANNOTATIONS_CONTROLLER_NS_FILTER";
    private static final String PVCS_ONLY_ENV_VAR_NAME = "VELERO_ANNOTATIONS_CONTROLLER_PVCS_ONLY";

    private static final Logger LOGGER = LoggerFactory.getLogger(ControllerApp.class);

    public static void main(String[] args) throws IOException {
        LOGGER.info("Preparing the controller ...");
        Controller controller = generateController(Config.defaultClient());

        LOGGER.info("Starting the controller ...");
        controller.run();
    }

    static Controller generateController(ApiClient apiClient) {
        Configuration.setDefaultApiClient(apiClient);
        CoreV1Api coreV1Api = new CoreV1Api();

        SharedInformerFactory informerFactory = new SharedInformerFactory();
        SharedIndexInformer<V1Pod> podInformer = informerFactory.sharedIndexInformerFor(
                (CallGeneratorParams params) ->
                        coreV1Api.listPodForAllNamespacesCall(
                                null, null, null, null, null,
                                params.resourceVersion, params.timeoutSeconds, params.watch, null, null),
                V1Pod.class,
                V1PodList.class);

        informerFactory.startAllRegisteredInformers();

        String namespacesFilterStr = System.getenv(NS_FILTER_ENV_VAR_NAME);
        List<String> namespacesFilter;
        if (namespacesFilterStr != null && !namespacesFilterStr.isEmpty()) {
            namespacesFilter = Arrays.asList(namespacesFilterStr.split(","));
            LOGGER.info("Environment variable {} is set, the controller will watch only following namespaces: {}", NS_FILTER_ENV_VAR_NAME, namespacesFilter);
        } else {
            namespacesFilter = null;
        }

        boolean reconcilePVCsAnnotationsOnly = !"false".equals(System.getenv(PVCS_ONLY_ENV_VAR_NAME));
        if (reconcilePVCsAnnotationsOnly) {
            LOGGER.info("Environment variable {} != \"false\": the controller will watch only volumes with a PVC.", PVCS_ONLY_ENV_VAR_NAME);
        } else {
            LOGGER.info("Environment variable {} == \"false\": the controller will watch all volumes.", PVCS_ONLY_ENV_VAR_NAME);
        }
        PodVeleroAnnotationsReconciler podReconciler = new PodVeleroAnnotationsReconciler(podInformer, coreV1Api, reconcilePVCsAnnotationsOnly, namespacesFilter);

        Controller controller = ControllerBuilder.defaultBuilder(informerFactory)
                .watch(workQueue -> ControllerBuilder.controllerWatchBuilder(V1Pod.class, workQueue)
                        .withOnDeleteFilter((V1Pod deletedNode, Boolean stateUnknown) -> false)
                        .build())
                .withReconciler(podReconciler)
                .withName("pod-velero-annotations-controller")
                .withReadyFunc(podInformer::hasSynced)
                .withWorkerCount(64)
                .build();

        ControllerManager controllerManager = ControllerBuilder.controllerManagerBuilder(informerFactory)
                .addController(controller)
                .build();

        return new LeaderElectingController(
                new LeaderElector(
                        new LeaderElectionConfig(
                                new EndpointsLock("kube-system", "leader-election", "velero-annotations-controller"),
                                Duration.ofMillis(10000),
                                Duration.ofMillis(8000),
                                Duration.ofMillis(5000))),
                controllerManager
        );
    }

    static class PodVeleroAnnotationsReconciler implements Reconciler {
        private final Lister<V1Pod> podLister;
        private final CoreV1Api coreV1Api;
        private final List<String> namespacesFilter;
        private final boolean reconcilePVCsAnnotationsOnly;

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

        @Override
        public Result reconcile(Request request) {
            if (namespacesFilter != null && !namespacesFilter.contains(request.getNamespace())) {
                LOGGER.debug(
                        "Skip reconciliation in namespace {} since namespacesFilter is set, and {} is not part of it.",
                        request.getNamespace(), request.getNamespace());
                return new Result(false);
            }

            LOGGER.debug("Reconciliation loop for {}/{}", request.getNamespace(), request.getName());

            V1Pod pod = this.podLister.namespace(request.getNamespace()).get(request.getName());
            if (!"Running".equals(pod.getStatus().getPhase())) {
                return new Result(true);
            }

            Map<String, String> podAnnotations = pod.getMetadata().getAnnotations();
            String veleroAnnotationsStr = podAnnotations != null ? podAnnotations.get(VELERO_ANNOTATION): null;
            List<String> veleroAnnotations;
            if (veleroAnnotationsStr == null) {
                veleroAnnotations = Collections.emptyList();
            } else {
                veleroAnnotations = Arrays.asList(veleroAnnotationsStr.split(","));
                Collections.sort(veleroAnnotations);
            }

            List<String> persistentVolumes = pod.getSpec().getVolumes().stream()
                    .filter(volume -> !reconcilePVCsAnnotationsOnly || volume.getPersistentVolumeClaim() != null)
                    .map(V1Volume::getName)
                    .sorted()
                    .collect(Collectors.toList());

            if (veleroAnnotations.isEmpty() && persistentVolumes.isEmpty()) {
                return new Result(false);
            }

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
