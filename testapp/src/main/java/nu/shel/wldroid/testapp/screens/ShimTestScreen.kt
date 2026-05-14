package nu.shel.wldroid.testapp.screens

import android.content.Context
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import nu.shel.wldroid.shims.ShimConfig
import nu.shel.wldroid.shims.ShimExtractor
import nu.shel.wldroid.ui.SetupOverlay
import nu.shel.wldroid.ui.SetupState
import nu.shel.wldroid.virgl.GpuMode

@HiltViewModel
class ShimTestViewModel @Inject constructor(
    private val shimExtractor: ShimExtractor,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    data class ShimInfo(
        val name: String,
        val path: String,
        val exists: Boolean,
        val sizeBytes: Long,
        val readable: Boolean,
    )

    data class ShimTestState(
        val extracted: Boolean = false,
        val extracting: Boolean = false,
        val shimInfos: List<ShimInfo> = emptyList(),
        val ldPreloadStrings: Map<String, String> = emptyMap(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(ShimTestState())
    val state: StateFlow<ShimTestState> = _state

    private val shimDir: String
        get() = File(context.cacheDir, "shims_test").absolutePath

    fun checkExtracted() {
        val isExtracted = shimExtractor.isExtracted(shimDir)
        _state.value = _state.value.copy(extracted = isExtracted)
        if (isExtracted) {
            verifyShims()
        }
    }

    fun extractShims() {
        viewModelScope.launch {
            _state.value = _state.value.copy(extracting = true, error = null)
            try {
                val shimSet = shimExtractor.extractAll(shimDir)
                _state.value = _state.value.copy(
                    extracting = false,
                    extracted = true,
                )
                verifyShims()
                buildLdPreloadStrings(shimSet)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    extracting = false,
                    error = "Extraction failed: ${e.message}",
                )
            }
        }
    }

    private fun verifyShims() {
        val dir = File(shimDir)
        if (!dir.exists()) return

        val files = dir.listFiles()?.toList() ?: emptyList()
        val infos = files.map { file ->
            ShimInfo(
                name = file.name,
                path = file.absolutePath,
                exists = file.exists(),
                sizeBytes = file.length(),
                readable = file.canRead(),
            )
        }
        _state.value = _state.value.copy(shimInfos = infos)
    }

    private fun buildLdPreloadStrings(shimSet: ShimExtractor.ShimSet) {
        val modes = GpuMode.entries.filter { it != GpuMode.AUTO }
        val ldStrings = modes.associate { mode ->
            mode.name to shimExtractor.getLdPreloadString(shimSet, mode.name)
        }
        _state.value = _state.value.copy(ldPreloadStrings = ldStrings)
    }

    fun clearShims() {
        File(shimDir).deleteRecursively()
        _state.value = ShimTestState()
    }

    /** Get the shim configuration for each GPU mode. */
    fun getShimConfigs(): Map<String, ShimConfig> =
        GpuMode.entries.filter { it != GpuMode.AUTO }.associate { mode ->
            mode.name to ShimConfig.forGpuMode(mode.name)
        }
}

@Composable
fun ShimTestScreen(
    viewModel: ShimTestViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val shimConfigs = viewModel.getShimConfigs()

    // Check status on first composition
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.checkExtracted()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        // Shim config per GPU mode
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Shim Configurations", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                shimConfigs.forEach { (mode, config) ->
                    Text(
                        text = mode,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)) {
                        Text("DRM: ${if (config.enableDrmShim) "✓" else "✗"}", style = MaterialTheme.typography.bodySmall)
                        Text("GBM: ${if (config.enableGbmShim) "✓" else "✗"}", style = MaterialTheme.typography.bodySmall)
                        Text("EGL: ${if (config.enableEglOverride) "✓" else "✗"}", style = MaterialTheme.typography.bodySmall)
                        Text("Netstub: ${if (config.enableNetstub) "✓" else "✗"}", style = MaterialTheme.typography.bodySmall)
                        Text("DRM Wrapper: ${if (config.enableDrmWrapper) "✓" else "✗"}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Extraction controls
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (state.extracted)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Shim Extraction", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (state.extracted) "Shims extracted ✓" else "Shims not extracted",
                    style = MaterialTheme.typography.bodyMedium,
                )

                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.extractShims() },
                        enabled = !state.extracting,
                    ) {
                        Text(if (state.extracted) "Re-extract" else "Extract Shims")
                    }
                    if (state.extracted) {
                        OutlinedButton(onClick = { viewModel.clearShims() }) {
                            Text("Clear")
                        }
                    }
                }
            }
        }

        // SetupOverlay from :ui — shows progress/error overlay during extraction
        val setupState = when {
            state.extracting -> SetupState.Installing("Extracting shim libraries…")
            state.error != null -> SetupState.Error(
                message = state.error!!,
                canRetry = true,
            )
            else -> SetupState.Idle
        }
        SetupOverlay(
            state = setupState,
            onRetry = { viewModel.extractShims() },
            onCancel = { viewModel.clearShims() },
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Extracted shim files
        if (state.shimInfos.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Extracted Libraries", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    state.shimInfos.forEach { info ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(info.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = "${formatShimBytes(info.sizeBytes)} • ${if (info.readable) "readable" else "NOT readable"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                text = if (info.exists && info.readable) "✓" else "✗",
                                color = if (info.exists && info.readable)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // LD_PRELOAD strings
        if (state.ldPreloadStrings.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("LD_PRELOAD Strings", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    state.ldPreloadStrings.forEach { (mode, ldString) ->
                        Text(
                            text = mode,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = ldString.ifEmpty { "(none)" },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .padding(start = 8.dp, bottom = 8.dp)
                                .horizontalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }
}

private fun formatShimBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}
