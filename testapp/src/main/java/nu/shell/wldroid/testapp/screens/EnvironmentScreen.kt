package nu.shell.wldroid.testapp.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import nu.shell.wldroid.proot.DistroTemplate
import nu.shell.wldroid.proot.EnvironmentConfig
import nu.shell.wldroid.proot.EnvironmentRegistry
import nu.shell.wldroid.proot.ProotExecutor
import nu.shell.wldroid.proot.RootfsEnvironment
import nu.shell.wldroid.proot.RootfsStatus

@HiltViewModel
class EnvironmentViewModel @Inject constructor(
    val environmentRegistry: EnvironmentRegistry,
    private val prootExecutor: ProotExecutor,
) : ViewModel() {
    val environments get() = environmentRegistry.environments

    private val _shellOutput = MutableStateFlow("")
    val shellOutput: StateFlow<String> = _shellOutput

    suspend fun createEnvironment(name: String, distro: DistroTemplate) {
        environmentRegistry.create(
            EnvironmentConfig(name = name, distro = distro),
        )
    }

    suspend fun deleteEnvironment(id: String) {
        environmentRegistry.delete(id)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnvironmentScreen(
    viewModel: EnvironmentViewModel = hiltViewModel(),
) {
    val environments by viewModel.environments.collectAsState()
    val shellOutput by viewModel.shellOutput.collectAsState()
    val scope = rememberCoroutineScope()
    var showCreateDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<RootfsEnvironment?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        // Header with create button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Proot Environments (${environments.size})",
                style = MaterialTheme.typography.titleMedium,
            )
            Button(onClick = { showCreateDialog = true }) {
                Text("Create New")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Available distros
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Available Distros", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(4.dp))
                DistroTemplate.entries.forEach { distro ->
                    Text(
                        text = "${distro.displayName} (${distro.version})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Environment list
        if (environments.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("No environments yet", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Create an environment to get started.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(environments, key = { it.id }) { env ->
                EnvironmentCard(
                    env = env,
                    onDelete = { deleteTarget = env },
                    onLaunchShell = { scope.launch { viewModel.launchShell(env) } },
                )
            }
        }

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

    // Create dialog
    if (showCreateDialog) {
        CreateEnvironmentDialog(
            distros = viewModel.environmentRegistry.availableDistros(),
            onDismiss = { showCreateDialog = false },
            onCreate = { name, distro ->
                showCreateDialog = false
                scope.launch { viewModel.createEnvironment(name, distro) }
            },
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
                    scope.launch { viewModel.deleteEnvironment(env.id) }
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

@Composable
private fun EnvironmentCard(
    env: RootfsEnvironment,
    onDelete: () -> Unit,
    onLaunchShell: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(env.name, style = MaterialTheme.typography.titleSmall)
                StatusBadge(env.status)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Distro: ${env.distro.ifEmpty { "Unknown" }}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Size: ${formatBytes(env.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Path: ${env.rootfsPath}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onLaunchShell,
                    enabled = env.status == RootfsStatus.READY,
                ) {
                    Text("Shell")
                }
                OutlinedButton(onClick = onDelete) {
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: RootfsStatus) {
    val color = when (status) {
        RootfsStatus.READY -> MaterialTheme.colorScheme.primary
        RootfsStatus.ERROR -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.tertiary
    }
    Text(
        text = status.name,
        style = MaterialTheme.typography.labelSmall,
        color = color,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateEnvironmentDialog(
    distros: List<DistroTemplate>,
    onDismiss: () -> Unit,
    onCreate: (String, DistroTemplate) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var selectedDistro by remember { mutableStateOf(distros.firstOrNull() ?: DistroTemplate.DEBIAN_TRIXIE) }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Environment") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Environment Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                ) {
                    OutlinedTextField(
                        value = selectedDistro.displayName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Distribution") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                    ) {
                        distros.forEach { distro ->
                            DropdownMenuItem(
                                text = { Text("${distro.displayName} (${distro.version})") },
                                onClick = {
                                    selectedDistro = distro
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.ifBlank { selectedDistro.displayName }, selectedDistro) },
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
}
