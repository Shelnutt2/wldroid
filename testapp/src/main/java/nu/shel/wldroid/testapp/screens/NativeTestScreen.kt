package nu.shel.wldroid.testapp.screens

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class NativeTestViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    data class NativeTestState(
        val running: Boolean = false,
        val nativeLibDir: String = "",
        val availableLibraries: List<String> = emptyList(),
        val testOutput: String = "",
        val testExitCode: Int? = null,
    )

    private val _state = MutableStateFlow(NativeTestState())
    val state: StateFlow<NativeTestState> = _state

    init {
        scanNativeLibraries()
    }

    private fun scanNativeLibraries() {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val dir = File(nativeLibDir)
        val libs = dir.listFiles()
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
        _state.value = _state.value.copy(
            nativeLibDir = nativeLibDir,
            availableLibraries = libs,
        )
    }

    fun runNativeTests() {
        viewModelScope.launch {
            _state.value = _state.value.copy(running = true, testOutput = "", testExitCode = null)
            withContext(Dispatchers.IO) {
                try {
                    // Look for a test runner binary in native libs
                    val nativeDir = context.applicationInfo.nativeLibraryDir
                    val testBinary = File(nativeDir, "libwldroid-test-runner.so")

                    if (!testBinary.exists()) {
                        _state.value = _state.value.copy(
                            running = false,
                            testOutput = "No native test runner found at:\n${testBinary.absolutePath}\n\n" +
                                "Native test binaries are not bundled in this build.\n" +
                                "Build with WLDROID_BUILD_TESTS=1 to include them.",
                            testExitCode = -1,
                        )
                        return@withContext
                    }

                    val process = ProcessBuilder(testBinary.absolutePath)
                        .directory(File(nativeDir))
                        .redirectErrorStream(true)
                        .start()

                    val output = StringBuilder()
                    process.inputStream.bufferedReader().forEachLine { line ->
                        output.appendLine(line)
                        _state.value = _state.value.copy(testOutput = output.toString())
                    }

                    val exitCode = process.waitFor()
                    _state.value = _state.value.copy(
                        running = false,
                        testOutput = output.toString(),
                        testExitCode = exitCode,
                    )
                } catch (e: Exception) {
                    _state.value = _state.value.copy(
                        running = false,
                        testOutput = "Error running tests:\n${e.message}\n\n${e.stackTraceToString()}",
                        testExitCode = -1,
                    )
                }
            }
        }
    }
}

@Composable
fun NativeTestScreen(
    viewModel: NativeTestViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        // Native libraries info
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Native Libraries", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Directory: ${state.nativeLibDir}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Found ${state.availableLibraries.size} libraries:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                state.availableLibraries.forEach { lib ->
                    val isTestLib = lib.contains("test", ignoreCase = true)
                    Text(
                        text = "  • $lib",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (isTestLib)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Test info
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Native Test Suite", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "The native test suite verifies wlroots compositor integration, " +
                        "VirGL rendering pipeline, and shim library functionality. " +
                        "Tests must be built with the native code (WLDROID_BUILD_TESTS=1).",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { viewModel.runNativeTests() },
                        enabled = !state.running,
                    ) {
                        Text("Run Native Tests")
                    }
                    if (state.running) {
                        CircularProgressIndicator()
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Test results
        if (state.testOutput.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (state.testExitCode) {
                        0 -> MaterialTheme.colorScheme.primaryContainer
                        null -> MaterialTheme.colorScheme.surfaceVariant
                        else -> MaterialTheme.colorScheme.errorContainer
                    },
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Test Results", style = MaterialTheme.typography.titleMedium)
                        state.testExitCode?.let { code ->
                            Text(
                                text = if (code == 0) "PASSED ✓" else "FAILED ✗ ($code)",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (code == 0)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = state.testOutput,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
