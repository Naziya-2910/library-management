FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/library-backend.jar app.jar
RUN chown -R 10001:10001 /app
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
