package nu.shel.wldroid.launcher

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DesktopSessionTest {

    @Test
    fun `DesktopSessionState has expected entries`() {
        assertThat(DesktopSessionState.entries).containsExactly(
            DesktopSessionState.IDLE,
            DesktopSessionState.STARTING,
            DesktopSessionState.RUNNING,
            DesktopSessionState.STOPPING,
            DesktopSessionState.STOPPED,
            DesktopSessionState.ERROR,
        )
    }

    @Test
    fun `initial state is IDLE`() {
        assertThat(DesktopSessionState.IDLE.ordinal).isEqualTo(0)
    }

    @Test
    fun `state ordinals are sequential`() {
        val states = DesktopSessionState.entries
        for (i in 0 until states.size) {
            assertThat(states[i].ordinal).isEqualTo(i)
        }
    }
}
