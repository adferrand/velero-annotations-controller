package net.pacalis.application;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.Startup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

@Startup
@ApplicationScoped
public class PodsWatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(PodsWatcher.class);

    private final Watch watch;

    public static class PodResourceWatcher implements Watcher<Pod> {
        @Override
        public void eventReceived(Action action, Pod pod) {
            // do something
        }

        @Override
        public void onClose(KubernetesClientException e) {
            // do something
        }
    }

    @Inject
    public PodsWatcher(KubernetesClient client) {
        this.watch = client.pods().watch(new PodResourceWatcher());
    }

    void onStop(@Observes ShutdownEvent ev) {
        LOGGER.info("The application is stopping...");
        watch.close();
    }
}
