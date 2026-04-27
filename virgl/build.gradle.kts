plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
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

    onlyIf { !project.hasProperty("skipVirgl") || project.property("skipVirgl") != "true" }
}

tasks.named("preBuild") {
    dependsOn(buildNative)
}

dependencies {
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.core)
    implementation(libs.datastore.preferences)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

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

