# Consuming WLDroid

This document describes how to add WLDroid modules to an Android project.

## Requirements

- `minSdk` 29 or higher
- `compileSdk` 35 or higher
- Java 17 source/target compatibility
- Modules that ship native executables (`wldroid-proot`, `wldroid-virgl`) require
  `packaging { jniLibs.useLegacyPackaging = true }` in the consuming app's
  `android` block so the binaries are extracted to disk at install time.

## Maven coordinates

All modules are published under group `nu.shell.wldroid`:

| Module | Artifact ID | Description |
|---|---|---|
| compositor | `wldroid-compositor` | Core Wayland compositor (wlroots backend) |
| proot | `wldroid-proot` | PRoot environment management for Linux apps |
| virgl | `wldroid-virgl` | VirGL server and GPU capability detection |
| shims | `wldroid-shims` | DRM/GBM/EGL/netstub shim libraries |
| launcher | `wldroid-launcher` | High-level launcher orchestrating all modules |
| ui | `wldroid-ui` | Jetpack Compose UI components |

## Which modules to declare

Most apps should declare only the high-level modules they need:

```kotlin
// Launcher-only (no UI):
implementation("nu.shell.wldroid:wldroid-launcher:<version>")

// Full UI integration:
implementation("nu.shell.wldroid:wldroid-ui:<version>")

// Both launcher and UI:
implementation("nu.shell.wldroid:wldroid-launcher:<version>")
implementation("nu.shell.wldroid:wldroid-ui:<version>")
```

`wldroid-launcher` and `wldroid-ui` transitively depend on `wldroid-compositor`,
`wldroid-proot`, `wldroid-virgl`, and `wldroid-shims` via `api` scope, so you
do not need to declare the leaf modules yourself.

`wldroid-proot` transitively provides `commons-compress` and `xz` (used for
rootfs extraction), so consumers do not need to add those manually.

## Resolution options

### Option 1: Maven repo zip from GitHub Releases (recommended, no auth required)

Each release attaches a `wldroid-maven-repo.zip` containing a complete Maven
repository with AARs, POMs (including transitive dependency declarations),
Gradle module metadata, and checksums.

```bash
# Download and extract the Maven repo
curl -L -o wldroid-maven-repo.zip \
  https://github.com/Shelnutt2/wldroid/releases/download/v<VERSION>/wldroid-maven-repo.zip
unzip wldroid-maven-repo.zip -d /path/to/wldroid-maven
```

In `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("/path/to/wldroid-maven")
            content { includeGroup("nu.shell.wldroid") }
        }
    }
}
```

Or automate extraction in CI and point to it via an environment variable:

```kotlin
// settings.gradle.kts
val wldroidMavenRepo = System.getenv("WLDROID_LOCAL_MAVEN_REPO")
if (wldroidMavenRepo != null) {
    maven {
        url = uri(wldroidMavenRepo)
        content { includeGroup("nu.shell.wldroid") }
    }
}
```

### Option 2: GitHub Packages (requires authentication)

GitHub Packages requires a personal access token with `read:packages` scope,
even for public packages.

In `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/Shelnutt2/wldroid")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                    ?: providers.gradleProperty("gpr.user").orNull ?: ""
                password = System.getenv("GITHUB_TOKEN")
                    ?: providers.gradleProperty("gpr.key").orNull ?: ""
            }
            content { includeGroup("nu.shell.wldroid") }
        }
    }
}
```

For local builds, add to `~/.gradle/gradle.properties`:

```properties
gpr.user=YOUR_GITHUB_USERNAME
gpr.key=ghp_YOUR_PERSONAL_ACCESS_TOKEN
```

The token needs the `read:packages` scope.

For CI, set `GITHUB_ACTOR` and `GITHUB_TOKEN` environment variables. Note that
the default `GITHUB_TOKEN` in GitHub Actions can only read packages from the
same repository or organization, so cross-org consumers need a PAT.

### Option 3: Release AARs (manual fallback)

Each release also attaches individual AAR files. These can be placed into a
local Maven directory layout, but you must provide your own POM files with
dependency declarations. This approach is not recommended because transitive
dependencies will not resolve automatically.

## Verifying artifacts

Each release includes a `checksums-sha256.txt` file. Verify downloaded
artifacts with:

```bash
sha256sum -c checksums-sha256.txt
```

## Troubleshooting

### Missing classes at runtime (R8/release builds)

If using ProGuard/R8, WLDroid modules include `consumer-rules.pro` files that
are applied automatically. If you still see missing class errors, verify that
all transitive dependencies are resolving correctly:

```bash
./gradlew app:dependencies --configuration releaseRuntimeClasspath | grep wldroid
```

### `useLegacyPackaging` errors

If proot or virgl binaries fail to launch with permission errors, ensure the
app's `build.gradle.kts` includes:

```kotlin
android {
    packaging {
        jniLibs.useLegacyPackaging = true
    }
}
```
