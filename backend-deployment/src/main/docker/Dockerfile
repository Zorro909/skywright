FROM ghcr.io/graalvm/jdk-community:25i2-25.0.4-ol10@sha256:15428f0d444cb8811758c5b0dde8a2f1ab35792629cc5dd7526fc838d949f159 AS graalvm

FROM docker.io/library/ubuntu@sha256:1e0a86e57d247923571b75e0aaf48a1449cf8c543d51fb3e07a4a7d7bfa79316

ARG BUILD_CREATED

LABEL org.opencontainers.image.created="${BUILD_CREATED}" \
      org.opencontainers.image.description="Always-on Skywright control-plane backend" \
      org.opencontainers.image.revision="@git.commit.id.full@" \
      org.opencontainers.image.source="https://github.com/Zorro909/skywright" \
      org.opencontainers.image.title="Skywright backend" \
      org.opencontainers.image.version="@project.version@" \
      io.skywright.skypilot.version="@skypilot.version@"

COPY --from=graalvm /usr/lib64/graalvm/graalvm-community-java25i2 /opt/graalvm

ENV JAVA_HOME=/opt/graalvm \
    PATH=/opt/graalvm/bin:$PATH

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install --yes --no-install-recommends \
        ca-certificates libpq5 libcrypt1 \
    && rm --recursive --force /var/lib/apt/lists/* \
    && groupadd --gid 10001 skywright \
    && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent \
        --shell /usr/sbin/nologin skywright \
    && install --directory --owner 0 --group 0 --mode 0755 /opt/skywright

COPY --chown=0:0 maven/application.jar /opt/skywright/application.jar
COPY --chown=0:0 maven/graalpy-resources /opt/skywright/graalpy-resources

USER 10001:10001
WORKDIR /opt/skywright
ENV HOME=/tmp \
    XDG_CACHE_HOME=/tmp/.cache
EXPOSE 8080
STOPSIGNAL SIGTERM

ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow", "-Xss16m", "-jar", "/opt/skywright/application.jar"]
