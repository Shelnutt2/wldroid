package nu.shel.wldroid.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KeyboardAutoShowBehaviorTest {
    @Test
    fun `auto show behavior exposes protocol-only and tap fallback modes`() {
        assertThat(KeyboardAutoShowBehavior.entries).containsExactly(
            KeyboardAutoShowBehavior.TextInputRequestsOnly,
            KeyboardAutoShowBehavior.TextInputRequestsAndFocusTap,
        ).inOrder()
    }

    @Test
    fun `focus tap fallback is enabled only for tap fallback mode`() {
        assertThat(KeyboardAutoShowBehavior.TextInputRequestsOnly.opensOnFocusTap).isFalse()
        assertThat(KeyboardAutoShowBehavior.TextInputRequestsAndFocusTap.opensOnFocusTap).isTrue()
    }
}
