plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "nu.shell.wldroid.proot"
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


    // Proot binaries are exec'd as separate processes, not loaded via JNI.
    // They must be extracted to disk (useLegacyPackaging) to be launchable.
    packaging {
        jniLibs.useLegacyPackaging = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// ── Cross-compile proot for Android arm64 ──
// CI can pass -PskipProot when the binaries are restored from cache.
val buildNative by tasks.registering(Exec::class) {
    description = "Cross-compile proot for Android arm64"
    group = "build"

    commandLine("bash", "${projectDir}/native/build-proot.sh")

    // Inputs: external proot sources, build script, patches
    inputs.files(fileTree("${rootProject.projectDir}/external/proot/src") {
        include("**/*.c", "**/*.h", "**/Makefile", "**/GNUmakefile")
        exclude("**/test/**", "**/tests/**", "**/docs/**")
    })
    inputs.file("native/build-proot.sh")
    inputs.dir("native/patches")
    inputs.property("ndkHome", providers.environmentVariable("ANDROID_NDK_HOME").orElse(
        providers.environmentVariable("ANDROID_NDK")
    ).orElse(""))

    // Outputs: two .so files
    outputs.file("src/main/jniLibs/arm64-v8a/libproot.so")
    outputs.file("src/main/jniLibs/arm64-v8a/libproot-loader.so")

    onlyIf { !project.hasProperty("skipProot") || project.property("skipProot") != "true" }
}

tasks.named("preBuild") {
    dependsOn(buildNative)
}

dependencies {
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.core)
    implementation(libs.datastore.preferences)
    api(libs.commons.compress)
    api(libs.xz)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.core)
    testImplementation("org.json:json:20231013")
}
// Instrumented test dependencies
dependencies {
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.coroutines.test)
}


extra["publishArtifactId"] = "wldroid-proot"
extra["publishDescription"] = "PRoot environment management for running Linux apps on Android"
apply(from = "${rootProject.projectDir}/gradle/publishing.gradle.kts")
