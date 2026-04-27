# WLDroid — Wayland Compositor Library for Android

## Project Structure
Multi-module Android library extracted from coder-mobile-android.
- `:compositor` — Core wlroots compositor + JNI bridge (C/Meson native build)
- `:proot` — Proot environment management (Kotlin)
- `:virgl` — VirGL server management + GPU detection (Kotlin + native)
- `:shims` — DRM/GBM/EGL/netstub shim libraries (C cross-compilation)
- `:ui` — Jetpack Compose UI components
- `:testapp` — Demo/test application

## Namespace
`nu.shell.wldroid.*`

## Native dependencies
Git submodules in `external/` — 3 forks (wlroots, virglrenderer, proot) + 14 upstream pinned.
Build system: Meson + Ninja cross-compiled via Android NDK.

## Key patterns
- Version catalog: `gradle/libs.versions.toml`
- Hilt for DI (optional for library consumers)
- Jetpack Compose for UI components
- StateFlow for observable state
- JNI via RegisterNatives (configurable class path)
