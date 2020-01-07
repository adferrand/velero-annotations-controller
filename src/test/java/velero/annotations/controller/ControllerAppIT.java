package velero.annotations.controller;

import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.AppsV1Api;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.extended.controller.Controller;
import io.kubernetes.client.models.*;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.KubeConfig;
import io.kubernetes.client.util.Yaml;
import org.junit.jupiter.api.*;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class ControllerAppIT {
    private static final String VELERO_ANNOTATION = "backup.velero.io/backup-volumes";


    private static Controller controller;
    private CoreV1Api coreApi;
    private AppsV1Api appsApi;

    @BeforeAll
    static void startController() throws IOException {
        String testKubeConfigPath = System.getProperty("test.kubeconfig.path");
        String context = System.getProperty("test.kubeconfig.context");

        KubeConfig kubeConfig = KubeConfig.loadKubeConfig(new FileReader(testKubeConfigPath));
        kubeConfig.setContext(context);

        ApiClient apiClient = Config.fromConfig(kubeConfig);
        controller = ControllerApp.generateController(apiClient);
        new Thread(controller).start();
    }

    @BeforeEach
    void setup() throws ApiException {
        this.coreApi = new CoreV1Api();
        this.appsApi = new AppsV1Api();

        try {
            coreApi.deleteNamespace("integration-tests", null, null, null, null, null, null);
        } catch (ApiException e) {
            // Pass this error, the namespace may not exist.
        }

        V1Namespace namespace = new V1NamespaceBuilder()
                .withNewMetadata()
                    .withName("integration-tests")
                .endMetadata()
                .build();
        coreApi.createNamespace(namespace, null, null, null);
    }

    @AfterEach
    void teardown() throws ApiException {
        coreApi.deleteNamespace("integration-tests", null, null, null, null, null, null);
    }

    @AfterAll
    static void stopController() {
        controller.shutdown();
    }

    @Test void testIt() throws IOException, ApiException, InterruptedException {
        V1Service service = Yaml.loadAs(resourceFile("service.yml"), V1Service.class);
        V1PersistentVolumeClaim pvc = Yaml.loadAs(resourceFile("pvc.yml"), V1PersistentVolumeClaim.class);
        V1Deployment deployment = Yaml.loadAs(resourceFile("deployment.yml"), V1Deployment.class);

        coreApi.createNamespacedService("integration-tests", service, null, null, null);
        coreApi.createNamespacedPersistentVolumeClaim("integration-tests", pvc, null, null, null);
        appsApi.createNamespacedDeployment("integration-tests", deployment, null, null, null);

        retryAssert(() -> {
            V1PodList list = this.coreApi.listNamespacedPod("integration-tests", null, null, null, null, null, null, null, true);
            assertFalse(list.getItems().isEmpty());

            V1Pod pod = list.getItems().get(0);
            assertNotNull(pod.getMetadata().getAnnotations());
            assertTrue(pod.getMetadata().getAnnotations().containsKey(VELERO_ANNOTATION));
            assertEquals("data", pod.getMetadata().getAnnotations().get(VELERO_ANNOTATION));
        }, 10, 3);
    }

    private static File resourceFile(String fileName) {
        return new File(Objects.requireNonNull
                (ClassLoader.getSystemClassLoader().getResource("service.yml")).getFile());
    }

    private interface Action {
        void run() throws ApiException;
    }

    private void retryAssert(Action assertAction, int maxRetry, int wait) throws InterruptedException, ApiException {
        int attempts = 0;
        AssertionError lastAssertionError = null;

        while (true) {
            try {
                assertAction.run();
            } catch (AssertionError e) {
                lastAssertionError = e;
            }

            attempts += 1;
            if (attempts > maxRetry) {
                break;
            }

            Thread.sleep(wait);
        }

        if (lastAssertionError != null) {
            throw lastAssertionError;
        }
    }
}
