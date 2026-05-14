plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "nu.shel.wldroid.compositor"
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
}

// ── Cross-compile the Wayland compositor via Meson/Ninja ──
// CI can pass -PskipCompositor when the .so is restored from cache.
val buildNative by tasks.registering(Exec::class) {
    description = "Cross-compile the Wayland compositor for Android arm64"
    group = "build"

    workingDir = project.projectDir
    commandLine("bash", "${project.projectDir}/native/scripts/build.sh")

    // Forward NDK location to the build script
    environment("ANDROID_NDK_HOME", providers.environmentVariable("ANDROID_NDK_HOME").orElse(
        providers.environmentVariable("ANDROID_NDK")
    ).orElse("").get())

    // Inputs: native C sources, meson build files, build script, external wlroots
    inputs.files(fileTree("native/src") { include("**/*.c", "**/*.h", "**/meson.build") })
    inputs.file("native/meson.build")
    inputs.file("native/scripts/build.sh")
    inputs.files(fileTree("native/subprojects") { include("**/*.wrap", "packagefiles/**") })
    inputs.files(fileTree("${rootProject.projectDir}/external/wlroots") {
        include("**/*.c", "**/*.h", "**/meson.build")
        exclude("**/test/**", "**/tests/**", "**/docs/**", "**/examples/**")
    })
    inputs.property("ndkHome", providers.environmentVariable("ANDROID_NDK_HOME").orElse(
        providers.environmentVariable("ANDROID_NDK")
    ).orElse(""))

    // Output: single .so
    outputs.file("src/main/jniLibs/arm64-v8a/libwldroid-compositor.so")

    onlyIf { !project.hasProperty("skipCompositor") || project.property("skipCompositor") != "true" }
}

tasks.named("preBuild") {
    dependsOn(buildNative)
}

dependencies {
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.core)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.annotation)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
// Instrumented test dependencies
dependencies {
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.coroutines.test)
}


extra["publishArtifactId"] = "wldroid-compositor"
extra["publishDescription"] = "Core Wayland compositor with wlroots backend for Android"
apply(from = "${rootProject.projectDir}/gradle/publishing.gradle.kts")
