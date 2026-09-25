pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "EpubReader"
include(":app")
include(":pockettts-core", ":pockettts-service")
project(":pockettts-core").projectDir = file("third_party/PocketTTS-LiteRT/pockettts-core")
project(":pockettts-service").projectDir = file("third_party/PocketTTS-LiteRT/pockettts-service")
