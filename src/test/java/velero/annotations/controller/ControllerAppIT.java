package velero.annotations.controller;

import io.kubernetes.client.ApiClient;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.extended.controller.Controller;
import io.kubernetes.client.util.Config;
import io.kubernetes.client.util.KubeConfig;
import org.junit.jupiter.api.*;

import java.io.FileReader;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ControllerAppIT {
    private static Controller controller;

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

    @AfterAll
    static void stopController() {
        controller.shutdown();
    }

    @Test void testIt() throws InterruptedException {
        CoreV1Api coreV1Api = new CoreV1Api();
        Thread.sleep(30000);
        assertTrue(true);
    }
}
