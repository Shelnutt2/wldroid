# Contributing to WLDroid

Thank you for your interest in contributing to WLDroid! This document provides guidelines and instructions for contributing.

## License

WLDroid is licensed under the **GNU Affero General Public License v3.0 (AGPL-3.0)**. By submitting a contribution, you agree that your work will be licensed under the same terms. This means:

- Any modifications or derivative works must also be released under AGPL-3.0
- If you run a modified version of WLDroid on a server, you must make the source code available to users
- You retain copyright on your contributions, but grant the project a license to use them under AGPL-3.0

If you are contributing on behalf of your employer, please ensure they are aware of and agree to these terms.

## Development Setup

### Prerequisites

| Tool | Version | Purpose |
|------|---------|---------|
| Android Studio | Latest stable | IDE with Android SDK management |
| Android SDK | compileSdk 35 | Android API level |
| Android NDK | r28 (28.0.13004108) | Native code cross-compilation |
| Java | 17 | Gradle and Kotlin compilation |
| Meson | ≥ 1.0 | Native build system |
| Ninja | Latest | Build backend for Meson |
| Python 3 | ≥ 3.8 | Meson dependency |
| Git | ≥ 2.20 | Submodule support |
| Docker | Optional | Reproducible shim builds |

### Getting Started

```bash
# Clone with submodules
git clone --recursive https://github.com/Shelnutt2/wldroid.git
cd wldroid

# Open in Android Studio or build from command line
./gradlew assembleDebug
```

See [docs/development.md](docs/development.md) for detailed setup instructions.

## Code Style

### Kotlin

- Follow the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use `StateFlow` for observable state (not `LiveData`)
- Use structured concurrency with `CoroutineScope`
- Data classes for configuration (`*Config`) and sealed classes for state (`*State`)
- Format with the project's `.editorconfig` or ktlint settings

### Native C Code

- GNU C11 (`-std=gnu11`)
- Follow wlroots naming conventions: `wlr_*` prefixes for wlroots-style APIs, `android_*` prefixes for Android backend code
- Use `snake_case` for functions and variables
- Document public functions with `/** */` comments
- Thread safety: use `wl_event_loop_add_idle()` for UI→compositor communication, `pthread_mutex_t` for shared data

### General

- Keep module boundaries strict — the 4 library modules (`:compositor`, `:proot`, `:virgl`, `:shims`) must not depend on each other
- Public API must be documented with KDoc
- All new public API surfaces must have corresponding unit tests

## Pull Request Process

### Before Submitting

1. **Create an issue first** — discuss the change before implementing, especially for new features or architectural changes
2. **Branch from `main`** — use descriptive branch names: `feature/xxx`, `fix/xxx`, `docs/xxx`
3. **Keep PRs focused** — one logical change per PR; split large changes into stacked PRs if needed
4. **Write tests** — all new code must have corresponding tests (see Testing below)
5. **Update documentation** — if you change public API, update `docs/api-reference.md` and any affected docs

### PR Checklist

- [ ] Code compiles without warnings: `./gradlew assembleDebug`
- [ ] All Kotlin unit tests pass: `./gradlew test`
- [ ] Native tests pass (if applicable): see [docs/testing.md](docs/testing.md)
- [ ] No new lint warnings: `./gradlew lint`
- [ ] Documentation updated for any public API changes
- [ ] Commit messages are clear and descriptive
- [ ] PR description explains *what* and *why*

### Review Process

1. Submit your PR against `main`
2. CI will run automated checks (build, test, lint)
3. A maintainer will review your PR
4. Address review feedback with new commits (don't force-push during review)
5. Once approved, a maintainer will merge using squash-and-merge

## Testing Requirements

WLDroid uses a layered testing approach. Your contribution should include tests at the appropriate level:

| Layer | Tool | When Required |
|-------|------|---------------|
| **Native unit tests** | Meson test (host x86_64) | Changes to C code in `compositor/native/` or `shims/native/` |
| **Kotlin unit tests** | JUnit + Truth | Any new Kotlin class or public method |
| **Integration tests** | AndroidX Test | Cross-module interactions |
| **UI tests** | Compose testing | Changes to `:ui` module composables |
| **E2E tests** | testapp on device | Significant behavioral changes |

### Running Tests

```bash
# Kotlin unit tests (all modules)
./gradlew test

# Specific module
./gradlew :compositor:test
./gradlew :proot:test
./gradlew :virgl:test
./gradlew :shims:test

# Lint checks
./gradlew lint

# Native tests — see docs/testing.md for Meson test instructions
```

See [docs/testing.md](docs/testing.md) for the full testing strategy.

## Module Architecture

```
:testapp → :ui → :compositor (independent)
                 :proot      (independent)
                 :virgl      (independent)
                 :shims      (independent)
```

The 4 library modules are **independent** — they must not import from each other. The `:ui` module depends on all four. The `:testapp` depends on `:ui`.

If your change requires coordination between modules, the integration point should be in `:ui` or in the consuming application.

## Commit Messages

Write clear, descriptive commit messages:

```
<module>: <short summary>

<optional longer description explaining what and why>
```

Examples:
```
compositor: fix AHB registry race condition on session teardown
proot: add Debian Bookworm rootfs support
virgl: improve GPU detection for Mali G78 devices
ui: add keyboard toggle FAB to CompositorSurface
docs: update API reference for VirglSession.start()
```

## Reporting Issues

- Use GitHub Issues for bug reports and feature requests
- Include device model, Android version, and GPU (SoC) for runtime issues
- Include full stack traces for crashes
- For build issues, include the full Gradle output with `--stacktrace`

## Questions?

- Open a GitHub Discussion for questions about the architecture or how to contribute
- Review the [docs/](docs/) directory for detailed technical documentation
- Check existing issues and PRs for context on ongoing work
