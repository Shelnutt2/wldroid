plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "nu.shell.wldroid.compositor"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// ── Cross-compile the Wayland compositor via Meson/Ninja ──
// CI can pass -PskipCompositor when the .so is restored from cache.
val buildNative by tasks.registering(Exec::class) {
    description = "Cross-compile the Wayland compositor for Android arm64"
    group = "build"

    commandLine("echo", "TODO: native build")

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
