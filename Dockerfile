# syntax=docker/dockerfile:1.7
# Gradle is already installed in this image. Do not invoke the wrapper here:
# Docker Desktop may be able to pull images while services.gradle.org is not
# reachable from the build network, which made every service build fail before
# compilation even started.
FROM gradle:9.2.1-jdk25 AS builder

ARG SERVICE
USER root
WORKDIR /workspace

COPY settings.gradle build.gradle ./
COPY gradle gradle
COPY platform-starter platform-starter
COPY identity-service identity-service
COPY api-gateway api-gateway
COPY job-service job-service
COPY approval-service approval-service
COPY application-service application-service
COPY service-support service-support
COPY candidate-service candidate-service
COPY resume-processor resume-processor
COPY interview-service interview-service
COPY offer-service offer-service
COPY employee-service employee-service
COPY ai-gateway ai-gateway
COPY ai-workers ai-workers
COPY talent-service talent-service
COPY compliance-service compliance-service
COPY analytics-service analytics-service
COPY communication-service communication-service
COPY notification-dispatcher notification-dispatcher
COPY integration-hub integration-hub

RUN --mount=type=cache,id=openhire-gradle-cache,target=/home/gradle/.gradle/caches,sharing=locked \
    gradle ":${SERVICE}:bootJar" --no-daemon \
    && cp "${SERVICE}/build/libs/${SERVICE}.jar" /workspace/application.jar

FROM eclipse-temurin:25-jre

RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system --gid 10001 openhire \
    && useradd --system --uid 10001 --gid openhire --home-dir /app --shell /usr/sbin/nologin openhire

WORKDIR /app
RUN mkdir -p /app/resumes && chown openhire:openhire /app/resumes
COPY --from=builder --chown=openhire:openhire /workspace/application.jar application.jar

USER openhire
# Serial GC, C1-only JIT and small thread stacks keep each JVM's resident memory low, so the whole local stack fits
# Docker Desktop's default 8 GB. These are local-development images.
# JVM_RAM_PERCENT lowers the heap share for services whose container limit is tight; non-heap memory needs the rest.
ENV JVM_RAM_PERCENT=75
ENTRYPOINT ["sh", "-c", "exec java -XX:MaxRAMPercentage=${JVM_RAM_PERCENT} -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -XX:+ExitOnOutOfMemoryError -jar /app/application.jar"]
