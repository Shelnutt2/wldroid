package nu.shell.wldroid.testapp.screens

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import nu.shell.wldroid.compositor.CompositorConfig
import nu.shell.wldroid.compositor.CompositorSession
import nu.shell.wldroid.launcher.DesktopAppPreset
import nu.shell.wldroid.launcher.DesktopLauncher
import nu.shell.wldroid.launcher.DesktopLauncherConfig
import nu.shell.wldroid.launcher.DesktopLauncherState
import nu.shell.wldroid.proot.EnvironmentRegistry
import nu.shell.wldroid.proot.ProotExecutor
import nu.shell.wldroid.proot.RootfsEnvironment
import nu.shell.wldroid.shims.ShimExtractor
import nu.shell.wldroid.ui.CompositorSurface
import nu.shell.wldroid.ui.CompositorSurfaceState
import nu.shell.wldroid.ui.InputMode
import nu.shell.wldroid.compositor.CompositorState
import nu.shell.wldroid.virgl.GpuMode
import nu.shell.wldroid.virgl.VirglSession

@HiltViewModel
class DesktopViewModel @Inject constructor(
    val compositorConfig: CompositorConfig,
    val environmentRegistry: EnvironmentRegistry,
    private val virglSession: VirglSession,
    private val shimExtractor: ShimExtractor,
    private val prootExecutor: ProotExecutor,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val compositorSession = CompositorSession(compositorConfig)

    private val launcherConfig = DesktopLauncherConfig(
        shimExtractDir = File(context.cacheDir, "shims").absolutePath,
        waylandRuntimeDir = File(context.cacheDir, "wayland-runtime").absolutePath,
        tempDir = File(context.cacheDir, "proot-tmp").absolutePath,
    )

    val launcher = DesktopLauncher(
        context = context,
        compositorSession = compositorSession,
        virglSession = virglSession,
        shimExtractor = shimExtractor,
        prootExecutor = prootExecutor,
        config = launcherConfig,
    )

    val environments get() = environmentRegistry.environments
    val launcherState get() = launcher.state
    val processOutput get() = launcher.processOutput
    val gpuMode get() = launcher.gpuMode
    val compositorState get() = compositorSession.state

    init {
        // Surface compositor errors into the launcher's process output stream.
        viewModelScope.launch {
            compositorSession.state.collect { state ->
                if (state == CompositorState.ERROR) {
                    launcher.emitOutput("✗ Compositor entered ERROR state")
                }
            }
        }
    }

    fun launch(env: RootfsEnvironment, preset: DesktopAppPreset) {
        launcher.launchPreset(env, preset, viewModelScope)
    }

    fun launchCustom(env: RootfsEnvironment, command: String) {
        launcher.launch(env, command.split(" "), scope = viewModelScope)
    }

    fun stop() {
        viewModelScope.launch { launcher.stop() }
    }

    override fun onCleared() {
        super.onCleared()
        runBlocking(Dispatchers.IO) { launcher.stop() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopScreen(
    viewModel: DesktopViewModel = hiltViewModel(),
) {
    val environments by viewModel.environments.collectAsState()
    val launcherState by viewModel.launcherState.collectAsState()
    val gpuMode by viewModel.gpuMode.collectAsState()
    val compositorState by viewModel.compositorState.collectAsState()
    val logLines = remember { mutableStateListOf<String>() }
    val logListState = rememberLazyListState()

    var selectedEnv by remember { mutableStateOf<RootfsEnvironment?>(null) }
    var selectedPreset by remember { mutableStateOf<DesktopAppPreset?>(null) }
    var isCustom by rememberSaveable { mutableStateOf(false) }
    var customCommand by rememberSaveable { mutableStateOf("") }
    var envDropdownExpanded by remember { mutableStateOf(false) }
    var showKeyboard by remember { mutableStateOf(false) }
    val view = LocalView.current

    // Auto-select first environment when available.
    LaunchedEffect(environments) {
        if (selectedEnv == null && environments.isNotEmpty()) {
            selectedEnv = environments.first()
        }
    }

    // Collect log output.
    LaunchedEffect(Unit) {
        viewModel.processOutput.collect { line ->
            logLines.add(line)
            // Keep last 500 lines.
            if (logLines.size > 500) logLines.removeAt(0)
        }
    }

    // Auto-scroll log to bottom when new lines arrive.
    LaunchedEffect(logListState) {
        snapshotFlow { logLines.size }
            .collect { size ->
                if (size > 0) {
                    logListState.animateScrollToItem(size - 1)
                }
            }
    }

    val surfaceState = remember(viewModel.compositorConfig, viewModel.compositorSession) {
        CompositorSurfaceState(viewModel.compositorSession, viewModel.compositorConfig)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Compositor surface
        CompositorSurface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.55f),
            config = viewModel.compositorConfig,
            surfaceState = surfaceState,
            inputMode = InputMode.TOUCH_AND_KEYBOARD,
            showKeyboardFab = true,
        )

        // Control panel
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.45f)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Environment dropdown
            ExposedDropdownMenuBox(
                expanded = envDropdownExpanded,
                onExpandedChange = { envDropdownExpanded = it },
            ) {
                OutlinedTextField(
                    value = selectedEnv?.name ?: "No environments",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Environment") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = envDropdownExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(
                    expanded = envDropdownExpanded,
                    onDismissRequest = { envDropdownExpanded = false },
                ) {
                    environments.forEach { env ->
                        DropdownMenuItem(
                            text = { Text("${env.name} (${env.distro})") },
                            onClick = {
                                selectedEnv = env
                                envDropdownExpanded = false
                            },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Status row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SuggestionChip(
                    onClick = {},
                    label = { Text(launcherState.displayName()) },
                )
                SuggestionChip(
                    onClick = {},
                    label = { Text("WC: ${compositorState.name}") },
                )
                SuggestionChip(
                    onClick = {},
                    label = { Text("GPU: ${gpuMode.name}") },
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // App preset buttons
            Text("App Presets", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DesktopAppPreset.ALL.forEach { preset ->
                    FilterChip(
                        selected = !isCustom && selectedPreset == preset,
                        onClick = {
                            selectedPreset = preset
                            isCustom = false
                        },
                        label = { Text("${preset.icon} ${preset.displayName}") },
                    )
                }
                FilterChip(
                    selected = isCustom,
                    onClick = { isCustom = true },
                    label = { Text("✏ Custom…") },
                )
            }

            // GPU compatibility warning for selected preset.
            if (!isCustom && selectedPreset != null && !selectedPreset!!.isCompatibleWith(gpuMode)) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "⚠ ${selectedPreset!!.displayName} requires " +
                        "${selectedPreset!!.supportedGpuModes!!.joinToString(" or ") { it.displayName }}. " +
                        "Current mode (${ gpuMode.displayName }) is not supported — the app will likely fail.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Custom command field
            if (isCustom) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = customCommand,
                    onValueChange = { customCommand = it },
                    label = { Text("Custom command") },
                    placeholder = { Text("e.g. weston-terminal") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Launch / Stop button + Keyboard toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (launcherState.isActive) {
                    Button(
                        onClick = { viewModel.stop() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Stop")
                    }
                } else {
                    Button(
                        onClick = {
                            val env = selectedEnv ?: return@Button
                            if (isCustom && customCommand.isNotBlank()) {
                                viewModel.launchCustom(env, customCommand)
                            } else {
                                val preset = selectedPreset ?: return@Button
                                viewModel.launch(env, preset)
                            }
                        },
                        enabled = selectedEnv != null &&
                            ((!isCustom && selectedPreset != null) || (isCustom && customCommand.isNotBlank())),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Launch")
                    }
                }

                // Keyboard toggle button
                IconButton(
                    onClick = {
                        showKeyboard = !showKeyboard
                        val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE)
                            as InputMethodManager
                        if (showKeyboard) {
                            // Find the compositor's SurfaceView (the focused view in the hierarchy)
                            val focusedView = view.rootView.findFocus() ?: view
                            focusedView.requestFocus()
                            imm.showSoftInput(focusedView, InputMethodManager.SHOW_IMPLICIT)
                        } else {
                            imm.hideSoftInputFromWindow(view.windowToken, 0)
                        }
                    },
                ) {
                    Icon(
                        imageVector = if (showKeyboard) Icons.Default.KeyboardHide
                            else Icons.Default.Keyboard,
                        contentDescription = if (showKeyboard) "Hide keyboard" else "Show keyboard",
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Log panel
            Text("Output Log", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
            ) {
                LazyColumn(
                    state = logListState,
                    modifier = Modifier.padding(8.dp),
                ) {
                    items(logLines.toList()) { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** User-friendly display name for a [DesktopLauncherState]. */
private fun DesktopLauncherState.displayName(): String = when (this) {
    is DesktopLauncherState.Idle -> "Idle"
    is DesktopLauncherState.StartingCompositor -> "Starting Compositor…"
    is DesktopLauncherState.DetectingGpu -> "Detecting GPU…"
    is DesktopLauncherState.SetupGpu -> "Setting up GPU…"
    is DesktopLauncherState.StartingVirgl -> "Starting VirGL…"
    is DesktopLauncherState.ExtractingShims -> "Extracting Shims…"
    is DesktopLauncherState.InstallingPackages -> "Installing Packages…"
    is DesktopLauncherState.LaunchingApp -> "Launching App…"
    is DesktopLauncherState.Running -> "Running"
    is DesktopLauncherState.Stopping -> "Stopping…"
    is DesktopLauncherState.Error -> "Error: $message"
}
