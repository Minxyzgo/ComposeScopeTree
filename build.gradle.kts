import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.PublishingExtension
import org.gradle.plugins.signing.SigningExtension

plugins {
    kotlin("jvm") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("com.android.application") version "9.1.1" apply false
}

group = "io.github.rwpp-team"
version = "0.1.0"

val centralStaging = layout.buildDirectory.dir("central-staging")

subprojects {
    group = rootProject.group
    version = rootProject.version

    plugins.withId("maven-publish") {
        extensions.configure<PublishingExtension> {
            publications.withType<MavenPublication>().configureEach {
                val publication = name
                artifact(tasks.register<Jar>("${publication}PublicationDocsJar") {
                    archiveClassifier.set("javadoc")
                    destinationDirectory.set(layout.buildDirectory.dir("publication-docs/$publication"))
                    from(rootProject.file("README.md"), rootProject.file("DESIGN.md"), rootProject.file("LICENSE"))
                })
                pom {
                    name.set("ComposeScopeTree ${project.name.removePrefix("compose-scope-tree-")}")
                    description.set("Observable Compose scope trees and per-instance editing for Kotlin Multiplatform.")
                    url.set("https://github.com/Minxyzgo/ComposeScopeTree")
                    licenses {
                        license {
                            name.set("Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("Minxyzgo")
                            name.set("Minxyzgo")
                            url.set("https://github.com/Minxyzgo")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/Minxyzgo/ComposeScopeTree.git")
                        developerConnection.set("scm:git:ssh://git@github.com/Minxyzgo/ComposeScopeTree.git")
                        url.set("https://github.com/Minxyzgo/ComposeScopeTree")
                    }
                }
            }
            repositories.maven {
                name = "centralStaging"
                url = uri(centralStaging)
            }
        }
        if (providers.gradleProperty("signing.gnupg.keyName").isPresent) {
            pluginManager.apply("signing")
            extensions.configure<SigningExtension> {
                useGpgCmd()
                sign(extensions.getByType<PublishingExtension>().publications)
            }
        }
    }
}

tasks.register<Zip>("bundleForCentral") {
    group = "publishing"
    description = "Bundles the signed desktop, Android and compiler publications."
    val androidIncluded = providers.gradleProperty("android").isPresent
    dependsOn(":compose-scope-tree-runtime:publishAllPublicationsToCentralStagingRepository",
        ":compose-scope-tree-compose-ui:publishAllPublicationsToCentralStagingRepository",
        ":compose-scope-tree-compiler-plugin:publishAllPublicationsToCentralStagingRepository", ":sample:test")
    from(centralStaging) {
        include("io/github/rwpp-team/compose-scope-tree-*/${project.version}/**")
    }
    archiveFileName.set("compose-scope-tree-${project.version}-central-bundle.zip")
    destinationDirectory.set(layout.buildDirectory)
    doFirst {
        check(androidIncluded) { "Use -Pandroid when building the Central bundle." }
        val staging = centralStaging.get().asFile.resolve("io/github/rwpp-team")
        for (suffix in listOf("runtime", "runtime-jvm", "runtime-android", "compose-ui", "compose-ui-jvm", "compose-ui-android", "compiler-plugin")) {
            val artifactId = "compose-scope-tree-$suffix"
            val file = staging.resolve("$artifactId/${project.version}/$artifactId-${project.version}.pom.asc")
            check(file.isFile) { "Missing signed publication $artifactId. Configure GPG signing before building the Central bundle." }
        }
    }
}
