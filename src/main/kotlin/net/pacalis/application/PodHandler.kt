package net.pacalis.application

import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.KubernetesClient
import org.slf4j.LoggerFactory
import javax.enterprise.context.ApplicationScoped

@ApplicationScoped
class PodHandler constructor(private val client: KubernetesClient) {

    fun handle(pod : Pod) {
        if (pod.metadata.namespace.startsWith("kube-")) {
            return
        }
        if (pod.status.phase != "Running") {
            return
        }

        val persistentVolumes = pod.spec.volumes
                .filter { it.persistentVolumeClaim != null }
                .map { it.name }

        if (persistentVolumes.isEmpty()) {
            return
        }

        val veleroAnnotations = pod.metadata.annotations[VELERO_ANNOTATION]?.split(",") ?: listOf()

        val missingVolumes = (persistentVolumes.toSet() - veleroAnnotations.toSet()).toList()

        if (missingVolumes.isNotEmpty()) {
            LOGGER.warn("Reconciling velero annotations on pod ${pod.metadata.namespace}/${pod.metadata.name}: " +
                    "persistent_volumes=${persistentVolumes}, " +
                    "velero_annotation=${veleroAnnotations}, " +
                    "missing=${missingVolumes}.)")

            val newVeleroAnnotations = ArrayList<String>()
            newVeleroAnnotations.addAll(veleroAnnotations)
            newVeleroAnnotations.addAll(missingVolumes)

            client.pods()
                    .inNamespace(pod.metadata.namespace)
                    .withName(pod.metadata.name)
                    .edit()
                    .editMetadata()
                    .addToAnnotations(VELERO_ANNOTATION, newVeleroAnnotations.joinToString(","))
                    .endMetadata()
                    .done();
        }
    }

    companion object {
        private val VELERO_ANNOTATION = "backup.velero.io/backup-volumes"
        private val LOGGER = LoggerFactory.getLogger(PodHandler::class.java)
    }
}
