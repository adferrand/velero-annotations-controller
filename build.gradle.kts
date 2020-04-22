import io.quarkus.gradle.tasks.QuarkusNative
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

group = "net.pacalis"
version = "1.0-SNAPSHOT"

plugins {
    java
    id("io.quarkus") version "1.3.2.Final"

    kotlin("jvm") version "1.3.72"
    kotlin("plugin.allopen") version "1.3.72"
}

repositories {
     mavenLocal()
     mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))

    implementation(enforcedPlatform("io.quarkus:quarkus-universe-bom:1.3.2.Final"))
    implementation("io.quarkus:quarkus-smallrye-health")
    implementation("io.quarkus:quarkus-kubernetes-client")
    implementation("io.quarkus:quarkus-kotlin")

    testImplementation("io.quarkus:quarkus-junit5")
}

tasks {
    test {
        useJUnitPlatform()
        exclude("**/Native*")
    }

    named<QuarkusNative>("buildNative") {
        isEnableHttpUrlHandler = true
    }
}

allOpen {
    annotation("javax.ws.rs.Path")
    annotation("javax.enterprise.context.ApplicationScoped")
    annotation("io.quarkus.test.junit.QuarkusTest")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
