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
for (module in listOf("runtime", "compiler-plugin", "compose-ui")) {
    val path = ":compose-scope-tree-$module"
    include(path)
    project(path).projectDir = file(module)
}
include(":sample")
if (providers.gradleProperty("android").isPresent) include(":android-sample")
