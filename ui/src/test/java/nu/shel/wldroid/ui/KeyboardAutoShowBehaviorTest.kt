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
}
