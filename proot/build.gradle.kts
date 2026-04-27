plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
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
}

// ── Cross-compile proot for Android arm64 ──
// CI can pass -PskipProot when the binaries are restored from cache.
val buildNative by tasks.registering(Exec::class) {
    description = "Cross-compile proot for Android arm64"
    group = "build"

    commandLine("echo", "TODO: native build")

    onlyIf { !project.hasProperty("skipProot") || project.property("skipProot") != "true" }
}

tasks.named("preBuild") {
    dependsOn(buildNative)
}

dependencies {
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.core)
    implementation(libs.datastore.preferences)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
