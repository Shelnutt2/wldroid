# WLDroid — Wayland Compositor Library for Android

Standalone, modular Android library providing a full Wayland compositor stack for running Linux desktop applications on Android devices. Extracted from coder-mobile-android.

## Project Structure

```
wldroid/
├── compositor/        # Core wlroots compositor + JNI bridge
│   ├── native/        # C source (Meson + Ninja build)
│   │   ├── src/       # 12 C source files (compositor, backend, AHB, input, JNI)
│   │   └── tests/     # 5 native unit tests (host x86_64)
│   └── src/main/java/ # Kotlin API: CompositorServer, CompositorSession, CompositorConfig, CompositorState, CompositorInput
├── proot/             # Proot environment management
│   └── src/main/java/ # EnvironmentRegistry, RootfsManager, ProotExecutor, ProotConfig, RootfsDownloader, RootfsExtractor, RootfsStore, ProotDnsManager
├── virgl/             # VirGL server management + GPU detection
│   └── src/main/java/ # VirglSession, VirglServerManager, GpuCapabilityDetector, GpuMode, GpuModeStore, VirglConfig, VirglState
├── shims/             # DRM/GBM/EGL/netstub shim libraries
│   ├── native/        # 5 C shim sub-libraries (drm-shim, gbm-shim, egl-override, netstub, drm-wrapper)
│   └── src/main/java/ # ShimExtractor, ShimConfig
├── ui/                # Jetpack Compose UI components
│   └── src/main/java/ # CompositorSurface, SetupOverlay, GpuModeSelector, EnvironmentPicker, EnvironmentCreator
├── testapp/           # Demo/test application (APK)
├── external/          # 17 git submodules (3 forks + 14 upstream pinned)
├── docs/              # Architecture documentation (8 files)
└── scripts/           # Build and CI scripts
```

## Namespace
`nu.shell.wldroid.*` — sub-packages: `.compositor`, `.proot`, `.virgl`, `.shims`, `.ui`, `.testapp`

## Module Dependency Graph
```
:testapp → :ui → :compositor (independent)
                 :proot      (independent)
                 :virgl      (independent)
                 :shims      (independent)
```
The 4 library modules have no dependencies on each other.

## Native Dependencies
Git submodules in `external/`:
- **3 forks** (Shelnutt2 org, carry Android patches): wlroots (android-0.19.2), virglrenderer (android-v1.1.0), proot (android)
- **14 upstream** (pinned to release tags): wayland, wayland-protocols, libdrm, pixman, libxkbcommon, libffi, expat, xcb-proto, libxcb, libxau, xorgproto, xcb-util-wm, libepoxy, talloc

## Build System
- **Gradle** orchestrates all modules; version catalog at `gradle/libs.versions.toml`
- **Meson + Ninja** for native C cross-compilation (compositor, shims)
- **Two cross-compile targets**: Android NDK ARM64 (compositor/proot/virgl JNI libs) and Linux glibc aarch64 (shims run inside proot)
- **Docker** optional for reproducible shim builds
- **Skip flags**: `-PskipCompositor`, `-PskipProot`, `-PskipVirgl`, `-PskipShims`

## Key Patterns
- Version catalog: `gradle/libs.versions.toml` (AGP 8.9.1, Kotlin 2.1.10, NDK r28, compileSdk 35, minSdk 29)
- Hilt for DI (optional for library consumers)
- Jetpack Compose for UI components
- StateFlow for all observable state (compositor lifecycle, GPU mode, environment list)
- JNI via RegisterNatives in JNI_OnLoad (configurable class path)
- DataStore Preferences for persistence (RootfsStore, GpuModeStore)
- Coroutines/Flow for async operations (rootfs download, proot execution)

## GPU Modes
5 modes, auto-detected by `GpuCapabilityDetector`:
1. **TURNIP_DIRECT** — Qualcomm Adreno direct GPU, highest performance
2. **VIRGL_ZINK** — Vulkan-over-VirGL translation
3. **VIRGL_GLES** — OpenGL ES via VirGL server
4. **VENUS** — Experimental Vulkan passthrough (never auto-selected)
5. **SOFTWARE** — CPU-only fallback (universal)

## Compositor Architecture
- Custom wlroots backend (`android_backend.c`) — no DRM/libinput, uses ANativeWindow
- AHardwareBuffer allocator for zero-copy GPU buffer sharing
- AHB registry (256-entry, mutex-protected) for compositor↔VirGL buffer exchange
- Unix socket wire protocol for AHB handle transfer (SCM_RIGHTS)
- 14 Wayland protocols supported + XWayland

## Shim Stack (runs inside proot Linux environment)
- **drm-shim** (~2900 LOC): Fake virtio_gpu DRM device, vtest protocol bridge
- **gbm-shim**: GBM API → AHardwareBuffer, zero-copy buffer allocation
- **egl-override** (~2000 LOC): Mode-aware EGL interception
- **netstub** (~500 LOC): getifaddrs replacement for Android SELinux
- **drm-wrapper**: DT_NEEDED linker, parameterized by LIBDRM_SOVERSION

## Testing
- **Native tests**: Meson test (compositor: 5 tests, gbm-shim: 64 tests, drm-shim: 3 test files)
- **Kotlin unit tests**: JUnit + Truth (compositor: 3, proot: 5, virgl: 6, shims: 2)
- **Instrumented tests**: AndroidX Test on device/emulator
- **E2E tests**: testapp on real ARM64 devices

## Documentation
Full docs in `docs/`: build-system.md, compositor-architecture.md, gpu-rendering.md, shims.md, integration-guide.md, api-reference.md, development.md, testing.md

## Important Conventions
- Do not modify source code files outside the documented module boundaries
- Native code uses GNU C11 (`-std=gnu11`) with wlroots naming conventions
- All public Kotlin API uses StateFlow, not LiveData
- Compositor thread safety: UI→compositor via wl_event_loop_add_idle(), AHB registry via mutex
- Shims target glibc aarch64, NOT Android bionic
