pluginManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        maven("https://repo1.maven.org/maven2/")
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        maven("https://repo1.maven.org/maven2/")
        mavenCentral()
    }
}

rootProject.name = "tipbot"
