# Testing Strategy

WLDroid uses a layered testing approach spanning native C code, Kotlin unit tests, integration tests, UI tests, and end-to-end tests. Each layer targets different components and failure modes.

## Test Layers Overview

```
┌─────────────────────────────────────────────────────────────┐
│ Layer 6: E2E Tests (device only)                            │
│ Full compositor + proot + GPU pipeline on real hardware      │
├─────────────────────────────────────────────────────────────┤
│ Layer 5: UI Tests (device/emulator)                         │
│ Compose component rendering and interaction                  │
├─────────────────────────────────────────────────────────────┤
│ Layer 4: Integration Tests (device/emulator)                │
│ Cross-module interactions, JNI bridge, file I/O              │
├─────────────────────────────────────────────────────────────┤
│ Layer 3: Kotlin Unit Tests (JVM)                            │
│ Business logic, state machines, data classes                 │
├─────────────────────────────────────────────────────────────┤
│ Layer 2: GBM Shim Tests (host)                              │
│ 64 native tests for gbm-shim buffer management              │
├─────────────────────────────────────────────────────────────┤
│ Layer 1: Native Unit Tests (host x86_64)                    │
│ Compositor C code — format tables, keycode maps, ring buffer│
└─────────────────────────────────────────────────────────────┘
```

## Layer 1: Native Unit Tests

**Framework:** Meson test (custom C test harness)
**Runs on:** Host (x86_64) — no Android device required
**Location:** `compositor/native/tests/`

### Test Files

| Test File | Tests | Description |
|-----------|-------|-------------|
| `test_drm_format.c` | DRM format table validation | Verifies format → name mappings |
| `test_keycode_map.c` | Android → XKB keycode map | Ensures all mapped keycodes are valid |
| `test_toplevel_tracking.c` | Toplevel surface tracking | Scene graph surface management |
| `test_client.c` | Test pattern client | Validates built-in test client rendering |
| `test_ring_buffer.c` | Ring buffer implementation | Thread-safe ring buffer for event queuing |

### Running

```bash
cd compositor/native

# Setup (first time)
meson setup builddir-host

# Run all native tests
meson test -C builddir-host

# Run with verbose output
meson test -C builddir-host -v

# Run a specific test
meson test -C builddir-host test_drm_format
```

### Expected Output

```
1/5 test_drm_format      OK     0.01s
2/5 test_keycode_map      OK     0.01s
3/5 test_toplevel_tracking OK    0.01s
4/5 test_client           OK     0.02s
5/5 test_ring_buffer      OK     0.01s

OK: 5
FAIL: 0
```

## Layer 2: GBM Shim Tests

**Framework:** Custom C test runner
**Runs on:** Host (x86_64)
**Location:** `shims/native/gbm-shim/tests/`
**Count:** 64 tests across 4 test files

### Test Coverage

- Buffer allocation (`gbm_bo_create`) with various formats and sizes
- Format mapping (GBM ↔ AHardwareBuffer format conversion)
- File descriptor export (`gbm_bo_get_fd`)
- Buffer destruction and reference counting
- Edge cases (zero-size, invalid formats, double-free)
- Modifier handling (`gbm_bo_create_with_modifiers`)

### Running

```bash
cd shims/native/gbm-shim

# Setup
meson setup builddir-host

# Run all 64 tests
meson test -C builddir-host -v
```

## Layer 2b: DRM Shim Tests

**Location:** `shims/native/drm-shim/tests/`

### Test Files

| Test File | Description |
|-----------|-------------|
| `test_drm_devices.c` | DRM device enumeration (`drmGetDevices2`) |
| `test_drm_prime.c` | PRIME fd ↔ handle conversion |
| `test_drm_version.c` | DRM version reporting (`drmGetVersion`) |

### Running

```bash
cd shims/native/drm-shim
meson setup builddir-host
meson test -C builddir-host -v
```

## Layer 3: Kotlin Unit Tests

**Framework:** JUnit 4 + Google Truth assertions
**Runs on:** JVM (no Android device required)
**Location:** `*/src/test/java/`

### Test Files by Module

#### `:compositor`
| Test File | Description |
|-----------|-------------|
| `CompositorConfigTest.kt` | Config defaults, validation |
| `CompositorSessionTest.kt` | State machine transitions |
| `CompositorStateTest.kt` | Enum completeness |

#### `:proot`
| Test File | Description |
|-----------|-------------|
| `EnvironmentRegistryTest.kt` | CRUD operations, state management |
| `ProotConfigTest.kt` | Config defaults, validation |
| `ProotExecutorTest.kt` | Command building, proot flags |
| `RootfsManagerTest.kt` | Environment lifecycle |
| `RootfsStoreTest.kt` | DataStore persistence |

#### `:virgl`
| Test File | Description |
|-----------|-------------|
| `GpuModeTest.kt` | Enum properties, `fromString()` |
| `GpuModeStoreTest.kt` | Preference persistence |
| `GpuCapabilityDetectorTest.kt` | Detection logic |
| `VirglConfigTest.kt` | Config defaults |
| `VirglServerManagerTest.kt` | Server command building |
| `VirglSessionTest.kt` | Session state machine |

#### `:shims`
| Test File | Description |
|-----------|-------------|
| `ShimConfigTest.kt` | Per-mode shim configuration |
| `ShimExtractorTest.kt` | Asset extraction, LD_PRELOAD building |

### Running

```bash
# All Kotlin unit tests
./gradlew test

# Single module
./gradlew :virgl:test

# Single test class
./gradlew :virgl:test --tests "nu.shell.wldroid.virgl.GpuModeTest"

# With detailed output
./gradlew test --info

# Generate HTML report
./gradlew test
# Report at: <module>/build/reports/tests/testDebugUnitTest/index.html
```

## Layer 4: Integration Tests

**Framework:** AndroidX Test + JUnit
**Runs on:** Device or emulator
**Location:** `*/src/androidTest/java/`

Integration tests verify cross-module interactions that require an Android context:

- JNI bridge functionality (loading native library, calling JNI methods)
- DataStore read/write on real Android storage
- Asset extraction (shim .so files from APK)
- Network operations (rootfs download with real HTTP)
- File system operations (rootfs extraction, permissions)

### Running

```bash
# All instrumented tests
./gradlew connectedAndroidTest

# Specific module
./gradlew :compositor:connectedAndroidTest
./gradlew :proot:connectedAndroidTest
```

### Emulator Strategy

For CI, use x86_64 emulators for Kotlin integration tests. Note that ARM64-only native code (shims, proot, virgl) cannot run on x86_64 emulators — those require real devices.

```bash
# Start emulator for integration tests
emulator -avd Pixel_6_API_33 -gpu swiftshader_indirect -no-audio -no-window
```

## Layer 5: UI Tests

**Framework:** Compose Testing
**Runs on:** Device or emulator
**Location:** `ui/src/androidTest/java/`

UI tests verify Compose component behavior:

- `CompositorSurface` renders and handles lifecycle
- `SetupOverlay` shows progress indicators
- `GpuModeSelector` displays and selects modes
- `EnvironmentPicker` lists and selects environments
- Navigation between screens in testapp

### Running

```bash
./gradlew :ui:connectedAndroidTest
```

## Layer 6: End-to-End Tests

**Framework:** AndroidX Test + testapp
**Runs on:** Real devices only (ARM64)
**Location:** `testapp/src/androidTest/java/`

E2E tests verify the complete pipeline:

1. **Compositor E2E** — Start compositor → verify rendering → connect test client → verify display
2. **Proot Integration** — Create environment → run command in proot → verify output
3. **GPU Pipeline** — Detect GPU → start VirGL → extract shims → verify rendering

### Running

```bash
# Requires ARM64 device
./gradlew :testapp:connectedAndroidTest
```

## CI Pipeline

### PR / Push Checks

| Step | Duration | What It Verifies |
|------|----------|-----------------|
| Lint | ~2 min | Code quality, Kotlin style |
| Build | ~5 min | Compilation (Kotlin + native) |
| Native tests | ~1 min | Host C tests (compositor + shims) |
| Kotlin unit tests | ~2 min | JVM-based business logic |
| Compose UI tests | ~3 min | Component rendering (emulator) |
| Cross-compile verify | ~3 min | ARM64 native builds succeed |
| `assembleDebug` | ~5 min | Full APK assembly |

### Nightly

| Step | Duration | What It Verifies |
|------|----------|-----------------|
| Firebase Test Lab | ~15 min | Full E2E on real ARM64 devices |
| All GPU modes | ~20 min | Each GPU mode on compatible hardware |
| Performance regression | ~10 min | Frame timing, memory usage baselines |

### CI Configuration

```yaml
# Example GitHub Actions workflow
name: CI
on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          submodules: recursive

      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Setup Android SDK
        uses: android-actions/setup-android@v3

      - name: Install Meson + Ninja
        run: pip3 install meson ninja

      - name: Native tests (host)
        run: |
          cd compositor/native
          meson setup builddir-host
          meson test -C builddir-host

      - name: Kotlin unit tests
        run: ./gradlew test

      - name: Build
        run: ./gradlew assembleDebug -PskipShims

  instrumented:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          submodules: recursive

      - uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 33
          arch: x86_64
          profile: pixel_6
          script: ./gradlew connectedAndroidTest -PskipCompositor -PskipShims
```

### Firebase Test Lab

For full E2E testing on real devices:

```bash
# Build test APK
./gradlew :testapp:assembleDebug :testapp:assembleDebugAndroidTest

# Run on Firebase Test Lab
gcloud firebase test android run \
  --type instrumentation \
  --app testapp/build/outputs/apk/debug/testapp-debug.apk \
  --test testapp/build/outputs/apk/androidTest/debug/testapp-debug-androidTest.apk \
  --device model=oriole,version=33 \
  --timeout 15m
```

## Writing New Tests

### Native Test (C)

```c
// compositor/native/tests/test_my_feature.c
#include "test_harness.h"
#include "../src/my_feature.h"

static void test_basic_functionality(void) {
    int result = my_function(42);
    ASSERT_EQ(result, 84);
}

static void test_edge_case(void) {
    int result = my_function(0);
    ASSERT_EQ(result, 0);
}

int main(void) {
    RUN_TEST(test_basic_functionality);
    RUN_TEST(test_edge_case);
    return test_summary();
}
```

Add to `compositor/native/tests/meson.build`:
```meson
test('test_my_feature', executable('test_my_feature',
    'test_my_feature.c',
    '../src/my_feature.c',
    include_directories: inc,
))
```

### Kotlin Unit Test

```kotlin
// virgl/src/test/java/nu/shell/wldroid/virgl/MyFeatureTest.kt
package nu.shell.wldroid.virgl

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MyFeatureTest {
    @Test
    fun `detectBestGpuMode returns SOFTWARE when no GPU`() {
        val detector = GpuCapabilityDetector(mockContext)
        val mode = detector.detectBestGpuMode(virglAvailable = false)
        assertThat(mode).isEqualTo(GpuMode.SOFTWARE)
    }
}
```

### Compose UI Test

```kotlin
// ui/src/androidTest/java/nu/shell/wldroid/ui/GpuModeSelectorTest.kt
package nu.shell.wldroid.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

class GpuModeSelectorTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun displaysCurrentMode() {
        composeRule.setContent {
            GpuModeSelector(
                currentMode = GpuMode.VIRGL_GLES,
                detectedMode = GpuMode.VIRGL_GLES,
                onModeSelected = {},
                gpuSummary = "Test GPU",
            )
        }

        composeRule.onNodeWithText("VirGL GLES").assertIsDisplayed()
    }
}
```

## Test Data & Fixtures

### Mock Contexts

For Kotlin unit tests that need Android `Context`:
- Use Robolectric (`@RunWith(RobolectricTestRunner::class)`)
- Or use `ApplicationProvider.getApplicationContext()` in instrumented tests

### Test Rootfs

For proot integration tests, use a minimal rootfs (~10MB) instead of full Debian:
- Located at `testapp/src/androidTest/assets/test-rootfs.tar.xz`
- Contains `/bin/sh`, `/etc/os-release`, basic utilities
- Created via `debootstrap --variant=minbase`

### Fake GPU Detection

For unit-testing GPU mode selection without real hardware:
```kotlin
val mockDetector = object : GpuCapabilityDetector(mockContext) {
    override fun isKgslAccessible() = false
    override fun hasVulkanSupport() = true
}
assertThat(mockDetector.detectBestGpuMode()).isEqualTo(GpuMode.VIRGL_ZINK)
```
