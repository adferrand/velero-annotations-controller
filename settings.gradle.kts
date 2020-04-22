pluginManagement {
    repositories {
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
    }

    plugins {
      id("io.quarkus") version "1.3.2.Final"
    }
}

rootProject.name = "velero-annotations-controller"
