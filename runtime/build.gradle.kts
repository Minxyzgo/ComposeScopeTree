import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
}

val enableAndroid = providers.gradleProperty("android").isPresent
if (enableAndroid) pluginManager.apply("com.android.kotlin.multiplatform.library")

kotlin {
    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    if (enableAndroid) {
        extensions.configure<KotlinMultiplatformAndroidLibraryTarget> {
            namespace = "dev.composescopetree.runtime"
            compileSdk = 37
            minSdk = 26
            compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    sourceSets {
        commonMain {
            kotlin.srcDir("src/main/kotlin")
            dependencies {
                api("org.jetbrains.compose.runtime:runtime:1.12.2")
            }
        }
        jvmMain.dependencies {
            api("org.jetbrains.compose.runtime:runtime:1.13.0-beta01")
        }
    }
}
