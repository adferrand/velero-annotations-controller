FROM python:3.8-slim

RUN mkdir -p /srv/ctrl
COPY src/ /srv/ctrl
RUN python3 -m venv /srv/ctrl \
 && /srv/ctrl/bin/pip install -e /srv/ctrl

CMD ["/srv/ctrl/bin/kopf", "run", "/srv/ctrl/handlers.py"]
