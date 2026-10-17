FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :job-service:bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S openhire && adduser -S openhire -G openhire
USER openhire
WORKDIR /app
COPY --from=build /workspace/job-service/build/libs/job-service-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

