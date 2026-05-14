package nu.shel.wldroid.proot

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for [RootfsStore] JSON serialization/deserialization.
 *
 * DataStore operations require Android context (tested via instrumentation),
 * but the JSON parsing logic is pure Kotlin and testable here.
 */
class RootfsStoreTest {

    @Test
    fun `serialize and deserialize round-trips correctly`() {
        val envs = listOf(
            RootfsEnvironment(
                id = "test-1",
                name = "Test Environment",
                rootfsPath = "/data/rootfs/test-1",
                distro = "DEBIAN_TRIXIE",
                createdAt = 1700000000000L,
                sizeBytes = 500_000_000L,
                lastUsedAt = 1700001000000L,
                status = RootfsStatus.READY,
            ),
            RootfsEnvironment(
                id = "test-2",
                name = "Another Env",
                rootfsPath = "/data/rootfs/test-2",
                distro = "DEBIAN_BOOKWORM",
                createdAt = 1700002000000L,
                sizeBytes = 0,
                lastUsedAt = null,
                status = RootfsStatus.DOWNLOADING,
            ),
        )

        val json = RootfsStore.serializeEnvironmentList(envs)
        val parsed = RootfsStore.parseEnvironmentList(json)

        assertThat(parsed).hasSize(2)
        assertThat(parsed[0].id).isEqualTo("test-1")
        assertThat(parsed[0].name).isEqualTo("Test Environment")
        assertThat(parsed[0].rootfsPath).isEqualTo("/data/rootfs/test-1")
        assertThat(parsed[0].distro).isEqualTo("DEBIAN_TRIXIE")
        assertThat(parsed[0].createdAt).isEqualTo(1700000000000L)
        assertThat(parsed[0].sizeBytes).isEqualTo(500_000_000L)
        assertThat(parsed[0].lastUsedAt).isEqualTo(1700001000000L)
        assertThat(parsed[0].status).isEqualTo(RootfsStatus.READY)

        assertThat(parsed[1].id).isEqualTo("test-2")
        assertThat(parsed[1].lastUsedAt).isNull()
        assertThat(parsed[1].status).isEqualTo(RootfsStatus.DOWNLOADING)
    }

    @Test
    fun `empty list serializes to empty JSON array`() {
        val json = RootfsStore.serializeEnvironmentList(emptyList())
        assertThat(json).isEqualTo("[]")
    }

    @Test
    fun `empty JSON array parses to empty list`() {
        val result = RootfsStore.parseEnvironmentList("[]")
        assertThat(result).isEmpty()
    }

    @Test
    fun `missing optional fields use defaults`() {
        val json = """[{"id":"minimal","rootfsPath":"/path","createdAt":1000}]"""
        val parsed = RootfsStore.parseEnvironmentList(json)

        assertThat(parsed).hasSize(1)
        assertThat(parsed[0].id).isEqualTo("minimal")
        assertThat(parsed[0].name).isEqualTo("minimal") // defaults to id
        assertThat(parsed[0].distro).isEmpty()
        assertThat(parsed[0].sizeBytes).isEqualTo(0)
        assertThat(parsed[0].lastUsedAt).isNull()
        assertThat(parsed[0].status).isEqualTo(RootfsStatus.READY)
    }

    @Test
    fun `unknown status falls back to READY`() {
        val json = """[{"id":"test","rootfsPath":"/path","createdAt":1000,"status":"UNKNOWN"}]"""
        val parsed = RootfsStore.parseEnvironmentList(json)
        assertThat(parsed[0].status).isEqualTo(RootfsStatus.READY)
    }

    @Test
    fun `null lastUsedAt is handled correctly`() {
        val json = """[{"id":"test","rootfsPath":"/path","createdAt":1000,"lastUsedAt":null}]"""
        val parsed = RootfsStore.parseEnvironmentList(json)
        assertThat(parsed[0].lastUsedAt).isNull()
    }
}
