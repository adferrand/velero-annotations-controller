import logging
import os
import signal
import sys

from kubernetes import config, client, watch

VELERO_ANNOTATION = 'backup.velero.io/backup-volumes'


def _configure_logging():
    logging.basicConfig(
        format='%(asctime)s - controller - %(levelname)s - %(message)s',
        level=logging.DEBUG,
        datefmt='%m/%d/%Y %I:%M:%S')


def _configure_exit(watcher):
    def handler(_signum, _frame):
        logging.info('Closing controller.')
        watcher.stop()
        sys.exit(0)

    signal.signal(signal.SIGINT, handler)
    signal.signal(signal.SIGTERM, handler)


def main():
    logging.info('Starting controller.')

    if os.path.exists('/var/run/secrets/kubernetes.io/serviceaccount/token'):
        config.load_incluster_config()
    else:
        config.load_kube_config()
    v1 = client.CoreV1Api()

    logging.info('Watching pods.')

    watcher = watch.Watch()
    _configure_exit(watcher)
    
    for event in watcher.stream(v1.list_pod_for_all_namespaces):
        pod = event['object']
        name = pod.metadata.name
        namespace = pod.metadata.namespace
        if event['type'] not in ['ADDED', 'MODIFIED']:
            continue
        if pod.metadata.namespace.startswith('kube-'):
            continue
        if pod.status.phase != 'Running':
            continue

        persistent_volumes = [volume.name for volume in pod.spec.volumes if volume.persistent_volume_claim]
        if not persistent_volumes:
            continue

        annotations = pod.metadata.annotations if pod.metadata.annotations else {}
        velero_annotation = annotations.get(VELERO_ANNOTATION)
        velero_annotation = velero_annotation.split(',') if velero_annotation else []

        missing_volumes = list(set(persistent_volumes) - set(velero_annotation))

        if missing_volumes:
            print('Reconciling velero annotations on pod {}/{}: persistent_volumes={}, velero_annotation={}, missing={}.'
                    .format(namespace, name, persistent_volumes, velero_annotation, missing_volumes))

            velero_annotation.extend(missing_volumes)
            annotations[VELERO_ANNOTATION] = ','.join(velero_annotation)
            setattr(pod.metadata, 'annotations', annotations)

            v1.patch_namespaced_pod(name, namespace, pod)


if __name__ == '__main__':
    _configure_logging()
    main()
