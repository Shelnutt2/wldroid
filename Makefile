# WLDroid — Convenience Makefile
#
# Run `make help` for available targets.

.PHONY: all native build test test-native test-kotlin test-instrumented test-all test-ui \
        clean docker-build setup-submodules setup-meson setup-emulator \
        emulator-start emulator-stop emulator-status check-env help

# Default: build native, then Gradle, then tests.
all: native build test

# ── Native builds ──

native: setup-meson ## Build all native components locally
	bash scripts/build-all-native.sh

native-compositor: setup-meson ## Build compositor native only
	bash scripts/build-all-native.sh --skip-proot --skip-virgl --skip-shims

native-proot: setup-meson ## Build proot native only
	bash scripts/build-all-native.sh --skip-compositor --skip-virgl --skip-shims

native-virgl: setup-meson ## Build virgl native only
	bash scripts/build-all-native.sh --skip-compositor --skip-proot --skip-shims

native-shims: setup-meson ## Build shims native only
	bash scripts/build-all-native.sh --skip-compositor --skip-proot --skip-virgl

native-clean: ## Clean all native build artifacts
	bash scripts/build-all-native.sh --clean

# ── Gradle builds ──

build: ## Gradle assembleDebug (skips native — use 'native' target first)
	./gradlew assembleDebug \
		-PskipCompositor=true \
		-PskipProot=true \
		-PskipVirgl=true \
		-PskipShims=true

build-full: ## Gradle assembleDebug with native builds
	./gradlew assembleDebug

build-release: ## Gradle assembleRelease with native builds
	./gradlew assembleRelease

# ── Testing ──

test: test-kotlin ## Run all local tests (native + Kotlin)

test-native: ## Run native unit tests (Meson test)
	@echo "=== Compositor native tests ==="
	cd compositor/native && meson test -C builddir 2>/dev/null || echo "  (skipped — no build dir)"
	@echo ""
	@echo "=== GBM shim native tests ==="
	cd shims/native/gbm-shim && meson test -C builddir 2>/dev/null || echo "  (skipped — no build dir)"

test-kotlin: ## Run Kotlin unit tests
	./gradlew test \
		-PskipCompositor=true \
		-PskipProot=true \
		-PskipVirgl=true \
		-PskipShims=true

test-instrumented: ## Run instrumented tests (requires device/emulator)
	./gradlew connectedAndroidTest \
		-PskipCompositor=true \
		-PskipProot=true \
		-PskipVirgl=true \
		-PskipShims=true

test-all: test-native test-kotlin test-instrumented ## Run all tests (native + Kotlin + instrumented)

test-ui: ## Run Compose UI tests only (requires device/emulator)
	./gradlew :ui:connectedAndroidTest \
		-PskipCompositor=true \
		-PskipProot=true \
		-PskipVirgl=true \
		-PskipShims=true

# ── Emulator ──

setup-emulator: ## Install system image and create AVD for testing
	bash scripts/setup-emulator.sh

emulator-start: ## Start the emulator in background (headless)
	bash scripts/run-emulator.sh

emulator-stop: ## Stop the running emulator
	@adb devices | grep -q emulator && adb -s $$(adb devices | grep emulator | head -1 | cut -f1) emu kill || echo "No running emulator found."

emulator-status: ## Check if emulator is running
	@if adb devices | grep -q emulator; then \
		echo "✅ Emulator is running:"; \
		adb devices | grep emulator; \
	else \
		echo "❌ No emulator running."; \
		echo "   Start one with: make emulator-start"; \
	fi

# ── Docker ──

docker-build: ## Build all native components using Docker
	docker compose -f docker/docker-compose.yml run --rm all-build

docker-compositor: ## Build compositor using Docker
	docker compose -f docker/docker-compose.yml run --rm compositor-build

docker-proot: ## Build proot using Docker
	docker compose -f docker/docker-compose.yml run --rm proot-build

docker-virgl: ## Build virgl using Docker
	docker compose -f docker/docker-compose.yml run --rm virgl-build

docker-shims: ## Build shims using Docker
	docker compose -f docker/docker-compose.yml run --rm shims-build

docker-shell: ## Enter interactive Docker build environment
	bash docker/build-env.sh

docker-image: ## Build the Docker builder image
	docker build -t wldroid-builder docker/

# ── Setup ──

setup-submodules: ## Init and update git submodules
	git submodule update --init --recursive

setup-meson: ## Create Meson subproject symlinks
	bash scripts/setup-meson-subprojects.sh

setup: setup-submodules setup-meson ## Full project setup (submodules + Meson links)

check-env: ## Check development environment for required tools
	bash scripts/check-dev-environment.sh

# ── Cleanup ──

clean: ## Clean all build artifacts
	./gradlew clean
	rm -rf compositor/native/builddir compositor/native/builddir-native compositor/native/builddir-cross
	rm -rf proot/build/native
	rm -rf virgl/native/build
	rm -rf shims/build/outputs/native
	rm -rf compositor/src/main/jniLibs
	rm -rf proot/src/main/jniLibs
	rm -rf virgl/src/main/jniLibs

# ── Help ──

help: ## Show available targets
	@echo "WLDroid Build System"
	@echo "===================="
	@echo ""
	@echo "Available targets:"
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'
	@echo ""
	@echo "Quick start:"
	@echo "  make check-env       # Verify development environment"
	@echo "  make setup           # Initialize submodules and Meson links"
	@echo "  make native          # Build all native components"
	@echo "  make build           # Gradle assembleDebug"
	@echo "  make test            # Run local tests"
	@echo "  make setup-emulator  # Set up Android emulator for testing"
	@echo "  make emulator-start  # Start headless emulator"
	@echo "  make docker-build    # Build everything in Docker"
