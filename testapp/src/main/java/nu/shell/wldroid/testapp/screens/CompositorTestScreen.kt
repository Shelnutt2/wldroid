package nu.shell.wldroid.testapp.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import nu.shell.wldroid.compositor.CompositorConfig
import nu.shell.wldroid.compositor.CompositorSession
import nu.shell.wldroid.compositor.CompositorState
import nu.shell.wldroid.ui.CompositorSurface
import nu.shell.wldroid.ui.InputMode

@HiltViewModel
class CompositorTestViewModel @Inject constructor(
    val compositorConfig: CompositorConfig,
) : ViewModel() {
    val session = CompositorSession(compositorConfig)
    val state get() = session.state
    val clientCount get() = session.clientCount
    val socketPath get() = session.socketPath
}

@Composable
fun CompositorTestScreen(
    viewModel: CompositorTestViewModel = hiltViewModel(),
) {
    val compositorState by viewModel.state.collectAsState()
    val clientCount by viewModel.clientCount.collectAsState()
    val socketPath by viewModel.socketPath.collectAsState()
    var inputMode by remember { mutableStateOf(InputMode.TOUCH_AND_KEYBOARD) }

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        // Status & controls (scrollable header)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Status card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (compositorState) {
                        CompositorState.RUNNING -> MaterialTheme.colorScheme.primaryContainer
                        CompositorState.ERROR -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Compositor Status",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    StatusRow("State", compositorState.name)
                    StatusRow("Clients", clientCount.toString())
                    StatusRow("Socket", socketPath ?: "—")
                    StatusRow("Input Mode", inputMode.name)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Input mode toggle using InputMode enum from :ui
            Text("Input Mode", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InputMode.entries.forEach { mode ->
                    FilterChip(
                        selected = inputMode == mode,
                        onClick = { inputMode = mode },
                        label = { Text(mode.name) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Test client
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Test Pattern Client",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Launch a built-in wlroots test pattern client to verify " +
                            "compositor rendering.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            // The test client is launched via the native compositor
                            // It requires the compositor to be running
                        },
                        enabled = compositorState == CompositorState.RUNNING,
                    ) {
                        Text("Launch Test Pattern")
                    }
                }
            }
        }

        // Compositor surface from :ui (manages SurfaceView, input, and keyboard FAB internally)
        CompositorSurface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            config = viewModel.compositorConfig,
            onStateChange = { /* state already observed via ViewModel */ },
            onClientCountChange = { /* client count already observed via ViewModel */ },
            inputMode = inputMode,
            showKeyboardFab = true,
        )
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
