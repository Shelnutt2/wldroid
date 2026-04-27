/*
 * keycode_map.h — Android keycode → Linux input-event-codes mapping
 *
 * Static O(1) lookup table indexed by Android KEYCODE_* values.
 * Unmapped keycodes return 0.
 */
#ifndef KEYCODE_MAP_H
#define KEYCODE_MAP_H

#include <stdint.h>
#include <linux/input-event-codes.h>

#define ANDROID_KEYCODE_MAX 304

/*
 * Index = Android KEYCODE_* constant
 * Value = Linux KEY_* constant (0 = unmapped)
 *
 * Reference: https://developer.android.com/reference/android/view/KeyEvent
 */
static const uint16_t android_to_linux_keycode[ANDROID_KEYCODE_MAX] = {
    /* ---- Digits (KEYCODE_0 .. KEYCODE_9 = 7..16) ---- */
    [7]  = KEY_0,
    [8]  = KEY_1,
    [9]  = KEY_2,
    [10] = KEY_3,
    [11] = KEY_4,
    [12] = KEY_5,
    [13] = KEY_6,
    [14] = KEY_7,
    [15] = KEY_8,
    [16] = KEY_9,

    /* ---- Star / Pound ---- */
    [17] = KEY_NUMERIC_STAR,
    [18] = KEY_NUMERIC_POUND,

    /* ---- D-pad / navigation (KEYCODE_DPAD_UP=19 .. DPAD_CENTER=23) ---- */
    [19] = KEY_UP,
    [20] = KEY_DOWN,
    [21] = KEY_LEFT,
    [22] = KEY_RIGHT,
    [23] = KEY_SELECT,      /* DPAD_CENTER → KEY_SELECT */

    /* ---- Volume / Power ---- */
    [24] = KEY_VOLUMEUP,
    [25] = KEY_VOLUMEDOWN,
    [26] = KEY_POWER,

    /* ---- Letters (KEYCODE_A=29 .. KEYCODE_Z=54) ---- */
    [29] = KEY_A,
    [30] = KEY_B,
    [31] = KEY_C,
    [32] = KEY_D,
    [33] = KEY_E,
    [34] = KEY_F,
    [35] = KEY_G,
    [36] = KEY_H,
    [37] = KEY_I,
    [38] = KEY_J,
    [39] = KEY_K,
    [40] = KEY_L,
    [41] = KEY_M,
    [42] = KEY_N,
    [43] = KEY_O,
    [44] = KEY_P,
    [45] = KEY_Q,
    [46] = KEY_R,
    [47] = KEY_S,
    [48] = KEY_T,
    [49] = KEY_U,
    [50] = KEY_V,
    [51] = KEY_W,
    [52] = KEY_X,
    [53] = KEY_Y,
    [54] = KEY_Z,

    /* ---- Punctuation / symbols ---- */
    [55] = KEY_COMMA,
    [56] = KEY_DOT,

    /* ---- Modifiers ---- */
    [57] = KEY_LEFTALT,     /* KEYCODE_ALT_LEFT */
    [58] = KEY_RIGHTALT,    /* KEYCODE_ALT_RIGHT */
    [59] = KEY_LEFTSHIFT,   /* KEYCODE_SHIFT_LEFT */
    [60] = KEY_RIGHTSHIFT,  /* KEYCODE_SHIFT_RIGHT */

    /* ---- Common keys ---- */
    [61] = KEY_TAB,
    [62] = KEY_SPACE,

    /* ---- Enter / Delete ---- */
    [66] = KEY_ENTER,
    [67] = KEY_BACKSPACE,   /* KEYCODE_DEL (backspace) */

    /* ---- Symbols (KEYCODE_GRAVE=68 .. KEYCODE_PLUS=81) ---- */
    [68] = KEY_GRAVE,       /* ` */
    [69] = KEY_MINUS,       /* - */
    [70] = KEY_EQUAL,       /* = */
    [71] = KEY_LEFTBRACE,   /* [ */
    [72] = KEY_RIGHTBRACE,  /* ] */
    [73] = KEY_BACKSLASH,   /* \ */
    [74] = KEY_SEMICOLON,   /* ; */
    [75] = KEY_APOSTROPHE,  /* ' */
    [76] = KEY_SLASH,       /* / */

    /* ---- Navigation ---- */
    [92]  = KEY_PAGEUP,
    [93]  = KEY_PAGEDOWN,

    /* ---- Escape ---- */
    [111] = KEY_ESC,

    /* ---- Forward delete ---- */
    [112] = KEY_DELETE,     /* KEYCODE_FORWARD_DEL */

    /* ---- Control keys ---- */
    [113] = KEY_LEFTCTRL,   /* KEYCODE_CTRL_LEFT */
    [114] = KEY_RIGHTCTRL,  /* KEYCODE_CTRL_RIGHT */

    /* ---- Caps / Scroll / Num Lock ---- */
    [115] = KEY_CAPSLOCK,
    [116] = KEY_SCROLLLOCK,
    [117] = KEY_LEFTMETA,   /* KEYCODE_META_LEFT */
    [118] = KEY_RIGHTMETA,  /* KEYCODE_META_RIGHT */

    /* ---- Print / Pause / Insert ---- */
    [120] = KEY_SYSRQ,      /* KEYCODE_SYSRQ (Print Screen) */
    [121] = KEY_PAUSE,      /* KEYCODE_BREAK */
    [122] = KEY_HOME,       /* KEYCODE_MOVE_HOME */
    [123] = KEY_END,        /* KEYCODE_MOVE_END */
    [124] = KEY_INSERT,

    /* ---- F1–F12 (KEYCODE_F1=131 .. KEYCODE_F12=142) ---- */
    [131] = KEY_F1,
    [132] = KEY_F2,
    [133] = KEY_F3,
    [134] = KEY_F4,
    [135] = KEY_F5,
    [136] = KEY_F6,
    [137] = KEY_F7,
    [138] = KEY_F8,
    [139] = KEY_F9,
    [140] = KEY_F10,
    [141] = KEY_F11,
    [142] = KEY_F12,

    /* ---- Num Lock ---- */
    [143] = KEY_NUMLOCK,

    /* ---- Numpad (KEYCODE_NUMPAD_0=144 .. NUMPAD_9=153) ---- */
    [144] = KEY_KP0,
    [145] = KEY_KP1,
    [146] = KEY_KP2,
    [147] = KEY_KP3,
    [148] = KEY_KP4,
    [149] = KEY_KP5,
    [150] = KEY_KP6,
    [151] = KEY_KP7,
    [152] = KEY_KP8,
    [153] = KEY_KP9,

    /* ---- Numpad operators ---- */
    [154] = KEY_KPSLASH,
    [155] = KEY_KPASTERISK,
    [156] = KEY_KPMINUS,
    [157] = KEY_KPPLUS,
    [158] = KEY_KPDOT,
    [160] = KEY_KPENTER,
};

/**
 * Convert an Android keycode to a Linux input-event-codes keycode.
 * Returns 0 if the keycode is out of range or unmapped.
 */
static inline uint16_t android_keycode_to_linux(int android_keycode) {
    if (android_keycode < 0 || android_keycode >= ANDROID_KEYCODE_MAX) {
        return 0;
    }
    return android_to_linux_keycode[android_keycode];
}

#endif /* KEYCODE_MAP_H */
