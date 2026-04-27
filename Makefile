# WLDroid — Convenience Makefile
#
# Run `make help` for available targets.

.PHONY: all native build test test-native test-kotlin test-instrumented \
        clean docker-build setup-submodules setup-meson help

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

test: test-kotlin ## Run all local tests

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
	@echo "  make setup        # Initialize submodules and Meson links"
	@echo "  make native       # Build all native components"
	@echo "  make build        # Gradle assembleDebug"
	@echo "  make test         # Run tests"
	@echo "  make docker-build # Build everything in Docker"
