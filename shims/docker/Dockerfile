# Docker image for cross-compiling WLDroid shim libraries for Android arm64.
# Usage:
#   docker build -t wldroid-shims-builder shims/docker/
#   docker run --rm -v "$(pwd):/project" wldroid-shims-builder
FROM ubuntu:22.04

ARG NDK_VERSION=r28
ARG NDK_URL=https://dl.google.com/android/repository/android-ndk-${NDK_VERSION}-linux.zip

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update -qq && \
    apt-get install -y --no-install-recommends \
        build-essential \
        cmake \
        ninja-build \
        python3 \
        wget \
        unzip \
        gcc-aarch64-linux-gnu \
        g++-aarch64-linux-gnu \
        binutils-aarch64-linux-gnu \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Download and install Android NDK
RUN mkdir -p /opt/android && \
    wget -q -O /tmp/ndk.zip "${NDK_URL}" && \
    unzip -q /tmp/ndk.zip -d /opt/android && \
    rm /tmp/ndk.zip && \
    ln -s /opt/android/android-ndk-${NDK_VERSION} /opt/android/ndk

ENV ANDROID_NDK_HOME=/opt/android/ndk
ENV PATH="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin:${PATH}"

WORKDIR /project

# Default: run build-all.sh
CMD ["bash", "shims/docker/build-all.sh"]
