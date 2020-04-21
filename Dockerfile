FROM docker.io/python:3.8-slim AS constraints

COPY . /tmp/workspace

RUN python3 -m pip install --user poetry --no-warn-script-location \
 && cd /tmp/workspace \
 && python3 -m poetry export --format requirements.txt --without-hashes > /tmp/workspace/constraints.txt \
 && python3 -m poetry build -f wheel

FROM docker.io/python:3.8-alpine

ENV PYTHONUNBUFFERED 1

COPY --from=constraints /tmp/workspace/constraints.txt /tmp/workspace/dist/*.whl /tmp/workspace/

RUN python3 -m venv /opt/ctrl \
 && /opt/ctrl/bin/pip3 install -c /tmp/workspace/constraints.txt /tmp/workspace/*.whl \
 && rm -rf /tmp/workspace

CMD ["/opt/ctrl/bin/velero-annotations-controller"]
