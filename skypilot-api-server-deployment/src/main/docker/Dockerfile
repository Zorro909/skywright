FROM docker.io/library/python:3.12.11-slim-bookworm@sha256:519591d6871b7bc437060736b9f7456b8731f1499a57e22e6c285135ae657bf7

ARG BUILD_CREATED

LABEL org.opencontainers.image.base.name="docker.io/library/python:3.12.11-slim-bookworm" \
      org.opencontainers.image.base.digest="sha256:519591d6871b7bc437060736b9f7456b8731f1499a57e22e6c285135ae657bf7" \
      org.opencontainers.image.created="${BUILD_CREATED}" \
      org.opencontainers.image.description="Version-paired SkyPilot API server for the Skywright control plane" \
      org.opencontainers.image.revision="@git.commit.id.full@" \
      org.opencontainers.image.source="https://github.com/Zorro909/skywright" \
      org.opencontainers.image.title="Skywright SkyPilot API server" \
      org.opencontainers.image.version="@project.version@" \
      io.skywright.python.version="@skypilot.python.version@" \
      io.skywright.skypilot.version="@skypilot.version@"

COPY maven/graalpy.lock /tmp/graalpy.lock
COPY server-requirements.lock /tmp/server-requirements.lock
COPY startup/launch.py /opt/skywright/runtime/launch.py
COPY startup/runtime_pull.py /opt/skywright/runtime/runtime_pull.py
COPY startup/log_collector.py startup/archive_read.py /opt/skywright/runtime/

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get upgrade --yes \
    && DEBIAN_FRONTEND=noninteractive apt-get install --yes --no-install-recommends \
        ca-certificates curl git netcat-openbsd openssh-client rsync socat tini \
    && case "$(dpkg --print-architecture)" in \
        amd64) kubectl_arch=amd64; kubectl_sha=6129359f4e1f3848a5572ccb0b26cf28b8ca08cef38c95a765b2f64a2c961a2f ;; \
        arm64) kubectl_arch=arm64; kubectl_sha=922df28df248cc00a9e025f947704f1d1482de64ece54cfe57e61f19eaf1eef3 ;; \
        *) exit 1 ;; \
       esac \
    && curl --fail --location --silent --show-error \
        "https://dl.k8s.io/release/v1.37.0/bin/linux/${kubectl_arch}/kubectl" \
        --output /usr/local/bin/kubectl \
    && printf '%s  /usr/local/bin/kubectl\n' "$kubectl_sha" | sha256sum --check --status \
    && chmod 0755 /usr/local/bin/kubectl \
    && rm --recursive --force /var/lib/apt/lists/* \
    && python -m pip install \
        --disable-pip-version-check \
        --no-cache-dir \
        --no-deps \
        --only-binary=:all: \
        --requirement /tmp/graalpy.lock \
    && python -m pip install \
        --disable-pip-version-check --no-cache-dir --no-deps \
        --only-binary=:all: --require-hashes \
        --requirement /tmp/server-requirements.lock \
    && python -c "import sky, vastai_sdk; assert sky.__version__ == '@skypilot.version@'" \
    && rm --force /tmp/graalpy.lock /tmp/server-requirements.lock \
    && groupadd --gid 10002 skypilot \
    && useradd --uid 10002 --gid 10002 --home-dir /var/lib/skypilot \
        --no-create-home --shell /usr/sbin/nologin skypilot \
    && install --directory --owner 10002 --group 10002 --mode 0750 \
        /var/lib/skypilot \
        /var/lib/skypilot/.cache \
        /var/lib/skypilot/.sky \
        /var/lib/skypilot/.sky/api_server \
        /var/lib/skypilot/.sky/api_server/clients \
        /var/lib/skypilot/sky_logs

USER 10002:10002
WORKDIR /var/lib/skypilot

ENV HOME=/var/lib/skypilot \
    IS_SKYPILOT_SERVER=true \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    OPENBLAS_NUM_THREADS=1 \
    SKYPILOT_API_SERVER_STORAGE_ENABLED=true \
    SKYPILOT_DISABLE_USAGE_COLLECTION=true \
    SKYPILOT_GRACE_PERIOD_SECONDS=10 \
    XDG_CACHE_HOME=/var/lib/skypilot/.cache

EXPOSE 46580 46581 46582
STOPSIGNAL SIGTERM

ENTRYPOINT ["/usr/bin/tini", "--", "python", "-I", "-S", "/opt/skywright/runtime/launch.py"]
CMD ["--host=0.0.0.0", "--port=46580", "--deploy"]
