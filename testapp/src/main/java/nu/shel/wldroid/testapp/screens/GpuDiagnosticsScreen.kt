package nu.shel.wldroid.testapp.screens

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import nu.shel.wldroid.virgl.GpuCapabilityDetector
import nu.shel.wldroid.virgl.GpuMode
import nu.shel.wldroid.virgl.GpuModeStore
import nu.shel.wldroid.virgl.VirglSession
import nu.shel.wldroid.virgl.VirglState
import nu.shel.wldroid.ui.GpuModeSelector

@HiltViewModel
class GpuDiagnosticsViewModel @Inject constructor(
    val gpuDetector: GpuCapabilityDetector,
    private val gpuModeStore: GpuModeStore,
    val virglSession: VirglSession,
) : ViewModel() {
    val virglState get() = virglSession.state
    val detectedMode get() = virglSession.detectedGpuMode
    val gpuModeOverride = gpuModeStore.getGpuModeOverride()

    private val _testResults = MutableStateFlow<Map<GpuMode, String>>(emptyMap())
    val testResults: StateFlow<Map<GpuMode, String>> = _testResults

    fun detectGpu(): String = gpuDetector.getGpuSummary()
    fun getGpuInfo(): String = gpuDetector.getGpuInfo()
    fun isAdrenoGpu(): Boolean = gpuDetector.isAdrenoGpu()
    fun hasVulkan(): Boolean = gpuDetector.hasVulkanSupport()
    fun isKgslAccessible(): Boolean = gpuDetector.isKgslAccessible()

    fun setGpuModeOverride(mode: GpuMode?) {
        viewModelScope.launch { gpuModeStore.setGpuModeOverride(mode) }
    }

    fun testGpuMode(mode: GpuMode) {
        _testResults.value = _testResults.value + (mode to "Testing...")
        viewModelScope.launch {
            try {
                if (mode.requiresVirglServer && virglSession.state.value != VirglState.RUNNING) {
                    _testResults.value = _testResults.value +
                        (mode to "Requires VirGL server (start server first)")
                } else {
                    _testResults.value = _testResults.value + (mode to "✓ Available")
                }
            } catch (e: Exception) {
                _testResults.value = _testResults.value +
                    (mode to "✗ Error: ${e.message}")
            }
        }
    }

    suspend fun startVirglServer() { virglSession.start() }
    suspend fun stopVirglServer() { virglSession.stop() }
}

@Composable
fun GpuDiagnosticsScreen(
    viewModel: GpuDiagnosticsViewModel = hiltViewModel(),
) {
    val virglState by viewModel.virglState.collectAsState()
    val detectedMode by viewModel.detectedMode.collectAsState()
    val testResults by viewModel.testResults.collectAsState()
    val gpuModeOverride by viewModel.gpuModeOverride.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    val gpuSummary = remember { viewModel.detectGpu() }
    val gpuInfo = remember { viewModel.getGpuInfo() }
    val isAdreno = remember { viewModel.isAdrenoGpu() }
    val hasVulkan = remember { viewModel.hasVulkan() }
    val kgslAccessible = remember { viewModel.isKgslAccessible() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        // GPU info
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Device GPU Info", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                InfoRow("Summary", gpuSummary)
                InfoRow("Details", gpuInfo)
                InfoRow("Adreno GPU", if (isAdreno) "Yes" else "No")
                InfoRow("Vulkan Support", if (hasVulkan) "Yes" else "No")
                InfoRow("KGSL Accessible", if (kgslAccessible) "Yes" else "No")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Detection result
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Auto-Detection Result", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Detected: ${detectedMode.displayName}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = detectedMode.description,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // GPU mode selector from :ui
        GpuModeSelector(
            currentMode = gpuModeOverride ?: detectedMode,
            availableModes = GpuMode.entries,
            onModeSelected = { mode -> viewModel.setGpuModeOverride(mode) },
            modifier = Modifier.fillMaxWidth(),
        )

        // Test buttons and results for each mode
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Mode Testing", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                GpuMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(mode.displayName, style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(
                            onClick = { viewModel.testGpuMode(mode) },
                        ) {
                            Text("Test")
                        }
                    }
                    testResults[mode]?.let { result ->
                        Text(
                            text = "  → $result",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (result.startsWith("✓"))
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = { viewModel.setGpuModeOverride(null) }) {
                    Text("Clear Override (use auto-detect)")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // VirGL server status
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when (virglState) {
                    VirglState.RUNNING -> MaterialTheme.colorScheme.primaryContainer
                    VirglState.ERROR, VirglState.UNHEALTHY ->
                        MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("VirGL Server", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                InfoRow("State", virglState.name)
                InfoRow("Healthy", if (viewModel.virglSession.isHealthy()) "Yes" else "No")
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { scope.launch { viewModel.startVirglServer() } },
                        enabled = virglState == VirglState.IDLE ||
                            virglState == VirglState.STOPPED,
                    ) {
                        Text("Start")
                    }
                    OutlinedButton(
                        onClick = { scope.launch { viewModel.stopVirglServer() } },
                        enabled = virglState == VirglState.RUNNING,
                    ) {
                        Text("Stop")
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
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
            modifier = Modifier.weight(0.3f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.7f),
        )
    }
}
