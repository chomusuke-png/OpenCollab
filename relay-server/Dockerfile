# OpenCollab relay server image. Build from the repository root:
#   docker build -f relay-server/Dockerfile -t opencollab-relay .
# See docs/RELAY_SERVER.md ("Docker") for compose files and the TLS choices.

# ---- Build: only core, transport and the relay (no IntelliJ download) ----------------------
# On the machine's own platform: the JAR is the same for every platform, so an arm64 image
# does not compile under emulation.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /src
# The image has no git history, so the version is passed in.
ARG VERSION=dev
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY core core
COPY transport transport
COPY relay-server relay-server
RUN sh ./gradlew --no-daemon --no-configuration-cache -PrelayOnly=true -PbuildVersion="$VERSION" \
        :relay-server:shadowJar -x test \
    && cp relay-server/build/libs/opencollab-relay-*-all.jar /relay.jar

# ---- Run ------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre
ARG VERSION=dev
LABEL org.opencontainers.image.title="OpenCollab relay" \
      org.opencontainers.image.description="Self-hosted relay for the OpenCollab IntelliJ plugin" \
      org.opencontainers.image.source="https://github.com/chomusuke-png/OpenCollab" \
      org.opencontainers.image.version="$VERSION"
RUN useradd --system --uid 10001 --home-dir /data --shell /usr/sbin/nologin opencollab \
    && mkdir -p /data /opt/opencollab && chown opencollab /data
COPY --from=build /relay.jar /opt/opencollab/relay.jar
USER 10001
WORKDIR /data
# The kept self-signed certificate lives here.
VOLUME /data
EXPOSE 8443
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/opt/opencollab/relay.jar"]
# Default: self-signed certificate kept in /data (stable fingerprint), secret from a compose or
# swarm secret. Replace the arguments to use a keystore or a reverse proxy.
CMD ["--self-signed", "--keep-certificate", "/data/relay.p12", "--host-secret-file", "/run/secrets/relay_secret"]
