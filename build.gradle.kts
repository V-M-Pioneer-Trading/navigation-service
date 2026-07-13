
plugins {
    java
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "de.vnm"
version = "0.0.1"

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
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.7.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
