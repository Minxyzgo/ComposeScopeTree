import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.composescopetree.androidsample"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.composescopetree.androidsample"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = rootProject.version.toString()
    }

    buildFeatures.compose = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val scopeTreeCompiler = configurations.create("scopeTreeCompiler") {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    implementation(project(":compose-scope-tree-compose-ui"))
    implementation("androidx.activity:activity-compose:1.13.0")
    add(scopeTreeCompiler.name, project(path = ":compose-scope-tree-compiler-plugin", configuration = "runtimeElements"))
}

tasks.withType<KotlinCompile>().configureEach {
    inputs.files(scopeTreeCompiler)
    compilerOptions.freeCompilerArgs.add(
        "-Xcompiler-plugin-order=dev.composescopetree.compiler>androidx.compose.compiler.plugins.kotlin"
    )
    compilerOptions.freeCompilerArgs.add(providers.provider { "-Xplugin=${scopeTreeCompiler.singleFile.absolutePath}" })
}
