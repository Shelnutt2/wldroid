package nu.shell.wldroid.ui

import android.content.Context
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nu.shell.wldroid.compositor.CompositorConfig
import nu.shell.wldroid.compositor.CompositorInput
import nu.shell.wldroid.compositor.CompositorState
import java.io.FileInputStream

/**
 * Flagship composable that embeds a Wayland compositor surface.
 *
 * Internally creates a [SurfaceView], manages the [CompositorSession] lifecycle,
 * dispatches touch/key/pointer events, and provides a custom [InputConnection]
 * for IME text input.
 *
 * @param modifier Layout modifier for the surface container.
 * @param config Compositor configuration (cache dir, XKB path, GPU mode, etc.).
 * @param onStateChange Called when the compositor lifecycle state changes.
 * @param onClientCountChange Called when the number of connected Wayland clients changes.
 * @param inputMode Controls which input events are forwarded to the compositor.
 * @param showKeyboardFab Whether to show a floating action button for toggling the keyboard.
 */
@Composable
fun CompositorSurface(
    modifier: Modifier = Modifier,
    config: CompositorConfig = CompositorConfig.default(),
    onStateChange: (CompositorState) -> Unit = {},
    onClientCountChange: (Int) -> Unit = {},
    inputMode: InputMode = InputMode.TOUCH_AND_KEYBOARD,
    showKeyboardFab: Boolean = true,
) {
    val surfaceState = rememberCompositorSurfaceState(config)
    val compositorState by surfaceState.compositorState.collectAsState()
    val clientCount by surfaceState.clientCount.collectAsState()
    val isKeyboardVisible by surfaceState.isKeyboardVisible.collectAsState()

    // Notify callers of state changes.
    LaunchedEffect(compositorState) {
        onStateChange(compositorState)
    }
    LaunchedEffect(clientCount) {
        onClientCountChange(clientCount)
    }

    // Start the IME pipe reader when the compositor is running.
    LaunchedEffect(compositorState) {
        if (compositorState == CompositorState.RUNNING) {
            launch(Dispatchers.IO) {
                readImePipe(surfaceState)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        CompositorAndroidView(
            surfaceState = surfaceState,
            inputMode = inputMode,
            modifier = Modifier.fillMaxSize(),
        )

        if (showKeyboardFab) {
            KeyboardToggleFab(
                isKeyboardVisible = isKeyboardVisible,
                onToggle = { toggleKeyboard(surfaceState) },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            )
        }
    }
}

/**
 * Internal composable wrapping the Android [SurfaceView] that hosts the compositor.
 */
@Composable
private fun CompositorAndroidView(
    surfaceState: CompositorSurfaceState,
    inputMode: InputMode,
    modifier: Modifier = Modifier,
) {
    var surfaceViewRef by remember { mutableStateOf<SurfaceView?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            surfaceState.session.stop()
            surfaceViewRef = null
        }
    }

    AndroidView(
        factory = { context ->
            CompositorSurfaceView(context, surfaceState, inputMode).also { view ->
                surfaceViewRef = view
                view.isFocusable = true
                view.isFocusableInTouchMode = true
                view.holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            surfaceState.session.start(holder.surface)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) {
                            surfaceState.session.resizeOutput(width, height)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            surfaceState.session.stop()
                        }
                    },
                )
            }
        },
        modifier = modifier,
    )
}

/**
 * Custom [SurfaceView] subclass that handles input dispatching and provides
 * a custom [InputConnection] for IME text input to the Wayland compositor.
 */
private class CompositorSurfaceView(
    context: Context,
    private val surfaceState: CompositorSurfaceState,
    private val inputMode: InputMode,
) : SurfaceView(context) {

    private val input: CompositorInput
        get() = surfaceState.session.input

    override fun onCheckIsTextEditor(): Boolean = inputMode.hasKeyboardInput

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE

        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val str = text?.toString() ?: return false
                // Try to map characters to synthetic key events
                val keyCharMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
                val events = keyCharMap.getEvents(str.toCharArray())
                if (events != null) {
                    for (event in events) {
                        val action = when (event.action) {
                            KeyEvent.ACTION_DOWN -> 1
                            KeyEvent.ACTION_UP -> 0
                            else -> continue
                        }
                        input.sendKeyEvent(
                            event.keyCode,
                            action,
                            event.eventTime,
                        )
                    }
                } else {
                    // Fallback: commit raw text via compositor
                    input.commitText(str)
                }
                return true
            }

            override fun deleteSurroundingText(
                beforeLength: Int,
                afterLength: Int,
            ): Boolean {
                val now = System.currentTimeMillis()
                // Send backspace key events for characters before cursor
                repeat(beforeLength) {
                    input.sendKeyEvent(KeyEvent.KEYCODE_DEL, 1, now)
                    input.sendKeyEvent(KeyEvent.KEYCODE_DEL, 0, now)
                }
                // Send forward-delete for characters after cursor
                repeat(afterLength) {
                    input.sendKeyEvent(KeyEvent.KEYCODE_FORWARD_DEL, 1, now)
                    input.sendKeyEvent(KeyEvent.KEYCODE_FORWARD_DEL, 0, now)
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                handleKeyEvent(event)
                return true
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!inputMode.hasTouchInput && !inputMode.hasPointerInput) return super.onTouchEvent(event)

        val toolType = event.getToolType(0)
        val isMouse = toolType == MotionEvent.TOOL_TYPE_MOUSE

        if (isMouse && inputMode.hasPointerInput) {
            handlePointerEvent(event)
        } else if (!isMouse && inputMode.hasTouchInput) {
            handleTouchEvent(event)
        }

        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!inputMode.hasPointerInput) return super.onGenericMotionEvent(event)

        return when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> {
                input.sendPointerMotion(event.x, event.y, event.eventTime)
                true
            }
            MotionEvent.ACTION_SCROLL -> {
                val hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                input.sendPointerScroll(hScroll, vScroll, event.eventTime)
                true
            }
            else -> super.onGenericMotionEvent(event)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!inputMode.hasKeyboardInput) return super.onKeyDown(keyCode, event)
        return handleKeyEvent(event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!inputMode.hasKeyboardInput) return super.onKeyUp(keyCode, event)
        return handleKeyEvent(event)
    }

    private fun handleKeyEvent(event: KeyEvent): Boolean {
        // Let the system handle navigation keys
        if (event.keyCode in SYSTEM_KEY_CODES) return false

        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> 0
            KeyEvent.ACTION_UP -> 1
            else -> return false
        }
        input.sendKeyEvent(event.keyCode, action, event.eventTime)
        return true
    }

    private fun handleTouchEvent(event: MotionEvent) {
        val actionIndex = event.actionIndex
        val action = event.actionMasked

        when (action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            -> {
                val id = event.getPointerId(actionIndex)
                input.sendTouchEvent(
                    id, MotionEvent.ACTION_DOWN,
                    event.getX(actionIndex), event.getY(actionIndex),
                    event.eventTime,
                )
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    input.sendTouchEvent(
                        event.getPointerId(i), MotionEvent.ACTION_MOVE,
                        event.getX(i), event.getY(i),
                        event.eventTime,
                    )
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            -> {
                val id = event.getPointerId(actionIndex)
                input.sendTouchEvent(
                    id, MotionEvent.ACTION_UP,
                    event.getX(actionIndex), event.getY(actionIndex),
                    event.eventTime,
                )
            }
            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until event.pointerCount) {
                    input.sendTouchEvent(
                        event.getPointerId(i), MotionEvent.ACTION_CANCEL,
                        event.getX(i), event.getY(i),
                        event.eventTime,
                    )
                }
            }
        }
    }

    private fun handlePointerEvent(event: MotionEvent) {
        input.sendPointerMotion(event.x, event.y, event.eventTime)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                input.sendPointerButton(
                    pointerButtonFromMotionEvent(event), 0, event.eventTime,
                )
            }
            MotionEvent.ACTION_UP -> {
                input.sendPointerButton(
                    pointerButtonFromMotionEvent(event), 1, event.eventTime,
                )
            }
        }
    }

    companion object {
        /** Key codes that should be handled by the system, not forwarded. */
        private val SYSTEM_KEY_CODES = setOf(
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH,
        )

        // Linux input event button codes
        private const val BTN_LEFT = 0x110
        private const val BTN_RIGHT = 0x111
        private const val BTN_MIDDLE = 0x112

        private fun pointerButtonFromMotionEvent(event: MotionEvent): Int {
            return when {
                event.isButtonPressed(MotionEvent.BUTTON_SECONDARY) -> BTN_RIGHT
                event.isButtonPressed(MotionEvent.BUTTON_TERTIARY) -> BTN_MIDDLE
                else -> BTN_LEFT
            }
        }
    }
}

/**
 * Reads the IME pipe from the compositor to show/hide the software keyboard.
 * The pipe sends 'S' for show and 'H' for hide.
 */
private suspend fun readImePipe(surfaceState: CompositorSurfaceState) {
    val fd = surfaceState.session.input.getImePipeFd()
    if (fd < 0) return

    withContext(Dispatchers.IO) {
        try {
            val pfd = android.os.ParcelFileDescriptor.adoptFd(fd)
            val fis = FileInputStream(pfd.fileDescriptor)
            val buffer = ByteArray(1)
            while (isActive) {
                val bytesRead = fis.read(buffer)
                if (bytesRead <= 0) break
                when (buffer[0].toInt().toChar()) {
                    'S' -> surfaceState.setKeyboardVisible(true)
                    'H' -> surfaceState.setKeyboardVisible(false)
                }
            }
        } catch (_: Exception) {
            // Pipe closed or error — ignore
        }
    }
}

/**
 * Toggles the software keyboard for the given compositor state.
 */
private fun toggleKeyboard(surfaceState: CompositorSurfaceState) {
    val visible = surfaceState.isKeyboardVisible.value
    surfaceState.setKeyboardVisible(!visible)
    if (visible) {
        surfaceState.session.input.notifyImeHidden()
    } else {
        surfaceState.session.input.notifyImeShown()
    }
}
