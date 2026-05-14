package nu.shel.wldroid.virgl

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class VirglServerManagerTest {

    private fun createManager(
        binaryPath: String = "/nonexistent/virgl",
        socketPath: String = "/tmp/test/.virgl_test",
    ) = VirglServerManager(
        VirglConfig(
            virglBinaryPath = binaryPath,
            socketPath = socketPath,
        ),
    )

    @Test
    fun `isRunning returns false when no process started`() {
        val manager = createManager()
        assertThat(manager.isRunning).isFalse()
    }

    @Test
    fun `socketDir derived from socketPath`() {
        val manager = createManager(socketPath = "/data/local/tmp/.virgl_test")
        assertThat(manager.socketDir).isEqualTo(File("/data/local/tmp"))
    }

    @Test
    fun `socketPath matches config`() {
        val path = "/tmp/custom/.virgl_test"
        val manager = createManager(socketPath = path)
        assertThat(manager.socketPath).isEqualTo(path)
    }

    @Test
    fun `serverBinaryPath matches config`() {
        val path = "/opt/virgl/server"
        val manager = createManager(binaryPath = path)
        assertThat(manager.serverBinaryPath).isEqualTo(path)
    }
}
