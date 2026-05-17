// Shared Maven Central + GitHub Packages publishing configuration for WLDroid library modules.
//
// Each module must set these extras BEFORE applying this script:
//   extra["publishArtifactId"] = "wldroid-compositor"
//   extra["publishDescription"] = "Core Wayland compositor for Android"

import org.gradle.api.publish.maven.MavenPom

apply(plugin = "com.vanniktech.maven.publish")

val publishArtifactId: String by project.extra
val publishDescription: String by project.extra

version = rootProject.version.toString()
group = "nu.shel.wldroid"

// Vanniktech plugin classes are on the runtime classpath (from root plugins block)
// but not available at script compile time for apply(from=...) scripts.
// Use withGroovyBuilder for vanniktech-specific API and typed Action<MavenPom> for POM.
val mavenPublishing = extensions.getByName("mavenPublishing")

val pomAction = Action<MavenPom> {
    name.set(publishArtifactId)
    description.set(publishDescription)
    inceptionYear.set("2025")
    url.set("https://github.com/Shelnutt2/wldroid")
    licenses {
        license {
            name.set("The Apache License, Version 2.0")
            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
        }
    }
    developers {
        developer {
            id.set("shelnutt2")
            name.set("Seth Shelnutt")
            url.set("https://github.com/Shelnutt2/")
        }
    }
    scm {
        connection.set("scm:git:git://github.com/Shelnutt2/wldroid.git")
        developerConnection.set("scm:git:ssh://github.com/Shelnutt2/wldroid.git")
        url.set("https://github.com/Shelnutt2/wldroid")
    }
}

mavenPublishing.withGroovyBuilder {
    "publishToMavenCentral"()
    "signAllPublications"()
    "coordinates"("nu.shel.wldroid", publishArtifactId, rootProject.version.toString())
    "pom"(pomAction)
}

// Keep GitHub Packages as an additional publishing target
configure<PublishingExtension> {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/Shelnutt2/wldroid")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                    ?: findProperty("gpr.user")?.toString() ?: ""
                password = System.getenv("GITHUB_TOKEN")
                    ?: findProperty("gpr.key")?.toString() ?: ""
            }
        }
        maven {
            name = "FileSystemMavenRepo"
            url = uri("${rootProject.layout.buildDirectory.get().asFile}/maven-repo")
        }
    }
}
