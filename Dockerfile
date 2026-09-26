# syntax=docker/dockerfile:1

FROM gradle:8.10.2-jdk21 AS build
WORKDIR /app

# Dependencies first, in a layer that depends on the build files only, so the
# CI layer cache keeps it until they change and a code-only change skips the
# download. `gradle dependencies` would fetch metadata only; resolving each
# configuration fetches the jars. Test configurations are skipped: bootJar
# never needs them.
COPY settings.gradle.kts build.gradle.kts ./
COPY <<'EOF' /tmp/resolve-dependencies.gradle
allprojects {
    tasks.register('resolveDependencies') {
        doLast {
            configurations
                .findAll { it.canBeResolved && !it.name.startsWith('test') }
                .each { it.resolve() }
        }
    }
}
EOF
RUN gradle --no-daemon --init-script /tmp/resolve-dependencies.gradle resolveDependencies

COPY src ./src

# --offline: everything bootJar needs must already be in the layer above. If
# the split ever misses something, this fails loudly instead of quietly
# downloading on every build again.
RUN gradle --no-daemon --offline bootJar

FROM eclipse-temurin:21-jre
WORKDIR /app

ENV SQLITE_DB_PATH=/data/nav.db
ENV SPRING_PROFILES_ACTIVE=prod

EXPOSE 8080
VOLUME ["/data"]

COPY --from=build /app/build/libs/*.jar app.jar

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
