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
// If the property "snapshot" is set (e.g., -Psnapshot), bump the patch version
// and append -SNAPSHOT for next-development-cycle semantics.
val resolvedVersion = findProperty("overrideVersion")?.toString() ?: gitVersion()
version = if (project.hasProperty("snapshot")) {
    // Strip commit-count, hash, and optional .dirty suffix to get base version.
    val baseVersion = resolvedVersion
        .removePrefix("v")
        .replace(Regex("-\\d+-g[0-9a-f]+(\\.dirty)?$"), "")
        .removeSuffix(".dirty")   // handles edge case: tagged commit with dirty tree
    // Bump patch version for next-development-cycle semantics.
    val parts = baseVersion.split(".")
    val bumped = if (parts.size == 3) {
        "${parts[0]}.${parts[1]}.${parts[2].toInt() + 1}"
    } else {
        baseVersion  // fallback: don't bump if format is unexpected
    }
    "$bumped-SNAPSHOT"
} else {
    resolvedVersion
}
