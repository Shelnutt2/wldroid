package nu.shell.wldroid.testapp.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import nu.shell.wldroid.testapp.screens.CompositorTestScreen
import nu.shell.wldroid.testapp.screens.DesktopScreen
import nu.shell.wldroid.testapp.screens.EnvironmentScreen
import nu.shell.wldroid.testapp.screens.GpuDiagnosticsScreen
import nu.shell.wldroid.testapp.screens.NativeTestScreen
import nu.shell.wldroid.testapp.screens.SettingsScreen
import nu.shell.wldroid.testapp.screens.ShimTestScreen

/** Navigation route definitions for the test app. */
sealed class TestAppRoute(val route: String, val title: String, val icon: String) {
    data object Compositor : TestAppRoute("compositor", "Compositor", "🖥")
    data object Desktop : TestAppRoute("desktop", "Desktop", "🖥️")
    data object Environment : TestAppRoute("environment", "Environments", "📦")
    data object GpuDiagnostics : TestAppRoute("gpu_diagnostics", "GPU", "🎮")
    data object ShimTest : TestAppRoute("shim_test", "Shims", "🔧")
    data object NativeTest : TestAppRoute("native_test", "Native Tests", "🧪")
    data object Settings : TestAppRoute("settings", "Settings", "⚙")

    companion object {
        val all: List<TestAppRoute> = listOf(
            Compositor, Desktop, Environment, GpuDiagnostics, ShimTest, NativeTest, Settings,
        )
    }
}

@Composable
fun TestAppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = TestAppRoute.Compositor.route,
        modifier = modifier,
    ) {
        composable(TestAppRoute.Compositor.route) { CompositorTestScreen() }
        composable(TestAppRoute.Desktop.route) { DesktopScreen() }
        composable(TestAppRoute.Environment.route) { EnvironmentScreen() }
        composable(TestAppRoute.GpuDiagnostics.route) { GpuDiagnosticsScreen() }
        composable(TestAppRoute.ShimTest.route) { ShimTestScreen() }
        composable(TestAppRoute.NativeTest.route) { NativeTestScreen() }
        composable(TestAppRoute.Settings.route) { SettingsScreen() }
    }
}
