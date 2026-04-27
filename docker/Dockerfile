# WLDroid — Full build environment for all native components.
#
# Includes: Android NDK r28, Meson, Ninja, GCC cross-compiler (aarch64-linux-gnu),
# and all tools needed to build compositor, proot, virgl, and shims.
#
# Usage:
#   docker build -t wldroid-builder docker/
#   docker run --rm -v "$(pwd):/project" wldroid-builder
#
# Or via docker-compose:
#   docker compose -f docker/docker-compose.yml run all-build
FROM ubuntu:22.04

ARG NDK_VERSION=r28
ARG NDK_URL=https://dl.google.com/android/repository/android-ndk-${NDK_VERSION}-linux.zip

ENV DEBIAN_FRONTEND=noninteractive

# Install build dependencies for all components:
# - build-essential, cmake, ninja-build: general C/C++ build tools
# - meson, python3-pip: Meson build system (compositor, virgl)
# - gcc-aarch64-linux-gnu, g++-aarch64-linux-gnu: glibc cross-compiler (shims)
# - pkg-config: dependency resolution
# - libwayland-dev: host wayland-scanner
# - wget, unzip, git: NDK download & submodule support
RUN apt-get update -qq && \
    apt-get install -y --no-install-recommends \
        build-essential \
        cmake \
        ninja-build \
        meson \
        python3 \
        python3-pip \
        pkg-config \
        wget \
        unzip \
        git \
        gcc-aarch64-linux-gnu \
        g++-aarch64-linux-gnu \
        binutils-aarch64-linux-gnu \
        libwayland-dev \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Upgrade Meson to a recent version (Ubuntu 22.04 ships an older one)
RUN pip3 install --no-cache-dir meson==1.3.2

# Download and install Android NDK
RUN mkdir -p /opt/android && \
    wget -q -O /tmp/ndk.zip "${NDK_URL}" && \
    unzip -q /tmp/ndk.zip -d /opt/android && \
    rm /tmp/ndk.zip && \
    ln -s /opt/android/android-ndk-${NDK_VERSION} /opt/android/ndk

ENV ANDROID_NDK_HOME=/opt/android/ndk
ENV ANDROID_SDK_ROOT=/opt/android
ENV PATH="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin:${PATH}"

WORKDIR /project

# Default: build everything
CMD ["bash", "scripts/build-all-native.sh"]
