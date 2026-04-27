/*
 * test_keycode_map.c — Verify Android→Linux keycode mapping table
 *
 * Build (host-only, no Android deps):
 *   cc -I../src test_keycode_map.c -o test_keycode_map && ./test_keycode_map
 */
#include "test_harness.h"
#include "keycode_map.h"

/* ── Critical letter mappings ─────────────────────────────────────── */

TEST(letter_a) { ASSERT_EQ(android_keycode_to_linux(29), KEY_A); }
TEST(letter_z) { ASSERT_EQ(android_keycode_to_linux(54), KEY_Z); }

/* ── Digit mappings ───────────────────────────────────────────────── */

TEST(digit_0) { ASSERT_EQ(android_keycode_to_linux(7),  KEY_0); }
TEST(digit_9) { ASSERT_EQ(android_keycode_to_linux(16), KEY_9); }

/* ── Modifier keys ────────────────────────────────────────────────── */

TEST(ctrl_left)  { ASSERT_EQ(android_keycode_to_linux(113), KEY_LEFTCTRL);  }
TEST(shift_left) { ASSERT_EQ(android_keycode_to_linux(59),  KEY_LEFTSHIFT); }
TEST(alt_left)   { ASSERT_EQ(android_keycode_to_linux(57),  KEY_LEFTALT);   }
TEST(meta_left)  { ASSERT_EQ(android_keycode_to_linux(117), KEY_LEFTMETA);  }

/* ── Function keys ────────────────────────────────────────────────── */

TEST(f1)  { ASSERT_EQ(android_keycode_to_linux(131), KEY_F1);  }
TEST(f12) { ASSERT_EQ(android_keycode_to_linux(142), KEY_F12); }

/* ── Special keys ─────────────────────────────────────────────────── */

TEST(enter)     { ASSERT_EQ(android_keycode_to_linux(66),  KEY_ENTER);     }
TEST(backspace) { ASSERT_EQ(android_keycode_to_linux(67),  KEY_BACKSPACE); }
TEST(escape)    { ASSERT_EQ(android_keycode_to_linux(111), KEY_ESC);       }
TEST(tab)       { ASSERT_EQ(android_keycode_to_linux(61),  KEY_TAB);       }
TEST(space)     { ASSERT_EQ(android_keycode_to_linux(62),  KEY_SPACE);     }
TEST(del)       { ASSERT_EQ(android_keycode_to_linux(112), KEY_DELETE);    }

/* ── Navigation keys ──────────────────────────────────────────────── */

TEST(up)       { ASSERT_EQ(android_keycode_to_linux(19),  KEY_UP);       }
TEST(down)     { ASSERT_EQ(android_keycode_to_linux(20),  KEY_DOWN);     }
TEST(left)     { ASSERT_EQ(android_keycode_to_linux(21),  KEY_LEFT);     }
TEST(right)    { ASSERT_EQ(android_keycode_to_linux(22),  KEY_RIGHT);    }
TEST(home)     { ASSERT_EQ(android_keycode_to_linux(122), KEY_HOME);     }
TEST(end)      { ASSERT_EQ(android_keycode_to_linux(123), KEY_END);      }
TEST(pageup)   { ASSERT_EQ(android_keycode_to_linux(92),  KEY_PAGEUP);   }
TEST(pagedown) { ASSERT_EQ(android_keycode_to_linux(93),  KEY_PAGEDOWN); }

/* ── Bounds checking ──────────────────────────────────────────────── */

TEST(negative_returns_zero) { ASSERT_EQ(android_keycode_to_linux(-1),  0); }
TEST(too_large_returns_zero) { ASSERT_EQ(android_keycode_to_linux(999), 0); }

/* ── Unmapped keycode ─────────────────────────────────────────────── */

TEST(unknown_returns_zero) { ASSERT_EQ(android_keycode_to_linux(0), 0); }

int main(void) {
    printf("Keycode mapping tests\n");

    RUN_TEST(letter_a);
    RUN_TEST(letter_z);
    RUN_TEST(digit_0);
    RUN_TEST(digit_9);
    RUN_TEST(ctrl_left);
    RUN_TEST(shift_left);
    RUN_TEST(alt_left);
    RUN_TEST(meta_left);
    RUN_TEST(f1);
    RUN_TEST(f12);
    RUN_TEST(enter);
    RUN_TEST(backspace);
    RUN_TEST(escape);
    RUN_TEST(tab);
    RUN_TEST(space);
    RUN_TEST(del);
    RUN_TEST(up);
    RUN_TEST(down);
    RUN_TEST(left);
    RUN_TEST(right);
    RUN_TEST(home);
    RUN_TEST(end);
    RUN_TEST(pageup);
    RUN_TEST(pagedown);
    RUN_TEST(negative_returns_zero);
    RUN_TEST(too_large_returns_zero);
    RUN_TEST(unknown_returns_zero);

    TEST_SUMMARY();
}
