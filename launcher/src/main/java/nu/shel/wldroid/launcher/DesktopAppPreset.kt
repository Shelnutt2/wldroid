package nu.shel.wldroid.launcher

import nu.shel.wldroid.virgl.GpuMode

data class DesktopAppPreset(
    val id: String,
    val displayName: String,
    val command: List<String>,
    val requiredPackages: List<String> = emptyList(),
    val description: String = "",
    val icon: String = "🖥",
    /**
     * GPU modes this preset is known to work with. `null` means the preset
     * works on any mode (the common case for non-GPU-specific apps).
     *
     * When non-null, the UI should warn the user if the current GPU mode
     * is not in the set — the app will likely fail or produce errors.
     */
    val supportedGpuModes: Set<GpuMode>? = null,
) {
    /** Returns true when the preset is compatible with [mode], or has no mode restrictions. */
    fun isCompatibleWith(mode: GpuMode): Boolean =
        supportedGpuModes == null || mode in supportedGpuModes

    companion object {
        val TEST_PATTERN = DesktopAppPreset(
            id = "test_pattern",
            displayName = "Test Pattern",
            command = listOf("weston-flower"),
            requiredPackages = listOf("weston"),
            description = "Weston test pattern client",
            icon = "🌸",
        )
        val WESTON_TERMINAL = DesktopAppPreset(
            id = "weston_terminal",
            displayName = "Weston Terminal",
            command = listOf("weston-terminal"),
            requiredPackages = listOf("weston"),
            description = "Weston terminal emulator",
            icon = "💻",
        )
        val ES2GEARS = DesktopAppPreset(
            id = "es2gears_wayland",
            displayName = "es2gears",
            command = listOf("es2gears_wayland"),
            requiredPackages = listOf("mesa-utils-extra"),
            description = "OpenGL ES2 test (rotating gears, Wayland native)",
            icon = "⚙",
        )
        val WESTON_SIMPLE_EGL = DesktopAppPreset(
            id = "weston_simple_egl",
            displayName = "simple-egl",
            command = listOf("weston-simple-egl"),
            requiredPackages = listOf("weston"),
            description = "Weston EGL test (animated triangle, works on all GPU modes)",
            icon = "🔺",
        )
        val VKCUBE = DesktopAppPreset(
            id = "vkcube",
            displayName = "vkcube",
            command = listOf("vkcube", "--wsi", "wayland"),
            requiredPackages = listOf("vulkan-tools"),
            description = "Vulkan test cube (requires real Vulkan: Venus or Turnip)",
            icon = "🟦",
            supportedGpuModes = setOf(GpuMode.VENUS, GpuMode.TURNIP_DIRECT),
        )
        val XTERM = DesktopAppPreset(
            id = "xterm",
            displayName = "xterm",
            command = listOf("xterm"),
            requiredPackages = listOf("xterm"),
            description = "X terminal emulator",
            icon = "📟",
        )
        val VSCODE = DesktopAppPreset(
            id = "vscode",
            displayName = "VS Code",
            command = listOf("code", "--ozone-platform=wayland", "--no-sandbox"),
            description = "Visual Studio Code",
            icon = "💻",
        )
        val FIREFOX = DesktopAppPreset(
            id = "firefox",
            displayName = "Firefox",
            command = listOf("firefox"),
            requiredPackages = listOf("firefox-esr"),
            description = "Firefox web browser",
            icon = "🦊",
        )
        val ALL = listOf(
            TEST_PATTERN, WESTON_TERMINAL, ES2GEARS, WESTON_SIMPLE_EGL,
            VKCUBE, XTERM, VSCODE, FIREFOX,
        )
    }
}
