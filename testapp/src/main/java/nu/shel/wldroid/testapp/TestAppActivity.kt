package nu.shel.wldroid.testapp

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nu.shel.wldroid.launcher.WldroidService
import nu.shel.wldroid.proot.EnvironmentRegistry
import nu.shel.wldroid.testapp.navigation.TestAppNavHost
import nu.shel.wldroid.testapp.navigation.TestAppRoute
import nu.shel.wldroid.ui.theme.WldroidTheme

@AndroidEntryPoint
class TestAppActivity : ComponentActivity() {

    @Inject lateinit var environmentRegistry: EnvironmentRegistry

    private val _service = MutableStateFlow<WldroidService?>(null)
    private var serviceScopeForRegistry: CoroutineScope? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* best-effort, no-op on denial */ }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            _service.value = (binder as WldroidService.LocalBinder).getService()
            serviceScopeForRegistry = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            environmentRegistry.setServiceScope(serviceScopeForRegistry)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            _service.value = null
            serviceScopeForRegistry?.cancel()
            serviceScopeForRegistry = null
            environmentRegistry.clearServiceScope()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start and bind the foreground service.
        val serviceIntent = Intent(this, WldroidService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)

        // Request notification permission on Android 13+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        enableEdgeToEdge()
        setContent {
            WldroidTheme {
                TestAppContent(serviceFlow = _service.asStateFlow())
            }
        }
    }

    override fun onDestroy() {
        unbindService(serviceConnection)
        serviceScopeForRegistry?.cancel()
        serviceScopeForRegistry = null
        environmentRegistry.clearServiceScope()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TestAppContent(serviceFlow: StateFlow<WldroidService?>) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val currentScreen = TestAppRoute.all.find { it.route == currentRoute }
        ?: TestAppRoute.Compositor

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = "WLDroid Test App",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(16.dp),
                )
                TestAppRoute.all.forEach { route ->
                    NavigationDrawerItem(
                        label = { Text("${route.icon}  ${route.title}") },
                        selected = currentRoute == route.route,
                        onClick = {
                            scope.launch { drawerState.close() }
                            navController.navigate(route.route) {
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(currentScreen.title) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Text("☰", style = MaterialTheme.typography.titleLarge)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            },
        ) { innerPadding ->
            TestAppNavHost(
                navController = navController,
                serviceFlow = serviceFlow,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}
