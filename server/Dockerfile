# Build stage: uses the Gradle wrapper so the image builds with the same Gradle as local dev.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null
COPY src ./src
RUN ./gradlew --no-daemon installDist -x test

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 insta && mkdir -p /data/media && chown insta /data/media
COPY --from=build /src/build/install/server /app
USER insta
EXPOSE 8080
ENTRYPOINT ["/app/bin/server"]
