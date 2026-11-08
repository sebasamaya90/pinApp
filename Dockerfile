# Base images are pinned by digest (reproducible builds, no silent tag changes).
# Refresh with: docker buildx imagetools inspect <image:tag>   (Dependabot also proposes updates)

# ---- Stage 1: build + run unit tests (Java 25 / Maven) ----
FROM maven:3.9.11-eclipse-temurin-25@sha256:407c4423cec0cf2981055bc2c6c0dc211d9605b6669279b95997f2d1c7e91e2c AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# `package` runs the unit tests: the image is not produced if they fail.
RUN mvn -B clean package

# ---- Stage 2: slim runtime with the library jar + runnable examples ----
FROM eclipse-temurin:25-jre-alpine@sha256:e1898fd6a11fc965a994d8ace186fd3585aeb636cf8f05f47a723fcfee0b0918
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /build/target/notifications-lib-1.0.0.jar ./notifications-lib.jar
COPY --from=build /build/target/lib ./lib
USER app
# This is a demo runner, not a long-lived service, so there is no HEALTHCHECK.
# Recommended hardening at run time (the app needs no writes and no network):
#   docker run --rm --read-only --cap-drop ALL --security-opt no-new-privileges --network none <image>
CMD ["java", "-cp", "notifications-lib.jar:lib/*", "com.pinapp.notifications.examples.NotificationExamples"]
