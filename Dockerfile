# Build stage
FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /usr/src/app
COPY . .
RUN mvn package -DskipTests

# Runtime stage - Java 21 JRE on a maintained Ubuntu base
FROM eclipse-temurin:21-jre-jammy AS final

WORKDIR /app

EXPOSE 8080

COPY --from=build /usr/src/app/target/Bento-0.0.1.war /app/app.war

CMD ["-jar", "/app/app.war"]