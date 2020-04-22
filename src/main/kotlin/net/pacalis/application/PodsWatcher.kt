package net.pacalis.application

import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.fabric8.kubernetes.client.Watch
import io.fabric8.kubernetes.client.Watcher
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.Startup
import org.slf4j.LoggerFactory
import javax.enterprise.context.ApplicationScoped
import javax.enterprise.event.Observes

@Startup
@ApplicationScoped
class PodsWatcher constructor(client: KubernetesClient, podHandler: PodHandler) {
    private val watch: Watch

    class PodResourceWatcher constructor(private val podHandler: PodHandler): Watcher<Pod?> {
        override fun eventReceived(action: Watcher.Action, pod: Pod?) {
            pod?.let {
                when(action) {
                    Watcher.Action.ADDED, Watcher.Action.MODIFIED -> podHandler.handle(pod)
                    else -> {}
                }
            }
        }

        override fun onClose(e: KubernetesClientException) {
            // Nothing to do
        }
    }

    fun onStop(@Observes ev: ShutdownEvent?) {
        LOGGER.info("Stop Pods watch ...")
        watch.close()
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger(PodsWatcher::class.java)
    }

    init {
        LOGGER.info("Synchronize pods...")
        client.pods().list().items.forEach {
            podHandler.handle(it)
        }
        watch = client.pods().watch(PodResourceWatcher(podHandler))
        LOGGER.info("Pods watch started.")
    }
}
