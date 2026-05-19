package nu.shel.wldroid.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import nu.shel.wldroid.ui.theme.WldroidTheme

/** Labels for each setup step in display order. */
private val STEP_LABELS = listOf(
    "Downloading environment",
    "Extracting filesystem",
    "Installing packages",
    "Launching desktop",
)

/**
 * Full-screen first-run setup experience with a vertical stepper showing
 * progress through download, extraction, installation, and launch phases.
 *
 * Renders all phases as a vertical step list inside a card, with the active
 * step expanded to show progress details. Use [SetupOverlay] instead for a
 * compact overlay shown during subsequent (non-first-run) setup triggers.
 *
 * @param state Current setup phase.
 * @param modifier Modifier for the root container.
 * @param onCancel Callback for the cancel button during active operations (null hides it).
 * @param onRetry Callback for the retry button on error (null hides the button).
 */
@Composable
fun SetupScreen(
    state: SetupState,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    // Track which step index was last active so errors highlight the correct row.
    var lastActiveIndex by remember { mutableIntStateOf(0) }
    val activeIndex = stateToActiveIndex(state)
    if (activeIndex >= 0) {
        lastActiveIndex = activeIndex
    }

    val steps = deriveSteps(state, lastActiveIndex)

    Box(
        modifier = modifier
            .fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 400.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(64.dp))

            // Header icon — penguin emoji as placeholder (no custom drawable).
            Text(
                text = "🐧",
                style = MaterialTheme.typography.displayMedium,
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Setting up your Linux desktop",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "This usually takes 2–5 minutes on a good connection.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Stepper card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    steps.forEachIndexed { index, step ->
                        StepRow(step)
                        if (index < steps.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 56.dp, end = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action buttons
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onCancel != null && state.isActive) {
                    OutlinedButton(onClick = onCancel) {
                        Text("Cancel Setup")
                    }
                }
                if (onRetry != null && state is SetupState.Error && state.canRetry) {
                    Button(onClick = onRetry) {
                        Text("Retry")
                    }
                }
                if (onCancel != null && state is SetupState.Error) {
                    OutlinedButton(onClick = onCancel) {
                        Text("Cancel")
                    }
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Step derivation
// ---------------------------------------------------------------------------

/** Returns the step index for the given active [state], or -1 when inactive. */
private fun stateToActiveIndex(state: SetupState): Int = when (state) {
    is SetupState.Downloading -> 0
    is SetupState.Extracting -> 1
    is SetupState.Installing -> 2
    is SetupState.Launching -> 3
    else -> -1
}

/**
 * Derives the visual step list from the current [state].
 * [lastActiveIndex] is used when the state is [SetupState.Error] to mark the
 * failed step.
 */
private fun deriveSteps(state: SetupState, lastActiveIndex: Int): List<SetupStep> {
    val activeIdx = stateToActiveIndex(state)

    return STEP_LABELS.mapIndexed { index, label ->
        when {
            // ---------- Error state ----------
            state is SetupState.Error -> when {
                index < lastActiveIndex -> SetupStep(label, StepStatus.COMPLETED)
                index == lastActiveIndex -> SetupStep(
                    label = label,
                    status = StepStatus.ERROR,
                    detail = state.message,
                )
                else -> SetupStep(label, StepStatus.PENDING)
            }

            // ---------- Running / completed ----------
            state is SetupState.Running -> SetupStep(label, StepStatus.COMPLETED)

            // ---------- Idle (all pending) ----------
            state is SetupState.Idle -> SetupStep(label, StepStatus.PENDING)

            // ---------- Active states ----------
            index < activeIdx -> SetupStep(label, StepStatus.COMPLETED)

            index == activeIdx -> {
                val (progress, detail) = extractProgressAndDetail(state)
                SetupStep(
                    label = label,
                    status = StepStatus.ACTIVE,
                    progress = progress,
                    detail = detail,
                )
            }

            else -> SetupStep(label, StepStatus.PENDING)
        }
    }
}

/** Extracts the progress float and detail message from active [SetupState] variants. */
private fun extractProgressAndDetail(state: SetupState): Pair<Float, String> = when (state) {
    is SetupState.Downloading -> state.progress to state.message
    is SetupState.Extracting -> state.progress to state.message
    is SetupState.Installing -> -1f to state.message
    is SetupState.Launching -> -1f to state.message
    else -> -1f to ""
}

// ---------------------------------------------------------------------------
// Step row composable
// ---------------------------------------------------------------------------

@Composable
private fun StepRow(step: SetupStep) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Status icon (24×24)
        Box(
            modifier = Modifier.size(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(targetState = step.status, label = "step_icon") { status ->
                when (status) {
                    StepStatus.COMPLETED -> CompletedIcon()
                    StepStatus.ACTIVE -> CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                    )
                    StepStatus.PENDING -> PendingIcon()
                    StepStatus.ERROR -> ErrorIcon()
                }
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Label + expandable detail
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = step.label,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (step.status == StepStatus.ACTIVE) FontWeight.SemiBold
                    else FontWeight.Normal,
                ),
                color = when (step.status) {
                    StepStatus.ACTIVE -> MaterialTheme.colorScheme.primary
                    StepStatus.COMPLETED -> MaterialTheme.colorScheme.onSurface
                    StepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                    StepStatus.ERROR -> MaterialTheme.colorScheme.error
                },
            )

            AnimatedVisibility(
                visible = step.status == StepStatus.ACTIVE || step.status == StepStatus.ERROR,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column {
                    if (step.detail.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = step.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (step.status == StepStatus.ERROR) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }

                    if (step.status == StepStatus.ACTIVE) {
                        Spacer(modifier = Modifier.height(8.dp))
                        if (step.progress in 0f..1f) {
                            LinearProgressIndicator(
                                progress = { step.progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Custom-drawn status icons (avoids material-icons-extended dependency)
// ---------------------------------------------------------------------------

/** Green/primary checkmark drawn on a small canvas. */
@Composable
private fun CompletedIcon() {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        // Circle background
        drawCircle(color = color, radius = w / 2f)
        // Checkmark (white)
        val stroke = Stroke(width = w * 0.12f, cap = StrokeCap.Round)
        drawLine(
            color = Color.White,
            start = Offset(w * 0.28f, h * 0.50f),
            end = Offset(w * 0.45f, h * 0.67f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = Color.White,
            start = Offset(w * 0.45f, h * 0.67f),
            end = Offset(w * 0.72f, h * 0.35f),
            strokeWidth = stroke.width,
            cap = StrokeCap.Round,
        )
    }
}

/** Hollow circle for pending steps. */
@Composable
private fun PendingIcon() {
    val color = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = Modifier.size(22.dp)) {
        drawCircle(
            color = color,
            radius = size.width / 2f - 1.dp.toPx(),
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

/** Red/error X icon drawn on a small canvas. */
@Composable
private fun ErrorIcon() {
    val color = MaterialTheme.colorScheme.error
    Canvas(modifier = Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        drawCircle(color = color, radius = w / 2f)
        val pad = w * 0.30f
        val sw = w * 0.12f
        drawLine(Color.White, Offset(pad, pad), Offset(w - pad, h - pad), sw, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(w - pad, pad), Offset(pad, h - pad), sw, cap = StrokeCap.Round)
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(name = "Downloading", showBackground = true)
@Composable
private fun PreviewDownloading() {
    WldroidTheme {
        SetupScreen(state = SetupState.Downloading(0.72f, "48.2 MB of 67 MB"))
    }
}

@Preview(name = "Extracting", showBackground = true)
@Composable
private fun PreviewExtracting() {
    WldroidTheme {
        SetupScreen(state = SetupState.Extracting(0.45f, "Extracting files…"))
    }
}

@Preview(name = "Installing", showBackground = true)
@Composable
private fun PreviewInstalling() {
    WldroidTheme {
        SetupScreen(state = SetupState.Installing("Installing xfce4…"))
    }
}

@Preview(name = "Launching", showBackground = true)
@Composable
private fun PreviewLaunching() {
    WldroidTheme {
        SetupScreen(state = SetupState.Launching("Starting compositor…"))
    }
}

@Preview(name = "Error", showBackground = true)
@Composable
private fun PreviewError() {
    WldroidTheme {
        SetupScreen(
            state = SetupState.Error("Network timeout", canRetry = true),
            onRetry = {},
            onCancel = {},
        )
    }
}
