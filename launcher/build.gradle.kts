plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "nu.shell.wldroid.launcher"
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

dependencies {
    api(project(":compositor"))
    api(project(":proot"))
    api(project(":virgl"))
    api(project(":shims"))
    api(libs.coroutines.core)
    implementation(libs.coroutines.android)
    api(libs.lifecycle.runtime.ktx)
    implementation(libs.annotation)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
}

extra["publishArtifactId"] = "wldroid-launcher"
extra["publishDescription"] = "High-level launcher orchestrating compositor, proot, virgl, and shims"
apply(from = "${rootProject.projectDir}/gradle/publishing.gradle.kts")
