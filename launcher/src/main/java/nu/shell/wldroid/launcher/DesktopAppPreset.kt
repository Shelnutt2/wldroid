package nu.shell.wldroid.launcher

data class DesktopAppPreset(
    val id: String,
    val displayName: String,
    val command: List<String>,
    val requiredPackages: List<String> = emptyList(),
    val description: String = "",
    val icon: String = "🖥",
) {
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
        val VKCUBE = DesktopAppPreset(
            id = "vkcube",
            displayName = "vkcube",
            command = listOf("vkcube", "--wsi", "wayland"),
            requiredPackages = listOf("vulkan-tools"),
            description = "Vulkan test cube",
            icon = "🟦",
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
        val ALL = listOf(TEST_PATTERN, WESTON_TERMINAL, ES2GEARS, VKCUBE, XTERM, VSCODE, FIREFOX)
    }
}
