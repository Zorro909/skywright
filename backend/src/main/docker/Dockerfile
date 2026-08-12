FROM ghcr.io/graalvm/jdk-community:25i2-25.0.4-ol9@sha256:be43f73571b4617dba3653c5a19dfa1ec45ac61a822db877763129ca8e7c3540

ARG APPLICATION_VERSION
ARG BUILD_CREATED
ARG JAR_FILE
ARG SOURCE_REVISION

LABEL org.opencontainers.image.created="${BUILD_CREATED}" \
      org.opencontainers.image.description="Always-on Skywright control-plane backend" \
      org.opencontainers.image.revision="${SOURCE_REVISION}" \
      org.opencontainers.image.source="https://github.com/Zorro909/skywright" \
      org.opencontainers.image.title="Skywright backend" \
      org.opencontainers.image.version="${APPLICATION_VERSION}"

RUN groupadd --gid 10001 skywright \
    && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent \
        --shell /sbin/nologin skywright \
    && install --directory --owner 10001 --group 10001 /opt/skywright

COPY --chown=10001:10001 ${JAR_FILE} /opt/skywright/application.jar

USER 10001:10001
WORKDIR /opt/skywright
EXPOSE 8080
STOPSIGNAL SIGTERM

ENTRYPOINT ["java", "-jar", "/opt/skywright/application.jar"]
