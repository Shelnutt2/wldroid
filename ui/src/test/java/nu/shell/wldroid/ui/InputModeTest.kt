package nu.shell.wldroid.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InputModeTest {

    @Test
    fun `enum has exactly four values`() {
        assertThat(InputMode.entries).hasSize(5)
    }

    @Test
    fun `TOUCH_ONLY has only touch input`() {
        val mode = InputMode.TOUCH_ONLY
        assertThat(mode.hasTouchInput).isTrue()
        assertThat(mode.hasKeyboardInput).isFalse()
        assertThat(mode.hasPointerInput).isFalse()
    }

    @Test
    fun `KEYBOARD_ONLY has only keyboard input`() {
        val mode = InputMode.KEYBOARD_ONLY
        assertThat(mode.hasTouchInput).isFalse()
        assertThat(mode.hasKeyboardInput).isTrue()
        assertThat(mode.hasPointerInput).isFalse()
    }

    @Test
    fun `TOUCH_AND_KEYBOARD has touch and keyboard input`() {
        val mode = InputMode.TOUCH_AND_KEYBOARD
        assertThat(mode.hasTouchInput).isTrue()
        assertThat(mode.hasKeyboardInput).isTrue()
        assertThat(mode.hasPointerInput).isFalse()
    }

    @Test
    fun `POINTER_AND_KEYBOARD has pointer and keyboard input`() {
        val mode = InputMode.POINTER_AND_KEYBOARD
        assertThat(mode.hasTouchInput).isFalse()
        assertThat(mode.hasKeyboardInput).isTrue()
        assertThat(mode.hasPointerInput).isTrue()
    }

    @Test
    fun `values can be looked up by name`() {
        assertThat(InputMode.valueOf("TOUCH_ONLY")).isEqualTo(InputMode.TOUCH_ONLY)
        assertThat(InputMode.valueOf("KEYBOARD_ONLY")).isEqualTo(InputMode.KEYBOARD_ONLY)
        assertThat(InputMode.valueOf("TOUCH_AND_KEYBOARD")).isEqualTo(InputMode.TOUCH_AND_KEYBOARD)
        assertThat(InputMode.valueOf("POINTER_AND_KEYBOARD")).isEqualTo(InputMode.POINTER_AND_KEYBOARD)
        assertThat(InputMode.valueOf("ALL")).isEqualTo(InputMode.ALL)
    }

    @Test
    fun `ALL has touch keyboard and pointer input`() {
        val mode = InputMode.ALL
        assertThat(mode.hasTouchInput).isTrue()
        assertThat(mode.hasKeyboardInput).isTrue()
        assertThat(mode.hasPointerInput).isTrue()
    }
}
