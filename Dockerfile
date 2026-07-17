# Build stage
FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /usr/src/app
COPY . .
RUN mvn -DskipTests package

# Runtime stage - minimal distroless Java 21, non-root
FROM gcr.io/distroless/java21-debian13:nonroot AS final

WORKDIR /app

EXPOSE 8080

COPY --from=build /usr/src/app/target/Bento-0.0.1.war /app/app.war

CMD ["-jar", "/app/app.war"]