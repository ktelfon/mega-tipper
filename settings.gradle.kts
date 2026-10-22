pluginManagement {
    repositories {
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        maven { url = uri("https://repo1.maven.org/maven2/") }
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "tipbot"
