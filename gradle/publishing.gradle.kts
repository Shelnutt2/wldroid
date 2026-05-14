// Shared GitHub Packages publishing configuration for WLDroid library modules.
//
// Each module must set these extras BEFORE applying this script:
//   extra["publishArtifactId"] = "wldroid-compositor"
//   extra["publishDescription"] = "Core Wayland compositor for Android"

apply(plugin = "maven-publish")

val publishArtifactId: String by project.extra
val publishDescription: String by project.extra

version = rootProject.version.toString()
group = "nu.shell.wldroid"

// Empty Javadoc JAR (satisfies repository requirements for Kotlin Android libraries)
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
                this.version = rootProject.version.toString()

                artifact(javadocJar)

                pom {
                    name.set(publishArtifactId)
                    description.set(publishDescription)
                    url.set("https://github.com/Shelnutt2/wldroid")
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
                        connection.set("scm:git:git://github.com/Shelnutt2/wldroid.git")
                        developerConnection.set("scm:git:ssh://github.com/Shelnutt2/wldroid.git")
                        url.set("https://github.com/Shelnutt2/wldroid")
                    }
                }
            }
        }

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
}
