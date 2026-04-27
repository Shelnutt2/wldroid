package nu.shell.wldroid.proot

/**
 * Configuration for proot execution.
 *
 * @property prootBinaryPath Path to the proot binary (typically `libproot.so` in nativeLibraryDir)
 * @property prootLoaderPath Path to the proot loader binary (typically `libproot-loader.so`)
 * @property defaultDistro Default distro template for new environments
 * @property rootfsBaseDir Base directory for rootfs environments (each env gets a subdirectory)
 * @property cacheDir Directory for proot runtime caches (shm, tmp, sysfs stubs)
 * @property fakeRoot Whether to run as fake root (proot `-0` flag)
 * @property link2symlink Whether to enable proot's link2symlink extension
 */
data class ProotConfig(
    val prootBinaryPath: String,
    val prootLoaderPath: String = "",
    val defaultDistro: DistroTemplate = DistroTemplate.DEBIAN_TRIXIE,
    val rootfsBaseDir: String = "",
    val cacheDir: String = "",
    val fakeRoot: Boolean = true,
    val link2symlink: Boolean = true,
)

/**
 * Represents a bind mount between host and guest paths in a proot environment.
 *
 * @property hostPath Absolute path on the host (Android) filesystem
 * @property guestPath Path inside the proot guest filesystem
 * @property readOnly Whether the mount is read-only (note: proot doesn't enforce this natively)
 */
data class BindMount(
    val hostPath: String,
    val guestPath: String,
    val readOnly: Boolean = false,
)

/**
 * Distro templates with download URLs and checksums for rootfs tarballs.
 *
 * URLs and checksums reference proot-distro releases from Termux.
 */
enum class DistroTemplate(
    val displayName: String,
    val downloadUrl: String,
    val sha256: String,
    val version: String,
) {
    DEBIAN_TRIXIE(
        displayName = "Debian Trixie",
        downloadUrl = "https://github.com/termux/proot-distro/releases/download/v4.26.0/debian-trixie-aarch64-pd-v4.26.0.tar.xz",
        sha256 = "cda75346f2c9e09e8a802665745b5a7e2bd6d8584dbf1c86c8c57ef54c4e2d3c",
        version = "v4.26.0",
    ),
    DEBIAN_BOOKWORM(
        displayName = "Debian Bookworm",
        downloadUrl = "https://github.com/termux/proot-distro/releases/download/v4.6.0/debian-aarch64-pd-v4.6.0.tar.xz",
        sha256 = "68dab31b46af61114014b54876c4f317be648ce8c76c0c6cbb5d6011d420886c",
        version = "v4.6.0",
    ),
}
