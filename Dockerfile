# Build stage
FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /usr/src/app
COPY . .
RUN mvn -DskipTests package \
 && mkdir -p /usr/src/app/extracted \
 && cd /usr/src/app/extracted \
 && jar -xf /usr/src/app/target/Bento-0.0.1.war

# Runtime stage - minimal distroless Java 21, non-root
FROM gcr.io/distroless/java21-debian13:nonroot AS final

WORKDIR /app

EXPOSE 8080

# Copy the exploded WAR (classes + lib jars) rather than running the packaged
# WAR directly. Spring Boot's executable WAR uses a nested-jar classloader
# (WarLauncher) under `java -jar`, which cannot resolve classpath resources
# (e.g. the GraphQL schema files) back to real filesystem paths. Running from
# an exploded directory with a plain classpath keeps those resources as
# regular files without requiring any application code changes.
COPY --from=build /usr/src/app/extracted/WEB-INF/classes /app/classes
COPY --from=build /usr/src/app/extracted/WEB-INF/lib /app/lib

ENTRYPOINT ["java", "-cp", "/app/classes:/app/lib/*", "gov.nih.nci.bento.BentoApplication"]