package nu.shel.wldroid.testapp.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import nu.shel.wldroid.proot.DistroTemplate
import nu.shel.wldroid.proot.EnvironmentConfig
import nu.shel.wldroid.proot.EnvironmentRegistry
import nu.shel.wldroid.proot.ProotExecutor
import nu.shel.wldroid.proot.RootfsEnvironment
import nu.shel.wldroid.ui.EnvironmentCreator
import nu.shel.wldroid.ui.EnvironmentPicker

@HiltViewModel
class EnvironmentViewModel @Inject constructor(
    val environmentRegistry: EnvironmentRegistry,
    private val prootExecutor: ProotExecutor,
) : ViewModel() {
    val environments get() = environmentRegistry.environments

    private val _shellOutput = MutableStateFlow("")
    val shellOutput: StateFlow<String> = _shellOutput

    fun createEnvironment(name: String, distro: DistroTemplate) {
        environmentRegistry.create(
            EnvironmentConfig(name = name, distro = distro),
        )
    }

    fun deleteEnvironment(id: String) {
        viewModelScope.launch {
            environmentRegistry.delete(id)
        }
    }

    suspend fun launchShell(env: RootfsEnvironment) {
        _shellOutput.value = "Running 'uname -a' in ${env.name}...\n"
        val exitCode = prootExecutor.runInProot(
            environment = env,
            command = listOf("uname", "-a"),
            onOutput = { line -> _shellOutput.value += "$line\n" },
        )
        _shellOutput.value += "\nExit code: $exitCode"
    }
}

@Composable
fun EnvironmentScreen(
    viewModel: EnvironmentViewModel = hiltViewModel(),
) {
    val environments by viewModel.environments.collectAsState()
    val shellOutput by viewModel.shellOutput.collectAsState()
    var showCreator by remember { mutableStateOf(false) }
    var selectedEnv by remember { mutableStateOf<RootfsEnvironment?>(null) }
    var deleteTarget by remember { mutableStateOf<RootfsEnvironment?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        // Environment list & create button via :ui EnvironmentPicker
        EnvironmentPicker(
            environments = environments,
            selectedId = selectedEnv?.id,
            onSelect = { env -> selectedEnv = env },
            onCreate = { showCreator = true },
            onDelete = { id -> deleteTarget = environments.find { it.id == id } },
            modifier = Modifier.weight(1f),
        )

        // Shell output
        if (shellOutput.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Shell Output", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = shellOutput,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
            }
        }
    }

    // Create environment wizard via :ui EnvironmentCreator (bottom sheet)
    if (showCreator) {
        EnvironmentCreator(
            availableDistros = viewModel.environmentRegistry.availableDistros(),
            onConfirm = { config ->
                showCreator = false
                viewModel.createEnvironment(config.name, config.distro)
            },
            onDismiss = { showCreator = false },
        )
    }

    // Delete confirmation
    deleteTarget?.let { env ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete Environment") },
            text = { Text("Delete \"${env.name}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteEnvironment(env.id)
                    deleteTarget = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}
