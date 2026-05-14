package nu.shel.wldroid.testapp

import com.google.common.truth.Truth.assertThat
import nu.shel.wldroid.testapp.navigation.TestAppRoute
import org.junit.Test

class NavigationTest {

    @Test
    fun `all routes are defined`() {
        val routes = TestAppRoute.all
        assertThat(routes).hasSize(7)
    }

    @Test
    fun `all routes have unique route strings`() {
        val routeStrings = TestAppRoute.all.map { it.route }
        assertThat(routeStrings).containsNoDuplicates()
    }

    @Test
    fun `all routes have non-empty titles`() {
        TestAppRoute.all.forEach { route ->
            assertThat(route.title).isNotEmpty()
        }
    }

    @Test
    fun `all routes have non-empty icons`() {
        TestAppRoute.all.forEach { route ->
            assertThat(route.icon).isNotEmpty()
        }
    }

    @Test
    fun `compositor route is first`() {
        assertThat(TestAppRoute.all.first()).isEqualTo(TestAppRoute.Compositor)
    }

    @Test
    fun `expected routes are present`() {
        val routeStrings = TestAppRoute.all.map { it.route }
        assertThat(routeStrings).containsExactly(
            "compositor",
            "desktop",
            "environment",
            "gpu_diagnostics",
            "shim_test",
            "native_test",
            "settings",
        )
    }
}
