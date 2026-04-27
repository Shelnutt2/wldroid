# Development Guide

This guide covers setting up a development environment, building from source, running tests, and contributing to WLDroid.

## Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| Android Studio | Latest stable | IDE with Android SDK management |
| Android SDK | compileSdk 35 | Android API level |
| Android NDK | r28 | Native code cross-compilation |
| Java | 17 | Gradle and Kotlin compilation |
| Meson | ≥ 1.0 | Native build system (compositor, shims) |
| Ninja | Latest | Build backend for Meson |
| Python 3 | ≥ 3.8 | Meson dependency |
| Git | ≥ 2.20 | Submodule support |
| Docker | Optional | Reproducible shim builds |
| `aarch64-linux-gnu-gcc` | Optional | Local shim cross-compilation |

### macOS Setup

```bash
brew install meson ninja python3
# NDK is managed by Android Studio or sdkmanager
```

### Ubuntu/Debian Setup

```bash
sudo apt install meson ninja-build python3 python3-pip
# For local shim cross-compilation:
sudo apt install gcc-aarch64-linux-gnu g++-aarch64-linux-gnu
```

### NDK Installation

Via Android Studio: **Settings → Languages & Frameworks → Android SDK → SDK Tools → NDK (Side by side)** → install version matching `gradle.properties`.

Or via command line:
```bash
sdkmanager "ndk;28.0.12674087"
```

## Getting Started

### Clone the Repository

```bash
git clone --recursive https://github.com/Shelnutt2/wldroid.git
cd wldroid
```

If you forgot `--recursive`:
```bash
git submodule update --init --recursive
```

### First Build

```bash
# Full build (includes native compilation — may take 10+ minutes first time)
./gradlew assembleDebug

# Quick Kotlin-only build (skip native)
./gradlew assembleDebug -PskipCompositor -PskipProot -PskipVirgl -PskipShims
```

### Open in Android Studio

1. Open the `wldroid` directory in Android Studio
2. Wait for Gradle sync to complete
3. Select the `:testapp` run configuration
4. Connect a device or start an emulator
5. Run

## Project Structure

```
wldroid/
├── build.gradle.kts          # Root build: plugin declarations (no apply)
├── settings.gradle.kts       # Module includes
├── gradle.properties         # Version pins, NDK version, skip flags
├── gradle/
│   └── libs.versions.toml    # Dependency version catalog
├── compositor/               # Core compositor module
│   ├── build.gradle.kts
│   ├── native/               # C source (Meson build)
│   │   ├── meson.build
│   │   ├── src/              # Compositor C source
│   │   ├── tests/            # Native unit tests
│   │   └── scripts/build.sh
│   └── src/main/java/        # Kotlin API
├── proot/                    # Proot environment module
│   ├── build.gradle.kts
│   └── src/main/java/        # Kotlin API
├── virgl/                    # VirGL + GPU detection module
│   ├── build.gradle.kts
│   └── src/main/java/        # Kotlin API
├── shims/                    # Shim libraries module
│   ├── build.gradle.kts
│   ├── native/               # Shim C source (5 sub-libraries)
│   │   ├── drm-shim/
│   │   ├── gbm-shim/
│   │   ├── egl-override/
│   │   ├── netstub/
│   │   └── drm-wrapper/
│   └── src/main/java/        # Kotlin API
├── ui/                       # Compose UI components
│   ├── build.gradle.kts
│   └── src/main/java/
├── testapp/                  # Demo/test application
│   ├── build.gradle.kts
│   └── src/
├── external/                 # Git submodules (17 dependencies)
├── scripts/                  # Build and CI scripts
├── docs/                     # Documentation
├── AGENTS.md                 # AI assistant context
├── README.md                 # Project overview
└── LICENSE                   # AGPL-3.0
```

## Building

### Individual Module Builds

```bash
./gradlew :compositor:assembleDebug   # Compositor + native build
./gradlew :proot:assembleDebug        # Proot module
./gradlew :virgl:assembleDebug        # VirGL module
./gradlew :shims:assembleDebug        # Shims + native build
./gradlew :ui:assembleDebug           # UI components
./gradlew :testapp:assembleDebug      # Test application APK
```

### Native-Only Builds

```bash
# Compositor (Meson)
cd compositor/native
meson setup builddir --cross-file android-arm64.ini
ninja -C builddir

# Host native tests (no cross-compilation)
cd compositor/native
meson setup builddir-host
meson test -C builddir-host

# Shims (drm-shim)
cd shims/native/drm-shim
meson setup builddir --cross-file aarch64-linux-gnu.ini
ninja -C builddir
```

### Skip Flags

For faster iteration when not changing native code:

```bash
./gradlew assembleDebug \
    -PskipCompositor \
    -PskipProot \
    -PskipVirgl \
    -PskipShims
```

### Docker Shim Builds

```bash
# Build the Docker image (first time)
docker build -t wldroid-builder docker/

# Build all shims in Docker
docker run -v $(pwd):/workspace wldroid-builder

# Build specific shims with Docker via Gradle
./gradlew :shims:buildNative -PdrmShimDocker -PgbmShimDocker
```

## Running Tests

### Kotlin Unit Tests

```bash
# All modules
./gradlew test

# Specific module
./gradlew :compositor:test
./gradlew :proot:test
./gradlew :virgl:test
./gradlew :shims:test

# With verbose output
./gradlew test --info
```

### Native Tests (Host)

```bash
# Compositor native tests (5 test files)
cd compositor/native
meson setup builddir-host
meson test -C builddir-host -v

# GBM shim tests (64 tests)
cd shims/native/gbm-shim
meson setup builddir-host
meson test -C builddir-host -v

# DRM shim tests
cd shims/native/drm-shim
meson setup builddir-host
meson test -C builddir-host -v
```

### Instrumented Tests (Device/Emulator)

```bash
# All instrumented tests
./gradlew connectedAndroidTest

# Specific module
./gradlew :compositor:connectedAndroidTest
```

### Using the Test App

The `:testapp` module provides interactive testing screens:

1. **Compositor Test** — Renders test pattern, verifies rendering pipeline
2. **Environment Manager** — Create/delete proot environments
3. **GPU Diagnostics** — View GPU capabilities, test mode switching
4. **Shim Validator** — Extract and verify shim loading
5. **Native Tests** — Run native test suite from UI
6. **Settings** — Configure GPU mode, XWayland, etc.

```bash
./gradlew :testapp:installDebug
adb shell am start -n nu.shell.wldroid.testapp/.TestAppActivity
```

## Common Development Tasks

### Adding a New GPU Mode

1. Add enum value to `virgl/src/main/java/.../GpuMode.kt`
2. Update `GpuCapabilityDetector.detectBestGpuMode()` if auto-selectable
3. Update `ShimConfig.forGpuMode()` with shim requirements
4. Add VirGL server flags in `VirglServerManager.start()` if needed
5. Update `docs/gpu-rendering.md` with pipeline diagram
6. Add tests for the new mode

### Adding a New Distro Template

1. Add enum value to `DistroTemplate` in `ProotConfig.kt`
2. Set `downloadUrl`, `sha256`, `version`, and `displayName`
3. If different libdrm soversion, update shim build configuration
4. Test rootfs download, extraction, and proot execution
5. Update documentation

### Updating Native Dependencies

#### Upstream submodule (no patches):

```bash
cd external/pixman
git fetch origin
git checkout pixman-0.47.0
cd ../..
git add external/pixman
git commit -m "external: update pixman to 0.47.0"

# Verify build still works
./gradlew :compositor:assembleDebug
```

#### Forked dependency (with patches):

1. In the fork repo, create new branch from upstream release:
   ```bash
   cd external/wlroots
   git fetch upstream
   git checkout -b android-0.20.0 upstream/0.20.0
   git cherry-pick <android-patch-commits>
   # Resolve conflicts, test, push to fork
   ```

2. Update submodule:
   ```bash
   cd external/wlroots
   git checkout android-0.20.0
   cd ../..
   git add external/wlroots
   git commit -m "external: update wlroots fork to android-0.20.0"
   ```

3. Full rebuild and test:
   ```bash
   ./gradlew clean assembleDebug
   cd compositor/native && meson test -C builddir-host
   ```

### Adding a New Shim

1. Create `shims/native/my-shim/` with `meson.build` and `src/`
2. Add build task in `shims/build.gradle.kts`
3. Add asset output to `shims/src/main/assets/my-shim/`
4. Update `ShimExtractor.SHIM_ASSETS` and `ShimSet`
5. Update `ShimConfig` with enable flag
6. Write tests
7. Update `docs/shims.md`

## Code Style

### Kotlin

- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use trailing commas in multi-line declarations
- Prefer `StateFlow` over `LiveData` for observable state
- Use `data class` for value types, `enum class` for fixed sets
- Use `suspend fun` for async operations, `Flow` for streams

### C (Native)

- GNU C11 standard (`-std=gnu11`)
- wlroots naming conventions for compositor code
- Prefix all public symbols with `wldroid_` or module-specific prefix
- Use `snake_case` for functions and variables
- Header guards: `#ifndef WLDROID_<MODULE>_<FILE>_H`

### Documentation

- All public API classes and methods should have KDoc comments
- Complex algorithms should have inline comments
- Architecture decisions should be documented in `docs/`
- README examples should be tested and compilable

## Troubleshooting

### "meson not found" during build

Install Meson: `pip3 install meson` or `brew install meson`.

### Submodules are empty

```bash
git submodule update --init --recursive
```

### NDK not found

Set `ANDROID_NDK_HOME` or install via Android Studio SDK Manager. Verify the version matches `gradle.properties`.

### Native build fails with missing headers

Ensure submodules are initialized. The compositor depends on headers from `external/wlroots`, `external/wayland`, etc.

### Shim cross-compilation fails

Install `aarch64-linux-gnu-gcc` or use Docker builds:
```bash
./gradlew :shims:buildNative -PdrmShimDocker
```

### Test app crashes on launch

Check logcat for native crashes:
```bash
adb logcat -s wldroid:* AndroidRuntime:*
```

Common causes:
- Missing native library (build the compositor first)
- Incompatible GPU mode for device
- Missing permissions (INTERNET for rootfs download)
