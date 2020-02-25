FROM python:3.8-slim

ENV PYTHONUNBUFFERED 1

RUN mkdir -p /srv/ctrl

COPY src/ /srv/ctrl

RUN python3 -m venv /srv/ctrl \
 && /srv/ctrl/bin/pip install -e /srv/ctrl

CMD ["/srv/ctrl/bin/python", "/srv/ctrl/run.py"]
