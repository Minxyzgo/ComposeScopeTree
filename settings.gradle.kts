pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "compose-scope-tree"
include(":runtime")
include(":compiler-plugin")
include(":compose-ui")
include(":sample")
if (providers.gradleProperty("android").isPresent) include(":android-sample")
