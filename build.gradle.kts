plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.git.version)
    alias(libs.plugins.maven.publish) apply false
}

val gitVersion: groovy.lang.Closure<String> by extra

// Allow CI to override the version (computed before native builds dirty the tree).
// If the property "snapshot" is set (e.g., -Psnapshot), append -SNAPSHOT.
val resolvedVersion = findProperty("overrideVersion")?.toString() ?: gitVersion()
version = if (project.hasProperty("snapshot")) {
    resolvedVersion.replace(Regex("-\\d+-g[0-9a-f]+$"), "") + "-SNAPSHOT"
} else {
    resolvedVersion
}
