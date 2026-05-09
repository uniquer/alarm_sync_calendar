pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://pkgs.dev.azure.com/MicrosoftDeviceSDK/7e621ec1-5377-4061-ab7d-944a99182f7c/_packaging/MicrosoftDeviceSDK/maven/v1") }
    }
}

rootProject.name = "Alarm Sync Calendar"
include(":app")