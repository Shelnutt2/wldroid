package nu.shel.wldroid.launcher

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DesktopLauncherStateTest {
    @Test fun idle_isTerminal() {
        assertThat(DesktopLauncherState.Idle.isTerminal).isTrue()
        assertThat(DesktopLauncherState.Idle.isActive).isFalse()
    }
    @Test fun running_isActive() {
        assertThat(DesktopLauncherState.Running.isActive).isTrue()
        assertThat(DesktopLauncherState.Running.isTerminal).isFalse()
    }
    @Test fun error_isTerminal() {
        val error = DesktopLauncherState.Error("fail", "test")
        assertThat(error.isTerminal).isTrue()
        assertThat(error.isActive).isFalse()
    }
    @Test fun startingCompositor_isActive() {
        assertThat(DesktopLauncherState.StartingCompositor.isActive).isTrue()
    }
    @Test fun detectingGpu_isActive() {
        assertThat(DesktopLauncherState.DetectingGpu.isActive).isTrue()
    }
    @Test fun startingVirgl_isActive() {
        assertThat(DesktopLauncherState.StartingVirgl.isActive).isTrue()
    }
    @Test fun extractingShims_isActive() {
        assertThat(DesktopLauncherState.ExtractingShims.isActive).isTrue()
    }
    @Test fun setupGpu_isActive() {
        assertThat(DesktopLauncherState.SetupGpu.isActive).isTrue()
    }
    @Test fun installingPackages_isActive() {
        assertThat(DesktopLauncherState.InstallingPackages.isActive).isTrue()
    }
    @Test fun launchingApp_isActive() {
        assertThat(DesktopLauncherState.LaunchingApp.isActive).isTrue()
    }
    @Test fun stopping_isActive() {
        assertThat(DesktopLauncherState.Stopping.isActive).isTrue()
    }
}
