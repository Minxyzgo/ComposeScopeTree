import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    `maven-publish`
}

val enableAndroid = providers.gradleProperty("android").isPresent
if (enableAndroid) pluginManager.apply("com.android.kotlin.multiplatform.library")

kotlin {
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    if (enableAndroid) {
        extensions.configure<KotlinMultiplatformAndroidLibraryTarget> {
            namespace = "dev.composescopetree.ui"
            compileSdk = 37
            minSdk = 26
            withSourcesJar()
            compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    sourceSets {
        commonMain {
            kotlin.srcDir("src/main/kotlin")
            dependencies {
                api("org.jetbrains.compose.foundation:foundation:1.12.2")
                api(project(":compose-scope-tree-runtime"))
            }
        }
        jvmMain.dependencies {
            api("org.jetbrains.compose.foundation:foundation:1.13.0-beta01")
        }
    }
}
