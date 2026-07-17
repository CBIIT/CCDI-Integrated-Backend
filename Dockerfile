# Runtime stage - minimal distroless Java 21, non-root
FROM gcr.io/distroless/java21-debian13:nonroot AS final

WORKDIR /app

EXPOSE 8080

COPY target/Bento-0.0.1.war /app/app.war

CMD ["-jar", "/app/app.war"]