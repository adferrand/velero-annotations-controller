plugins {
    java
    application
}

repositories {
    jcenter()
}

dependencies {
    implementation("io.kubernetes:client-java-extended:6.0.1")
    implementation("org.slf4j:slf4j-simple:1.7.30")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.5.1")

    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.5.1")
}

application {
    mainClassName = "velero.annotations.controller.ControllerApp"
}

val test by tasks.getting(Test::class) {
    useJUnitPlatform()
}
