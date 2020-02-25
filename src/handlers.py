import asyncio
from typing import Dict
import os
import logging

import kopf
from kubernetes import config, client

TASKS: Dict[str, asyncio.Task] = {}
VELERO_ANNOTATION = 'backup.velero.io/backup-volumes'


@kopf.on.resume('', 'v1', 'pods')
@kopf.on.create('', 'v1', 'pods')
@kopf.on.update('', 'v1', 'pods')
async def handle_pod(namespace, name, body, logger, **_):
    if namespace.startswith('kube-'):
        return

    key = '{}/{}'.format(namespace, name)
    if key in TASKS:
        TASKS[key].cancel()

    TASKS[key] = asyncio.create_task(reconcile_pod_annotations(namespace, name, body, logger))


@kopf.on.delete('', 'v1', 'pods')
async def remove_pod_watch(namespace, name, **_):
    key = '{}/{}'.format(namespace, name)
    if key in TASKS:
        TASKS[key].cancel()


async def reconcile_pod_annotations(namespace: str, name: str, body: Dict, logger: logging.Logger):
    try:
        if os.path.exists('/var/run/secrets/kubernetes.io/serviceaccount/token'):
            config.load_incluster_config()
        else:
            config.load_kube_config()
        v1 = client.CoreV1Api()

        if body['status']['phase'] != 'Running':
            return

        persistent_volumes = [volume['name'] for volume in body['spec'].get('volumes', []) if volume.get('persistentVolumeClaim')]

        annotations = body['metadata'].get('annotations', {})
        velero_annotation = annotations.get(VELERO_ANNOTATION)
        velero_annotation = velero_annotation.split(',') if velero_annotation else []

        missing_volumes = list(set(persistent_volumes) - set(velero_annotation))

        if missing_volumes:
            logger.warning('Reconciling pod: persistent_volumes={}, velero_annotation={}, missing={}'
                           .format(persistent_volumes, velero_annotation, missing_volumes))

            velero_annotation.extend(missing_volumes)
            annotations[VELERO_ANNOTATION] = ','.join(velero_annotation)

            pod = v1.read_namespaced_pod(name, namespace)
            setattr(pod.metadata, 'annotations', annotations)

            v1.patch_namespaced_pod(name, namespace, pod)

    except BaseException as error:
        logger.error('Error occured while processing reconciliation for pod {}/{}: {}'.format(namespace, name, error))
