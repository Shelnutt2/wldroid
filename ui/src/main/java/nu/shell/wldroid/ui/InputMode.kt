package nu.shell.wldroid.ui

/**
 * Defines how user input is routed to the Wayland compositor.
 */
enum class InputMode {
    /** Only touch events are forwarded to the compositor. */
    TOUCH_ONLY,

    /** Only keyboard events are forwarded. */
    KEYBOARD_ONLY,

    /** Both touch and keyboard events are forwarded (default). */
    TOUCH_AND_KEYBOARD,

    /** Pointer (mouse) and keyboard events are forwarded; touch is ignored. */
    POINTER_AND_KEYBOARD,
    ;

    /** Whether this mode includes touch input. */
    val hasTouchInput: Boolean
        get() = this == TOUCH_ONLY || this == TOUCH_AND_KEYBOARD

    /** Whether this mode includes keyboard input. */
    val hasKeyboardInput: Boolean
        get() = this == KEYBOARD_ONLY || this == TOUCH_AND_KEYBOARD || this == POINTER_AND_KEYBOARD

    /** Whether this mode includes pointer/mouse input. */
    val hasPointerInput: Boolean
        get() = this == POINTER_AND_KEYBOARD
}
