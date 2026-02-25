pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://chaquo.com/maven") }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://chaquo.com/maven") }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "noyt-droid"
include(":app")
