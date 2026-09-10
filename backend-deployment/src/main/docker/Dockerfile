FROM ghcr.io/graalvm/jdk-community:25i3-25.0.4.1-ol10@sha256:f09094098451bd6899fd941a5c23079b9e99a13b83ebaf73ac771d7c5ec874f0 AS graalvm

FROM docker.io/library/ubuntu@sha256:1e0a86e57d247923571b75e0aaf48a1449cf8c543d51fb3e07a4a7d7bfa79316

ARG BUILD_CREATED

LABEL org.opencontainers.image.created="${BUILD_CREATED}" \
      org.opencontainers.image.description="Always-on Skywright control-plane backend" \
      org.opencontainers.image.revision="@git.commit.id.full@" \
      org.opencontainers.image.source="https://github.com/Zorro909/skywright" \
      org.opencontainers.image.title="Skywright backend" \
      org.opencontainers.image.version="@project.version@" \
      io.skywright.skypilot.version="@skypilot.version@"

COPY --from=graalvm /usr/lib64/graalvm/graalvm-community-java25i3 /opt/graalvm

ENV JAVA_HOME=/opt/graalvm \
    PATH=/opt/graalvm/bin:$PATH

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install --yes --no-install-recommends \
        ca-certificates libpq5 libcrypt1 \
    && rm --recursive --force /var/lib/apt/lists/* \
    && ln --symbolic "$(ldconfig -p | awk '$1 == "libcrypt.so.1" {print $NF; exit}')" /usr/lib/libcrypt.so \
    && groupadd --gid 10001 skywright \
    && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent \
        --shell /usr/sbin/nologin skywright \
    && install --directory --owner 0 --group 0 --mode 0755 /opt/skywright

COPY --chown=0:0 maven/application.jar /opt/skywright/application.jar
COPY --chown=0:0 maven/graalpy-resources /opt/skywright/graalpy-resources
COPY --chown=0:0 --chmod=0755 start /opt/skywright/start

USER 10001:10001
WORKDIR /opt/skywright
ENV HOME=/tmp \
    XDG_CACHE_HOME=/tmp/.cache
EXPOSE 8080
STOPSIGNAL SIGTERM

ENTRYPOINT ["/opt/skywright/start", "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow", "-Xss16m", "-jar", "/opt/skywright/application.jar"]
