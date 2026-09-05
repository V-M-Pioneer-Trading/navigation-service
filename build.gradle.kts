
plugins {
    java
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "de.vnm"
version = "0.0.1"
description = "Read-through cache for SpaceTraders waypoint, market and shipyard data"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

springBoot {
    mainClass.set("de.vnm.navigation.NavigationServiceApplication")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.7.0")
    // Clerk session verification, networkless (auth-design.md decision 10). Not in the Boot BOM, so pinned.
    implementation("com.nimbusds:nimbus-jose-jwt:9.47")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Only the executable jar is ever deployed; leaving the plain library jar enabled means
// build/libs holds two jars, and the Dockerfile's `COPY build/libs/*.jar app.jar` breaks
// the moment anything runs `build` instead of `bootJar`.
tasks.named<Jar>("jar") {
    enabled = false
}
