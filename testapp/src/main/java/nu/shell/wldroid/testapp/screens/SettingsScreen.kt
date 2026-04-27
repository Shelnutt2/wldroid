package nu.shell.wldroid.testapp.screens

import android.content.Context
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nu.shell.wldroid.proot.DistroTemplate
import nu.shell.wldroid.virgl.GpuMode
import nu.shell.wldroid.virgl.GpuModeStore

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val gpuModeStore: GpuModeStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val gpuModeOverride = gpuModeStore.getGpuModeOverride()

    private val _defaultDistro = MutableStateFlow(DistroTemplate.DEBIAN_TRIXIE)
    val defaultDistro: StateFlow<DistroTemplate> = _defaultDistro

    private val _debugLogging = MutableStateFlow(false)
    val debugLogging: StateFlow<Boolean> = _debugLogging

    private val _cacheInfo = MutableStateFlow(CacheInfo())
    val cacheInfo: StateFlow<CacheInfo> = _cacheInfo

    data class CacheInfo(
        val rootfsCacheSize: String = "Calculating...",
        val shimCacheSize: String = "Calculating...",
        val totalCacheSize: String = "Calculating...",
    )

    init {
        refreshCacheInfo()
    }

    fun setGpuMode(mode: GpuMode?) {
        viewModelScope.launch { gpuModeStore.setGpuModeOverride(mode) }
    }

    fun setDefaultDistro(distro: DistroTemplate) {
        _defaultDistro.value = distro
    }

    fun setDebugLogging(enabled: Boolean) {
        _debugLogging.value = enabled
    }

    fun refreshCacheInfo() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val rootfsDir = File(context.filesDir, "rootfs")
                val shimDir = File(context.cacheDir, "shims_test")
                val rootfsSize = dirSize(rootfsDir)
                val shimSize = dirSize(shimDir)
                _cacheInfo.value = CacheInfo(
                    rootfsCacheSize = formatBytes(rootfsSize),
                    shimCacheSize = formatBytes(shimSize),
                    totalCacheSize = formatBytes(rootfsSize + shimSize),
                )
            }
        }
    }

    fun clearRootfsCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                File(context.filesDir, "rootfs").deleteRecursively()
            }
            refreshCacheInfo()
        }
    }

    fun clearShimCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                File(context.cacheDir, "shims_test").deleteRecursively()
            }
            refreshCacheInfo()
        }
    }

    fun getVersionInfo(): Map<String, String> = mapOf(
        "App Version" to "0.1.0",
        "Build Type" to android.os.Build.TYPE,
        "Android SDK" to android.os.Build.VERSION.SDK_INT.toString(),
        "Device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
        "ABI" to android.os.Build.SUPPORTED_ABIS.joinToString(", "),
    )

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024.0 * 1024.0))} GB"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val gpuModeOverride by viewModel.gpuModeOverride.collectAsState(initial = null)
    val defaultDistro by viewModel.defaultDistro.collectAsState()
    val debugLogging by viewModel.debugLogging.collectAsState()
    val cacheInfo by viewModel.cacheInfo.collectAsState()
    val versionInfo = remember { viewModel.getVersionInfo() }

    var showClearRootfsDialog by remember { mutableStateOf(false) }
    var showClearShimsDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        // GPU mode preference
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("GPU Mode", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                var gpuExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = gpuExpanded,
                    onExpandedChange = { gpuExpanded = it },
                ) {
                    OutlinedTextField(
                        value = gpuModeOverride?.displayName ?: "Auto-detect",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("GPU Mode") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(gpuExpanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = gpuExpanded,
                        onDismissRequest = { gpuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Auto-detect") },
                            onClick = {
                                viewModel.setGpuMode(null)
                                gpuExpanded = false
                            },
                        )
                        GpuMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.displayName) },
                                onClick = {
                                    viewModel.setGpuMode(mode)
                                    gpuExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Default distro
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Default Distribution", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                var distroExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = distroExpanded,
                    onExpandedChange = { distroExpanded = it },
                ) {
                    OutlinedTextField(
                        value = "${defaultDistro.displayName} (${defaultDistro.version})",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Default Distro") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(distroExpanded) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = distroExpanded,
                        onDismissRequest = { distroExpanded = false },
                    ) {
                        DistroTemplate.entries.forEach { distro ->
                            DropdownMenuItem(
                                text = { Text("${distro.displayName} (${distro.version})") },
                                onClick = {
                                    viewModel.setDefaultDistro(distro)
                                    distroExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Cache management
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Cache Management", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Rootfs cache", style = MaterialTheme.typography.bodyMedium)
                    Text(cacheInfo.rootfsCacheSize, style = MaterialTheme.typography.bodyMedium)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Shim cache", style = MaterialTheme.typography.bodyMedium)
                    Text(cacheInfo.shimCacheSize, style = MaterialTheme.typography.bodyMedium)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "Total",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        cacheInfo.totalCacheSize,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showClearRootfsDialog = true }) {
                        Text("Clear Rootfs")
                    }
                    OutlinedButton(onClick = { showClearShimsDialog = true }) {
                        Text("Clear Shims")
                    }
                    OutlinedButton(onClick = { viewModel.refreshCacheInfo() }) {
                        Text("Refresh")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Debug logging
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Debug Logging", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Enable verbose logging for all WLDroid components",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = debugLogging,
                    onCheckedChange = { viewModel.setDebugLogging(it) },
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Version info
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Version Info", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                versionInfo.forEach { (key, value) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = key,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(text = value, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    // Confirmation dialogs
    if (showClearRootfsDialog) {
        AlertDialog(
            onDismissRequest = { showClearRootfsDialog = false },
            title = { Text("Clear Rootfs Cache") },
            text = { Text("Delete all rootfs environments? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearRootfsCache()
                    showClearRootfsDialog = false
                }) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearRootfsDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showClearShimsDialog) {
        AlertDialog(
            onDismissRequest = { showClearShimsDialog = false },
            title = { Text("Clear Shim Cache") },
            text = { Text("Delete extracted shim libraries?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearShimCache()
                    showClearShimsDialog = false
                }) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearShimsDialog = false }) { Text("Cancel") }
            },
        )
    }
}
