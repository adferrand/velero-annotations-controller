plugins {
    java
    // Enabling application plugin allow us to use tasks to create distribuable artifacts.
    application
}

repositories {
    jcenter()
}

dependencies {
    implementation("io.kubernetes:client-java-extended:7.0.0")
    implementation("org.slf4j:slf4j-simple:1.7.30")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.5.1")

    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.5.1")
}

application {
    // This value needs to be updated if Java class for the Controller application is changed.
    mainClassName = "velero.annotations.controller.ControllerApp"
}

val test by tasks.getting(Test::class) {
    useJUnitPlatform()
    systemProperty("test.kubeconfig.path", System.getProperty("test.kubeconfig.path"))
}
