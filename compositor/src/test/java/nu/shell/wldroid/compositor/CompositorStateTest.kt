package nu.shell.wldroid.compositor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CompositorStateTest {

    @Test
    fun `all expected states exist`() {
        val states = CompositorState.entries
        assertEquals(6, states.size)
        assertNotNull(CompositorState.valueOf("IDLE"))
        assertNotNull(CompositorState.valueOf("STARTING"))
        assertNotNull(CompositorState.valueOf("RUNNING"))
        assertNotNull(CompositorState.valueOf("STOPPING"))
        assertNotNull(CompositorState.valueOf("STOPPED"))
        assertNotNull(CompositorState.valueOf("ERROR"))
    }

    @Test
    fun `states have correct ordinal order`() {
        assertEquals(0, CompositorState.IDLE.ordinal)
        assertEquals(1, CompositorState.STARTING.ordinal)
        assertEquals(2, CompositorState.RUNNING.ordinal)
        assertEquals(3, CompositorState.STOPPING.ordinal)
        assertEquals(4, CompositorState.STOPPED.ordinal)
        assertEquals(5, CompositorState.ERROR.ordinal)
    }
}
