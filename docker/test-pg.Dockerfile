FROM gradle:8.13-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :apps:test-pg:bootJar --no-daemon

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=build /workspace/apps/test-pg/build/libs/test-pg-*.jar /app/test-pg.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/test-pg.jar"]
