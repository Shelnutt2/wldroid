plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.git.version)
}

val gitVersion: groovy.lang.Closure<String> by extra

// Allow CI to override the version (computed before native builds dirty the tree)
version = findProperty("overrideVersion")?.toString() ?: gitVersion()

tasks.register("printVersion") {
    doLast {
        println(version)
    }
}
