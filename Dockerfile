# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:17-jdk-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates git unzip \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

COPY . .

ARG GRADLE_TASK=:plugin-idea:buildPlugin
ARG GRADLE_ARGS=

ENV GRADLE_USER_HOME=/workspace/.gradle

LABEL org.opencontainers.image.title="AI Test Plugin Build Workspace"
LABEL org.opencontainers.image.description="Contains the full source tree, Gradle cache, downloaded dependencies, and built IntelliJ plugin outputs."

RUN ./gradlew --no-daemon --stacktrace ${GRADLE_TASK} ${GRADLE_ARGS}

CMD ["bash"]
