# syntax=docker/dockerfile:1

FROM gradle:8.10.2-jdk21 AS build
WORKDIR /app

COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src

RUN gradle --no-daemon bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app

ENV SQLITE_DB_PATH=/data/nav.db
ENV SPRING_PROFILES_ACTIVE=prod

EXPOSE 8080
VOLUME ["/data"]

COPY --from=build /app/build/libs/*.jar app.jar

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
