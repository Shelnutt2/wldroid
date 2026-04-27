# Build System Architecture

WLDroid uses a hybrid build system: **Gradle** orchestrates Android module compilation and packaging, while **Meson + Ninja** handle native C code cross-compilation via the Android NDK. Shim libraries targeting Linux glibc aarch64 can optionally be built in Docker for reproducibility.

## Overview

```
┌──────────────────────────────────────────────────────────────┐
│                    Gradle (root project)                      │
│  settings.gradle.kts includes 6 modules                      │
├──────────┬──────────┬──────────┬──────────┬────────┬─────────┤
│:compositor│  :proot  │  :virgl  │  :shims  │  :ui   │:testapp │
│          │          │          │          │        │         │
│ buildNat.│ buildNat.│ buildNat.│ buildNat.│ (pure  │ (app    │
│ (Meson)  │ (script) │ (script) │ (Meson/  │ Kotlin)│  APK)   │
│          │          │          │  Docker) │        │         │
└──────────┴──────────┴──────────┴──────────┴────────┴─────────┘
                        │
          ┌─────────────┴─────────────┐
          │   external/ (17 submodules)│
          │   Android NDK r28          │
          └────────────────────────────┘
```

## Two Build Target Platforms

WLDroid has two distinct cross-compilation targets:

### 1. Android NDK (ARM64)

Used for: `:compositor`, `:proot`, `:virgl`

These modules produce `.so` libraries that run inside the Android app process. They are cross-compiled using Meson with NDK toolchains and placed in `jniLibs/arm64-v8a/`.

### 2. Linux glibc aarch64

Used for: `:shims` (drm-shim, gbm-shim, egl-override, netstub, drm-wrapper)

Shim libraries run **inside the proot Linux environment**, not in the Android process. They must link against glibc (not bionic) and target standard Linux aarch64. These are cross-compiled using `aarch64-linux-gnu-gcc` (either locally or in Docker) and bundled as assets.

## Gradle Module Configuration

### Version Catalog

All dependency versions are centralized in `gradle/libs.versions.toml`:

```toml
[versions]
agp = "8.9.1"
kotlin = "2.1.10"
ndk = "r28"
compileSdk = "35"
minSdk = "29"
targetSdk = "35"
```

### Native Build Tasks

Each module with native code registers a `buildNative` Gradle task:

```kotlin
// compositor/build.gradle.kts
tasks.register<Exec>("buildNative") {
    commandLine("bash", "native/scripts/build.sh")
    environment("ANDROID_NDK_HOME", ndkDir)
    environment("SUBMODULES_DIR", rootProject.file("external").absolutePath)
}

tasks.named("preBuild") {
    dependsOn("buildNative")
}
```

### Skip Flags

Each native build can be skipped for faster CI iteration:

| Flag | Skips |
|------|-------|
| `-PskipCompositor` | Compositor native build (Meson/wlroots) |
| `-PskipProot` | Proot binary cross-compilation |
| `-PskipVirgl` | VirGL server cross-compilation |
| `-PskipShims` | All shim library builds |

Example:
```bash
# Skip all native builds (Kotlin-only iteration)
./gradlew assembleDebug -PskipCompositor -PskipProot -PskipVirgl -PskipShims
```

## Compositor Native Build (Meson)

The compositor is the most complex native build, using Meson to compile wlroots and all its dependencies from submodules.

### Meson Project Structure

```
compositor/native/
├── meson.build          # Top-level: project config, subprojects, feature flags
├── src/
│   ├── meson.build      # Compositor shared library target
│   └── *.c / *.h        # Source files
├── tests/
│   ├── meson.build      # Host-only test targets
│   └── test_*.c         # Native unit tests
└── scripts/
    └── build.sh         # Cross-compile wrapper
```

### Meson Configuration

The top-level `meson.build` configures wlroots for Android:

```meson
project('wldroid-compositor', 'c', default_options: ['c_std=gnu11'], version: '0.1.0')

# wlroots configuration — no standard backends, custom Android backend
# backends=none, renderers=gles2, allocators=none, session=disabled, xwayland=enabled
```

Key configuration choices:
- **Backends:** `none` — WLDroid provides a custom Android backend (`android_backend.c`) instead of DRM/libinput/X11
- **Renderers:** `gles2` — Android provides EGL/GLES natively
- **Allocators:** `none` — AHardwareBuffer allocator (`ahb_allocator.c`) replaces GBM/DRM allocators
- **Session:** `disabled` — no VT/logind session management on Android
- **XWayland:** `enabled` — X11 app support via XWayland

### Submodule Integration

Meson subprojects are sourced from `external/` via symlinks or `--subproject-dir`:

```
external/
├── wlroots/            → compositor/subprojects/wlroots
├── wayland/            → compositor/subprojects/wayland
├── wayland-protocols/  → compositor/subprojects/wayland-protocols
├── pixman/             → compositor/subprojects/pixman
├── libxkbcommon/       → compositor/subprojects/libxkbcommon
├── libdrm/             → compositor/subprojects/libdrm
├── libffi/             → compositor/subprojects/libffi
├── expat/              → compositor/subprojects/expat
├── libxcb/             → compositor/subprojects/libxcb (XWayland)
├── xcb-proto/          → compositor/subprojects/xcb-proto
├── libxau/             → compositor/subprojects/libxau
├── xorgproto/          → compositor/subprojects/xorgproto
├── xcb-util-wm/        → compositor/subprojects/xcb-util-wm
└── libepoxy/           → compositor/subprojects/libepoxy
```

### Build Output

The Meson build produces a single shared library:

```
compositor/src/main/jniLibs/arm64-v8a/libwldroid-compositor.so
```

This contains the wlroots compositor, all Wayland protocol implementations, the Android backend, the AHB allocator, and the JNI bridge — statically linked against wlroots and dependencies.

## Shim Native Builds

### Build Targets

| Shim | Build System | Output | Target |
|------|-------------|--------|--------|
| drm-shim | Meson | `libdrm-shim.so` | Linux glibc aarch64 |
| gbm-shim | Meson | `libgbm.so.1` | Linux glibc aarch64 |
| egl-override | Script | `libegl_override.so` | Linux glibc aarch64 |
| netstub | Script | `libnetstub.so` | Linux glibc aarch64 |
| drm-wrapper | Script | `libdrm-wrapper.so` | Linux glibc aarch64 |

### Docker Cross-Compilation

For reproducible shim builds, a Docker image provides the complete glibc aarch64 toolchain:

```dockerfile
FROM ubuntu:22.04
RUN apt-get install -y build-essential cmake ninja-build \
    python3 gcc-aarch64-linux-gnu g++-aarch64-linux-gnu
```

Build all shims in Docker:

```bash
docker build -t wldroid-builder docker/
docker run -v $(pwd):/workspace wldroid-builder ./scripts/build-all-shims.sh
```

Gradle tasks detect Docker availability and fall back to local NDK cross-compilation:

```bash
# Force Docker builds
./gradlew :shims:buildNative -PdrmShimDocker -PgbmShimDocker -PnetstubDocker
```

### Shim Output Location

Built shim libraries are placed in module assets for runtime extraction:

```
shims/src/main/assets/
├── drm-shim/libdrm-shim.so
├── drm-wrapper/libdrm-wrapper.so
├── gbm-shim/libgbm.so.1
├── egl-override/libegl_override.so
└── netstub/libnetstub.so
```

At runtime, `ShimExtractor` copies these from APK assets into the proot environment.

## Git Submodule Management

### Forked Dependencies (3)

These carry Android-specific patches and are maintained under the `Shelnutt2` GitHub org:

| Submodule | Fork Branch | Upstream | Patches |
|-----------|------------|----------|---------|
| `external/wlroots` | `android-0.19.2` | freedesktop wlroots 0.19.2 | 8 patches: EGL_ANDROID_image_native_buffer, DMA-BUF, memfd_create bionic compat, librt optional, SHM |
| `external/virglrenderer` | `android-v1.1.0` | freedesktop virglrenderer 1.1.0 | 10+ patches: EGL display init, GBM DRM guards, Venus memfd_create, AHB interop |
| `external/proot` | `android` | termux/proot | link2symlink, bind mount fixes |

### Fork Maintenance Strategy

1. Fork from upstream release tag → create `android-<version>` branch
2. Apply Android patches as individual commits with clear messages
3. Pin submodule to specific commit on the fork branch
4. On upstream release: rebase `android-<newversion>`, re-apply patches
5. Each patch's purpose is documented in the fork's README

### Upstream Dependencies (14)

Pinned to release tags — no local modifications:

| Submodule | Version |
|-----------|---------|
| `external/wayland` | 1.24.0 |
| `external/wayland-protocols` | 1.47 |
| `external/libdrm` | libdrm-2.4.131 |
| `external/pixman` | pixman-0.46.4 |
| `external/libxkbcommon` | xkbcommon-1.11.0 |
| `external/libffi` | v3.5.2 |
| `external/expat` | R_2_7_4 |
| `external/xcb-proto` | Latest stable |
| `external/libxcb` | Latest stable |
| `external/libxau` | Latest stable |
| `external/xorgproto` | Latest stable |
| `external/xcb-util-wm` | Latest stable |
| `external/libepoxy` | Latest stable |
| `external/talloc` | Latest stable |

### Updating Submodules

```bash
# Update a single upstream submodule to a new version
cd external/pixman
git fetch origin
git checkout pixman-0.47.0
cd ../..
git add external/pixman
git commit -m "external: update pixman to 0.47.0"

# Update a fork after rebasing patches
cd external/wlroots
git fetch origin
git checkout android-0.20.0  # new branch with rebased patches
cd ../..
git add external/wlroots
git commit -m "external: update wlroots fork to android-0.20.0"
```

## Build All

```bash
# Prerequisites
# - Android SDK + NDK r28
# - Meson >= 1.0, Ninja, Python 3, Java 17
# - For shims: aarch64-linux-gnu-gcc or Docker

# Initialize submodules
git submodule update --init --recursive

# Full build
./gradlew assembleDebug

# Individual module builds
./gradlew :compositor:assembleDebug
./gradlew :proot:assembleDebug
./gradlew :virgl:assembleDebug
./gradlew :shims:assembleDebug
./gradlew :testapp:assembleDebug
```

## Clean

```bash
# Clean Gradle outputs
./gradlew clean

# Clean native build directories
rm -rf compositor/native/builddir
rm -rf shims/native/*/builddir
```
