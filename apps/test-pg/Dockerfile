FROM eclipse-temurin:21-jre
ENV SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=8080
WORKDIR /app
COPY apps/test-pg/build/libs/test-pg.jar /app/test-pg.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/test-pg.jar"]
