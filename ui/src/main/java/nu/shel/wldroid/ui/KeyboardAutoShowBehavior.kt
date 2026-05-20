package nu.shel.wldroid.ui

/** Controls when [CompositorSurface] automatically asks Android to show the soft keyboard. */
enum class KeyboardAutoShowBehavior {
    /**
     * Show the Android keyboard only when a Wayland text-input request is committed.
     *
     * This is the least intrusive option and relies on clients supporting the
     * Wayland text-input protocol.
     */
    TextInputRequestsOnly,

    /**
     * Show for Wayland text-input requests, and also after a user tap/click gives
     * the compositor surface focus.
     *
     * The tap/click fallback helps XWayland, Electron, and other desktop apps
     * that can accept synthetic keyboard input but do not advertise focused text
     * fields through the Wayland text-input protocol.
     */
    TextInputRequestsAndFocusTap,
    ;

    internal val opensOnFocusTap: Boolean
        get() = this == TextInputRequestsAndFocusTap
}
