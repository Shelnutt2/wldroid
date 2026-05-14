package nu.shel.wldroid.launcher

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class XWaylandConfigTest {

    @Test fun defaultConfig_isEnabled() {
        val config = XWaylandConfig()
        assertThat(config.enabled).isTrue()
    }

    @Test fun defaultConfig_displayNumber0() {
        val config = XWaylandConfig()
        assertThat(config.displayNumber).isEqualTo(0)
    }

    @Test fun defaultConfig_noAdditionalPackages() {
        val config = XWaylandConfig()
        assertThat(config.additionalPackages).isEmpty()
    }

    @Test fun displayName_defaultIsColon0() {
        val config = XWaylandConfig()
        assertThat(config.displayName).isEqualTo(":0")
    }

    @Test fun displayName_customDisplayNumber() {
        val config = XWaylandConfig(displayNumber = 3)
        assertThat(config.displayName).isEqualTo(":3")
    }

    @Test fun disabledConfig() {
        val config = XWaylandConfig(enabled = false)
        assertThat(config.enabled).isFalse()
        // displayName still works even when disabled (caller decides whether to use it)
        assertThat(config.displayName).isEqualTo(":0")
    }

    @Test fun configWithAdditionalPackages() {
        val config = XWaylandConfig(additionalPackages = listOf("xterm", "x11-utils"))
        assertThat(config.additionalPackages).containsExactly("xterm", "x11-utils")
    }
}
