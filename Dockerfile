# Build stage
FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /usr/src/app
COPY . .
RUN mvn -DskipTests clean package \
 && mkdir -p /usr/src/app/extracted \
 && cd /usr/src/app/extracted \
 && jar -xf /usr/src/app/target/Bento-0.0.1.war

# Runtime stage - patched Temurin Java 21, non-root
FROM eclipse-temurin:21.0.13_9-jre-noble AS final

ARG TARGETARCH
ENV JAVA_VERSION=jdk-21.0.13.1+2

# Bootstrap from the published image until Temurin publishes its patched image.
RUN set -eu; \
    case "$TARGETARCH" in \
        amd64) arch=x64; checksum=18f44fab9065217f3ba61d0eef8a7b5f3c3713aa7293001b0d4ac10ede3850f0 ;; \
        ppc64le) arch=ppc64le; checksum=7fbeba1a2ba03533657bf16655e642de7b32c1bf074a3e2eb406f7cfc0f29a14 ;; \
        s390x) arch=s390x; checksum=bd196e3e017243173ad9a4fcf54abb5da91ebc9fca960578b1451dc6a3d6c48c ;; \
        *) echo "No verified Temurin 21.0.13.1 JRE configured for architecture: $TARGETARCH" >&2; exit 1 ;; \
    esac; \
    apt-get update; \
    DEBIAN_FRONTEND=noninteractive apt-get upgrade -y --no-install-recommends; \
    rm -rf /var/lib/apt/lists; \
    curl --fail --location --show-error --silent \
        "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13.1%2B2/OpenJDK21U-jre_${arch}_linux_hotspot_21.0.13.1_2.tar.gz" \
        --output /tmp/openjdk.tar.gz; \
    echo "$checksum  /tmp/openjdk.tar.gz" | sha256sum --check -; \
    rm -rf /opt/java/openjdk; \
    mkdir -p /opt/java/openjdk; \
    tar --extract --file /tmp/openjdk.tar.gz --directory /opt/java/openjdk --strip-components=1 --no-same-owner; \
    rm /tmp/openjdk.tar.gz; \
    java -version

WORKDIR /app
RUN mkdir -p /app/logs && chown 65532:65532 /app/logs
USER 65532:65532

EXPOSE 8080

# Copy the exploded WAR (classes + lib jars) rather than running the packaged
# WAR directly. Spring Boot's executable WAR uses a nested-jar classloader
# (WarLauncher) under `java -jar`, which cannot resolve classpath resources
# (e.g. the GraphQL schema files) back to real filesystem paths. Running from
# an exploded directory with a plain classpath keeps those resources as
# regular files without requiring any application code changes.
COPY --from=build /usr/src/app/extracted/WEB-INF/classes /app/classes
COPY --from=build /usr/src/app/extracted/WEB-INF/lib /app/lib

ENTRYPOINT ["java", "-XX:+ExitOnOutOfMemoryError", "-Xms3g", "-Xmx6g", "-cp", "/app/classes:/app/lib/*", "gov.nih.nci.bento.BentoApplication"]
