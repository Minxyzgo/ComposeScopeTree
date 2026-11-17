import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

val compilerPluginConfig = configurations.create("compilerPlugin") {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    implementation(project(":runtime"))
    implementation(project(":compose-ui"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    compilerPluginConfig(project(path = ":compiler-plugin", configuration = "runtimeElements"))
}

application {
    mainClass.set("dev.composescopetree.sample.HeadlessSampleKt")
}

tasks.register<JavaExec>("runImplicit") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.composescopetree.sample.ImplicitBoxSampleKt")
}

tasks.register<JavaExec>("benchmarkImplicit") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.composescopetree.sample.ImplicitBenchmarkKt")
}

val withoutScopeTreeCompiler = providers.gradleProperty("withoutScopeTreeCompiler").isPresent
val verifyScopeTreeIr = providers.gradleProperty("verifyScopeTreeIr").isPresent

tasks.withType<KotlinCompile>().configureEach {
    if (!withoutScopeTreeCompiler) {
        inputs.files(compilerPluginConfig)
        compilerOptions.freeCompilerArgs.add(
            "-Xcompiler-plugin-order=dev.composescopetree.compiler>androidx.compose.compiler.plugins.kotlin"
        )
        compilerOptions.freeCompilerArgs.add(
            providers.provider { "-Xplugin=${compilerPluginConfig.singleFile.absolutePath}" }
        )
    }
    if (verifyScopeTreeIr) {
        compilerOptions.freeCompilerArgs.add("-Xverify-ir=error")
    }
}
