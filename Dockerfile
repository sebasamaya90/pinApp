# ---- Stage 1: build + run unit tests (Java 25 / Maven) ----
FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# `package` runs the unit tests: the image is not produced if they fail.
RUN mvn -B clean package

# ---- Stage 2: slim runtime with the library jar + runnable examples ----
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /build/target/notifications-lib-1.0.0.jar ./notifications-lib.jar
COPY --from=build /build/target/lib ./lib
USER app
# Runs the demo (all channels are simulated: no network, no real credentials).
CMD ["java", "-cp", "notifications-lib.jar:lib/*", "com.pinapp.notifications.examples.NotificationExamples"]
