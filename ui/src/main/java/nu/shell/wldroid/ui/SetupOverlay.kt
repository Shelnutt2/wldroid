package nu.shell.wldroid.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Overlay displayed during environment setup that shows progress through
 * download, extraction, installation, and launch phases.
 *
 * Returns without rendering when state is [SetupState.Idle] or [SetupState.Running].
 *
 * @param state Current setup phase.
 * @param modifier Modifier for the overlay container.
 * @param onRetry Callback for the retry button on error (null hides the button).
 * @param onCancel Callback for the cancel button during active operations (null hides it).
 */
@Composable
fun SetupOverlay(
    state: SetupState,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
) {
    // Don't show overlay for idle or running states
    val visible = state !is SetupState.Idle && state !is SetupState.Running

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .padding(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    when (state) {
                        is SetupState.Downloading -> DownloadingContent(state, onCancel)
                        is SetupState.Extracting -> ExtractingContent(state, onCancel)
                        is SetupState.Installing -> InstallingContent(state, onCancel)
                        is SetupState.Launching -> LaunchingContent(state)
                        is SetupState.Error -> ErrorContent(state, onRetry, onCancel)
                        // Idle and Running don't render (handled by AnimatedVisibility)
                        else -> {}
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadingContent(
    state: SetupState.Downloading,
    onCancel: (() -> Unit)?,
) {
    if (state.progress >= 0f) {
        CircularProgressIndicator(
            progress = { state.progress },
            modifier = Modifier.size(64.dp),
            strokeWidth = 6.dp,
        )
    } else {
        CircularProgressIndicator(
            modifier = Modifier.size(64.dp),
            strokeWidth = 6.dp,
        )
    }

    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "Downloading Linux environment…",
        style = MaterialTheme.typography.titleMedium,
    )

    if (state.message.isNotBlank()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = state.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (state.progress >= 0f) {
        Spacer(modifier = Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${(state.progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    CancelButton(onCancel)
}

@Composable
private fun ExtractingContent(
    state: SetupState.Extracting,
    onCancel: (() -> Unit)?,
) {
    if (state.progress >= 0f) {
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "Setting up environment…",
        style = MaterialTheme.typography.titleMedium,
    )

    if (state.message.isNotBlank()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = state.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (state.progress >= 0f) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${(state.progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    CancelButton(onCancel)
}

@Composable
private fun InstallingContent(
    state: SetupState.Installing,
    onCancel: (() -> Unit)?,
) {
    CircularProgressIndicator(
        modifier = Modifier.size(64.dp),
        strokeWidth = 6.dp,
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = if (state.message.isNotBlank()) state.message else "Installing…",
        style = MaterialTheme.typography.titleMedium,
    )

    CancelButton(onCancel)
}

@Composable
private fun LaunchingContent(state: SetupState.Launching) {
    CircularProgressIndicator(
        modifier = Modifier.size(64.dp),
        strokeWidth = 6.dp,
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = if (state.message.isNotBlank()) state.message else "Starting compositor…",
        style = MaterialTheme.typography.titleMedium,
    )
}

@Composable
private fun ErrorContent(
    state: SetupState.Error,
    onRetry: (() -> Unit)?,
    onCancel: (() -> Unit)?,
) {
    Icon(
        painter = painterResource(id = android.R.drawable.ic_dialog_alert),
        contentDescription = "Error",
        modifier = Modifier.size(48.dp),
        tint = MaterialTheme.colorScheme.error,
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "Setup failed",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.error,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = state.message,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(16.dp))

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onCancel != null) {
            OutlinedButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
        if (state.canRetry && onRetry != null) {
            Button(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}

@Composable
private fun CancelButton(onCancel: (() -> Unit)?) {
    if (onCancel != null) {
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(onClick = onCancel) {
            Text("Cancel")
        }
    }
}
