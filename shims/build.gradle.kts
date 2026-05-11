plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "nu.shell.wldroid.shims"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs("${project.layout.buildDirectory.get().asFile}/outputs/native")
        }
    }
}

// ── Cross-compile DRM/GBM/EGL/netstub shims for Android arm64 ──
// CI can pass -PskipShims when the binaries are restored from cache.
val buildNative by tasks.registering(Exec::class) {
    description = "Cross-compile shim libraries for Android arm64"
    group = "build"

    workingDir = rootProject.projectDir
    commandLine("bash", "${rootProject.projectDir}/shims/docker/build-all.sh")

    // Shims output directory
    environment("OUTPUT_DIR", "${project.layout.buildDirectory.get().asFile}/outputs/native")

    // Inputs: all native shim sources and build scripts
    inputs.files(fileTree("native") { include("**/*.c", "**/*.h", "**/meson.build", "**/*.sh") })
    inputs.file("docker/build-all.sh")

    // Output: entire native output directory (assets)
    outputs.dir("${project.layout.buildDirectory.get().asFile}/outputs/native")

    onlyIf { !project.hasProperty("skipShims") || project.property("skipShims") != "true" }

    doFirst {
        delete("${project.layout.buildDirectory.get().asFile}/outputs/native")
    }
}

tasks.named("preBuild") {
    dependsOn(buildNative)
}

dependencies {
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
// Instrumented test dependencies
dependencies {
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
}

