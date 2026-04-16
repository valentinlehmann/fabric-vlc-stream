pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9"
}

stonecutter {
    create(rootProject) {
        versions("1.21.4", "1.21.8", "1.21.11", "26.1.2")
        vcsVersion = "1.21.11"
    }
}

rootProject.name = "vlc-stream"
