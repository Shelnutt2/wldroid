plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "nu.shell.wldroid.virgl"
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


    // VirGL server binary is exec'd as a separate process, not loaded via JNI.
    // It must be extracted to disk (useLegacyPackaging) to be launchable.
    packaging {
        jniLibs.useLegacyPackaging = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// ── Cross-compile virgl_test_server for Android arm64 ──
// CI can pass -PskipVirgl when the binary is restored from cache.
val buildNative by tasks.registering(Exec::class) {
    description = "Cross-compile virgl_test_server for Android arm64"
    group = "build"

    workingDir = project.projectDir
    commandLine("bash", "${project.projectDir}/native/build-virgl.sh")

    // Inputs: external virglrenderer sources, build script
    inputs.files(fileTree("${rootProject.projectDir}/external/virglrenderer") {
        include("**/*.c", "**/*.h", "**/meson.build")
        exclude("**/test/**", "**/tests/**", "**/docs/**")
    })
    inputs.file("native/build-virgl.sh")
    inputs.property("ndkHome", providers.environmentVariable("ANDROID_NDK_HOME").orElse(
        providers.environmentVariable("ANDROID_NDK")
    ).orElse(""))

    // Outputs: two .so files
    outputs.file("src/main/jniLibs/arm64-v8a/libvirgl-test-server.so")
    outputs.file("src/main/jniLibs/arm64-v8a/libvirgl-render-server.so")

    onlyIf { !project.hasProperty("skipVirgl") || project.property("skipVirgl") != "true" }
}

tasks.named("preBuild") {
    dependsOn(buildNative)
}

dependencies {
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.core)
    implementation(libs.datastore.preferences)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
}
// Instrumented test dependencies
dependencies {
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.coroutines.test)
}

