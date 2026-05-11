// Shared Maven Central publishing configuration for WLDroid library modules.
//
// Each module must set these extras BEFORE applying this script:
//   extra["publishArtifactId"] = "wldroid-compositor"
//   extra["publishDescription"] = "Core Wayland compositor for Android"

apply(plugin = "maven-publish")
apply(plugin = "signing")

val publishArtifactId: String by project.extra
val publishDescription: String by project.extra

// Read version from catalog
val wldroidVersion: String = project.rootProject
    .extensions.getByType<VersionCatalogsExtension>()
    .named("libs")
    .findVersion("wldroid")
    .get()
    .requiredVersion

version = wldroidVersion
group = "nu.shell.wldroid"

// Empty Javadoc JAR (satisfies Maven Central requirement for Kotlin Android libraries)
val javadocJar = tasks.register("javadocJar", Jar::class.java) {
    archiveClassifier.set("javadoc")
}

// Publishing
afterEvaluate {
    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "nu.shell.wldroid"
                artifactId = publishArtifactId
                this.version = wldroidVersion

                artifact(javadocJar)

                pom {
                    name.set(publishArtifactId)
                    description.set(publishDescription)
                    url.set("https://github.com/AntonisShelworx/wldroid")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            id.set("shelnutt2")
                            name.set("Seth Shelnutt")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/AntonisShelworx/wldroid.git")
                        developerConnection.set("scm:git:ssh://github.com/AntonisShelworx/wldroid.git")
                        url.set("https://github.com/AntonisShelworx/wldroid")
                    }
                }
            }
        }

        repositories {
            maven {
                name = "sonatype"
                val isSnapshot = wldroidVersion.endsWith("-SNAPSHOT")
                url = uri(
                    if (isSnapshot) "https://s01.oss.sonatype.org/content/repositories/snapshots/"
                    else "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
                )
                credentials {
                    username = findProperty("ossrhUsername")?.toString()
                        ?: System.getenv("OSSRH_USERNAME") ?: ""
                    password = findProperty("ossrhPassword")?.toString()
                        ?: System.getenv("OSSRH_PASSWORD") ?: ""
                }
            }
        }
    }

    // Signing — only when key is available
    val signingKey = findProperty("signingKey")?.toString()
        ?: System.getenv("ORG_GRADLE_PROJECT_signingKey")
    val signingPassword = findProperty("signingPassword")?.toString()
        ?: System.getenv("ORG_GRADLE_PROJECT_signingPassword") ?: ""
    val signingKeyId = findProperty("signingKeyId")?.toString()
        ?: System.getenv("ORG_GRADLE_PROJECT_signingKeyId")

    if (!signingKey.isNullOrBlank()) {
        configure<SigningExtension> {
            useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
            sign(the<PublishingExtension>().publications)
        }
    }

}
