package nu.shel.wldroid.ui

/** Controls how the host viewport accounts for Android IME overlap. */
enum class KeyboardPanBehavior {
    /** Do not change viewport pan bounds for the IME. */
    None,

    /** Treat the IME-covered bottom area as unsafe and allow panning content above it. */
    PanWithinImeSafeArea,
}
