# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - Unreleased

### Added

- **`:compositor` module** — Core Wayland compositor with wlroots 0.19.2 Android backend
  - `CompositorServer` / `CompositorSession` for lifecycle management
  - `CompositorConfig` for structured configuration
  - `CompositorInput` for unified touch, keyboard, and pointer input routing
  - `CompositorState` sealed class with `StateFlow`-based observation
  - Custom `android_backend` replacing DRM/KMS with `ANativeWindow`
  - `AHardwareBuffer` allocator for zero-copy GPU buffer sharing
  - AHB registry (256-entry, mutex-protected) for compositor↔VirGL exchange
  - Unix socket wire protocol with `SCM_RIGHTS` for AHB handle transfer
  - JNI via `RegisterNatives` in `JNI_OnLoad` (configurable class path)
  - 14 Wayland protocols supported + XWayland
  - 5 native unit tests (host x86_64)
  - 3 Kotlin unit tests

- **`:proot` module** — Proot environment management with Debian Trixie/Bookworm support
  - `EnvironmentRegistry` for named environment lifecycle (create, list, delete)
  - `RootfsManager` for rootfs operations
  - `RootfsDownloader` with resumable downloads and progress reporting
  - `RootfsExtractor` with tar extraction and progress reporting
  - `ProotExecutor` for running commands in proot environments
  - `ProotConfig` for structured execution configuration
  - `ProotDnsManager` for automatic DNS configuration
  - `RootfsStore` for DataStore-based metadata persistence
  - 5 Kotlin unit tests

- **`:virgl` module** — VirGL GPU acceleration with 5 GPU modes
  - `VirglSession` for high-level VirGL lifecycle management
  - `VirglServerManager` for server process control
  - `GpuCapabilityDetector` for automatic GPU mode selection
  - `GpuModeStore` for DataStore-based mode persistence
  - `VirglConfig` / `VirglState` for configuration and state observation
  - GPU modes: TURNIP_DIRECT, VIRGL_ZINK, VIRGL_GLES, VENUS, SOFTWARE
  - 6 Kotlin unit tests

- **`:shims` module** — DRM/GBM/EGL/netstub shim libraries for proot environment
  - `drm-shim` (~2900 LOC): Fake virtio_gpu DRM device with vtest protocol bridge
  - `gbm-shim`: GBM API → AHardwareBuffer zero-copy allocation
  - `egl-override` (~2000 LOC): Mode-aware EGL interception
  - `netstub` (~500 LOC): `getifaddrs` replacement for Android SELinux
  - `drm-wrapper`: `DT_NEEDED` linker parameterized by `LIBDRM_SOVERSION`
  - `ShimExtractor` / `ShimConfig` for extracting shim `.so` files
  - 64 gbm-shim unit tests, 3 drm-shim test files
  - 2 Kotlin unit tests

- **`:ui` module** — Jetpack Compose UI components
  - `CompositorSurface` — full compositor display with automatic lifecycle and input
  - `SetupOverlay` — download/extraction progress display
  - `GpuModeSelector` — GPU mode picker with all 5 modes
  - `EnvironmentPicker` — environment list with create/delete actions
  - `EnvironmentCreator` — guided environment creation wizard
  - `KeyboardToggleFab` — floating action button for soft keyboard toggle
  - `WldroidTheme` — Material 3 theme wrapper

- **`:testapp` module** — Demo/test application for development and E2E testing

- **Documentation** (8 files in `docs/`)
  - `api-reference.md` — Complete Kotlin and native API documentation
  - `build-system.md` — Gradle + Meson + Ninja build architecture
  - `compositor-architecture.md` — wlroots backend, AHB, Wayland protocols
  - `gpu-rendering.md` — 5 GPU modes, detection, and rendering pipeline
  - `shims.md` — DRM/GBM/EGL/netstub shim architecture
  - `integration-guide.md` — Library consumer guide
  - `development.md` — Development environment setup
  - `testing.md` — Test strategy across all layers
  - `migration-guide.md` — coder-mobile-android migration guide

- **Build system**
  - Gradle with version catalog (`gradle/libs.versions.toml`)
  - Meson + Ninja for native C cross-compilation
  - Two cross-compile targets: Android NDK ARM64 and Linux glibc aarch64
  - Docker-based reproducible shim builds
  - Skip flags: `-PskipCompositor`, `-PskipProot`, `-PskipVirgl`, `-PskipShims`
  - CI/CD pipeline configuration

- **External dependencies** — 17 git submodules in `external/`
  - 3 forks (wlroots android-0.19.2, virglrenderer android-v1.1.0, proot android)
  - 14 upstream pinned (wayland, wayland-protocols, libdrm, pixman, libxkbcommon, libffi, expat, xcb-proto, libxcb, libxau, xorgproto, xcb-util-wm, libepoxy, talloc)
