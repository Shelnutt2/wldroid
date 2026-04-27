package nu.shell.wldroid.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource

/**
 * A floating action button that toggles the software keyboard visibility.
 * Shows a keyboard icon when hidden, and a keyboard-hide icon when visible.
 */
@Composable
fun KeyboardToggleFab(
    isKeyboardVisible: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
    ) {
        FloatingActionButton(
            onClick = onToggle,
            modifier = modifier,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Icon(
                painter = painterResource(
                    id = if (isKeyboardVisible) {
                        android.R.drawable.ic_menu_close_clear_cancel
                    } else {
                        android.R.drawable.ic_menu_edit
                    },
                ),
                contentDescription = if (isKeyboardVisible) {
                    "Hide keyboard"
                } else {
                    "Show keyboard"
                },
            )
        }
    }
}
