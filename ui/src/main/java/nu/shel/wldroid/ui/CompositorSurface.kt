package nu.shel.wldroid.ui

import android.content.Context
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nu.shel.wldroid.compositor.CompositorConfig
import nu.shel.wldroid.compositor.CompositorInput
import nu.shel.wldroid.compositor.CompositorState

internal const val DEFAULT_ENABLE_VIEWPORT_GESTURES = false


/**
 * Public controller for Android soft-keyboard actions associated with a [CompositorSurface].
 *
 * Instances are provided by [CompositorSurface]'s `onKeyboardControllerChange` callback while
 * the underlying Android view is attached. Showing or hiding the keyboard does not change the
 * guest Wayland output size or DPI.
 */
class CompositorKeyboardController internal constructor(
    private val surfaceState: CompositorSurfaceState,
    private val viewProvider: () -> View?,
) {
    /** Request that Android show the soft keyboard and notify native IME state by default. */
    fun show(notifyNative: Boolean = true): Boolean {
        return showKeyboard(viewProvider(), surfaceState, notifyNative)
    }

    /** Hide the Android soft keyboard and notify native IME state by default. */
    fun hide(notifyNative: Boolean = true) {
        hideKeyboard(viewProvider(), surfaceState, notifyNative)
    }

    /** Toggle keyboard visibility if keyboard input is currently enabled. */
    fun toggle(keyboardInputEnabled: Boolean = true): Boolean {
        return if (surfaceState.isKeyboardVisible.value) {
            hide()
            false
        } else if (keyboardInputEnabled) {
            show()
        } else {
            false
        }
    }

    /** Restart Android input so changes to text-editor capability are visible to the IME. */
    fun restartInput() {
        val targetView = viewProvider() ?: return
        val imm = targetView.context.getSystemService(InputMethodManager::class.java)
        imm?.restartInput(targetView)
    }
}

/**
 * Flagship composable that embeds a Wayland compositor surface.
 *
 * Internally creates a [SurfaceView], manages the [CompositorSession] lifecycle,
 * dispatches touch/key/pointer events, and provides a custom [InputConnection]
 * for IME text input.
 *
 * Zoom and pan are Android host viewport transforms. They do not change the
 * guest Wayland output size, DPI, or client layout.
 *
 * @param modifier Layout modifier for the surface container.
 * @param config Compositor configuration (cache dir, XKB path, GPU mode, etc.).
 * @param onStateChange Called when the compositor lifecycle state changes.
 * @param onClientCountChange Called when the number of connected Wayland clients changes.
 * @param onKeyboardControllerChange Called with a keyboard controller while the surface view is attached.
 * @param inputMode Controls which input events are forwarded to the compositor.
 * @param showKeyboardFab Whether to show a floating action button for toggling the keyboard.
 * @param enableViewportGestures Whether two-finger host pinch/pan gestures are enabled. Defaults off so guest multi-touch is forwarded.
 * @param minZoom Minimum host viewport zoom; does not affect Wayland output size.
 * @param maxZoom Maximum host viewport zoom; does not affect Wayland output size.
 * @param keyboardPanBehavior How the host viewport accounts for Android IME overlap.
 * @param keyboardAutoShowBehavior When the Android soft keyboard should auto-open.
 */
@Composable
fun CompositorSurface(
    modifier: Modifier = Modifier,
    config: CompositorConfig = CompositorConfig.default(),
    surfaceState: CompositorSurfaceState = rememberCompositorSurfaceState(config),
    onStateChange: (CompositorState) -> Unit = {},
    onClientCountChange: (Int) -> Unit = {},
    onKeyboardControllerChange: (CompositorKeyboardController?) -> Unit = {},
    inputMode: InputMode = InputMode.TOUCH_AND_KEYBOARD,
    showKeyboardFab: Boolean = true,
    enableViewportGestures: Boolean = DEFAULT_ENABLE_VIEWPORT_GESTURES,
    minZoom: Float = 1f,
    maxZoom: Float = 4f,
    keyboardPanBehavior: KeyboardPanBehavior = KeyboardPanBehavior.PanWithinImeSafeArea,
    keyboardAutoShowBehavior: KeyboardAutoShowBehavior = KeyboardAutoShowBehavior.TextInputRequestsAndFocusTap,
) {
    val compositorState by surfaceState.compositorState.collectAsState()
    val clientCount by surfaceState.clientCount.collectAsState()
    val isKeyboardVisible by surfaceState.isKeyboardVisible.collectAsState()
    var surfaceViewRef by remember { mutableStateOf<CompositorSurfaceView?>(null) }
    val currentInputMode = rememberUpdatedState(inputMode)
    val keyboardController = remember(surfaceState) {
        CompositorKeyboardController(surfaceState) { surfaceViewRef }
    }

    val density = LocalDensity.current
    val imeBottomInset = WindowInsets.ime.getBottom(density)

    LaunchedEffect(minZoom, maxZoom) {
        surfaceState.setViewportScaleBounds(minZoom, maxZoom)
    }

    LaunchedEffect(imeBottomInset, keyboardPanBehavior) {
        surfaceState.setImeBottomInset(
            if (keyboardPanBehavior == KeyboardPanBehavior.PanWithinImeSafeArea) {
                imeBottomInset
            } else {
                0
            },
        )
        surfaceState.setKeyboardVisible(imeBottomInset > 0)
    }

    LaunchedEffect(surfaceViewRef, keyboardController) {
        onKeyboardControllerChange(if (surfaceViewRef != null) keyboardController else null)
    }

    LaunchedEffect(inputMode.hasKeyboardInput, surfaceViewRef) {
        keyboardController.restartInput()
        if (!inputMode.hasKeyboardInput) {
            keyboardController.hide(notifyNative = true)
        } else if (surfaceViewRef != null && surfaceState.session.input.hasActiveTextInput()) {
            keyboardController.show(notifyNative = true)
        }
    }

    // Notify callers of state changes.
    LaunchedEffect(compositorState) {
        onStateChange(compositorState)
    }
    LaunchedEffect(clientCount) {
        onClientCountChange(clientCount)
    }

    // Start the IME pipe reader when the compositor is running.
    LaunchedEffect(surfaceState, compositorState) {
        if (compositorState == CompositorState.RUNNING) {
            launch(Dispatchers.IO) {
                readImePipe(
                    surfaceState = surfaceState,
                    keyboardInputEnabled = { currentInputMode.value.hasKeyboardInput },
                ) { surfaceViewRef }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        key(surfaceState) {
            CompositorAndroidView(
                surfaceState = surfaceState,
                inputMode = inputMode,
                keyboardAutoShowBehavior = keyboardAutoShowBehavior,
                enableViewportGestures = enableViewportGestures,
                onViewChanged = { surfaceViewRef = it },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (showKeyboardFab) {
            KeyboardToggleFab(
                isKeyboardVisible = isKeyboardVisible,
                onToggle = {
                    keyboardController.toggle(inputMode.hasKeyboardInput)
                },
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
    keyboardAutoShowBehavior: KeyboardAutoShowBehavior,
    enableViewportGestures: Boolean,
    onViewChanged: (CompositorSurfaceView?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewport by surfaceState.viewport.collectAsState()

    DisposableEffect(surfaceState) {
        onDispose {
            surfaceState.session.stop()
            onViewChanged(null)
        }
    }

    AndroidView(
        factory = { context ->
            FrameLayout(context).also { container ->
                container.clipChildren = true
                container.clipToPadding = true
                container.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                    surfaceState.setViewportViewSize(right - left, bottom - top)
                }

                val view = CompositorSurfaceView(
                    context = context,
                    surfaceState = surfaceState,
                    inputMode = inputMode,
                    keyboardAutoShowBehavior = keyboardAutoShowBehavior,
                    enableViewportGestures = enableViewportGestures,
                )
                onViewChanged(view)
                view.isFocusable = true
                view.isFocusableInTouchMode = true
                view.holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            view.requestFocus()
                            val state = surfaceState.session.state.value
                            if (state == CompositorState.PAUSED) {
                                surfaceState.session.resume(holder.surface)
                            } else {
                                surfaceState.session.start(holder.surface)
                            }
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) {
                            surfaceState.setViewportContentSize(width, height)
                            surfaceState.session.resizeOutput(width, height)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            surfaceState.session.pause()
                        }
                    },
                )
                container.addView(
                    view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        },
        update = { container ->
            val view = container.getChildAt(0) as? CompositorSurfaceView ?: return@AndroidView
            view.inputMode = inputMode
            view.keyboardAutoShowBehavior = keyboardAutoShowBehavior
            view.enableViewportGestures = enableViewportGestures
            view.pivotX = 0f
            view.pivotY = 0f
            view.scaleX = viewport.scale
            view.scaleY = viewport.scale
            view.translationX = viewport.panX
            view.translationY = viewport.panY
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
    inputMode: InputMode,
    keyboardAutoShowBehavior: KeyboardAutoShowBehavior,
    enableViewportGestures: Boolean,
) : SurfaceView(context) {

    var inputMode: InputMode = inputMode
        set(value) {
            val cancelTouches = field.hasTouchInput && !value.hasTouchInput
            field = value
            if (cancelTouches) {
                cancelForwardedTouches(SystemClock.uptimeMillis())
                endHostGesture(suppressUntilUp = false)
            }
        }
    var keyboardAutoShowBehavior: KeyboardAutoShowBehavior = keyboardAutoShowBehavior
    var enableViewportGestures: Boolean = enableViewportGestures

    private val input: CompositorInput
        get() = surfaceState.session.input

    private val forwardedTouchIds = mutableSetOf<Int>()
    private val keyboardFocusTapSlopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var pendingKeyboardFocusTap: KeyboardFocusTapCandidate? = null
    private var hostGestureActive = false
    private var suppressTouchForwardingUntilUp = false
    private var lastGestureFocusX = 0f
    private var lastGestureFocusY = 0f
    private var lastGestureSpan = 0f
    private val hostLocationInWindow = IntArray(2)

    override fun onCheckIsTextEditor(): Boolean = inputMode.hasKeyboardInput

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE

        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val str = text?.toString() ?: return false
                if (input.hasActiveTextInput()) {
                    input.commitText(str)
                    return true
                }

                // Try to map characters to synthetic key events
                val keyCharMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
                val events = keyCharMap.getEvents(str.toCharArray())
                if (events != null) {
                    for (event in events) {
                        val action = when (event.action) {
                            KeyEvent.ACTION_DOWN -> 0
                            KeyEvent.ACTION_UP -> 1
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
                input.deleteSurroundingText(beforeLength, afterLength)
                return true
            }

            override fun deleteSurroundingTextInCodePoints(
                beforeLength: Int,
                afterLength: Int,
            ): Boolean {
                input.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                handleKeyEvent(event)
                return true
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val toolType = event.getToolType(0)
        val isMouse = toolType == MotionEvent.TOOL_TYPE_MOUSE

        if (isMouse && inputMode.hasPointerInput) {
            handlePointerEvent(event)
            return true
        }

        if (!isMouse && shouldHandleHostGesture(event)) {
            handleHostGesture(event)
            return true
        }

        if (suppressTouchForwardingUntilUp) {
            pendingKeyboardFocusTap = null
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                suppressTouchForwardingUntilUp = false
            }
            return true
        }

        if (!isMouse && inputMode.hasTouchInput) {
            handleTouchEvent(event)
            return true
        }

        if (enableViewportGestures && !isMouse) return true
        if (!inputMode.hasTouchInput && !inputMode.hasPointerInput) return super.onTouchEvent(event)
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!inputMode.hasPointerInput) return super.onGenericMotionEvent(event)

        return when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE -> {
                val point = mapEventToGuest(event, 0)
                input.sendPointerMotion(point.x, point.y, event.eventTime)
                true
            }
            MotionEvent.ACTION_SCROLL -> {
                val point = mapEventToGuest(event, 0)
                input.sendPointerMotion(point.x, point.y, event.eventTime)
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

    private fun shouldHandleHostGesture(event: MotionEvent): Boolean {
        return enableViewportGestures && (hostGestureActive || event.pointerCount >= 2)
    }

    private fun handleHostGesture(event: MotionEvent) {
        val action = event.actionMasked
        if (!hostGestureActive) {
            cancelForwardedTouches(event.eventTime)
            beginHostGesture(event)
            return
        }

        when (action) {
            MotionEvent.ACTION_MOVE -> updateHostGesture(event)
            MotionEvent.ACTION_POINTER_DOWN -> beginHostGesture(event)
            MotionEvent.ACTION_POINTER_UP -> {
                val remainingPointers = event.pointerCount - 1
                if (remainingPointers >= 2) {
                    beginHostGesture(event, skipPointerIndex = event.actionIndex)
                } else {
                    endHostGesture(suppressUntilUp = true)
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> endHostGesture(suppressUntilUp = false)
        }
    }

    private fun beginHostGesture(event: MotionEvent, skipPointerIndex: Int = -1) {
        pendingKeyboardFocusTap = null
        hostGestureActive = true
        suppressTouchForwardingUntilUp = true
        val centroid = gestureCentroid(event, skipPointerIndex)
        lastGestureFocusX = centroid.first
        lastGestureFocusY = centroid.second
        lastGestureSpan = gestureSpan(event, lastGestureFocusX, lastGestureFocusY, skipPointerIndex)
    }

    private fun updateHostGesture(event: MotionEvent) {
        val centroid = gestureCentroid(event)
        val focusX = centroid.first
        val focusY = centroid.second
        val span = gestureSpan(event, focusX, focusY)

        if (lastGestureSpan > 0f && span > 0f) {
            surfaceState.zoomBy(span / lastGestureSpan, focusX, focusY)
        }
        surfaceState.panBy(focusX - lastGestureFocusX, focusY - lastGestureFocusY)

        lastGestureFocusX = focusX
        lastGestureFocusY = focusY
        lastGestureSpan = span
    }

    private fun endHostGesture(suppressUntilUp: Boolean) {
        hostGestureActive = false
        lastGestureSpan = 0f
        suppressTouchForwardingUntilUp = suppressUntilUp
    }

    private fun cancelForwardedTouches(timestampMs: Long) {
        pendingKeyboardFocusTap = null
        if (forwardedTouchIds.isEmpty()) return
        forwardedTouchIds.forEach { id ->
            input.sendTouchEvent(id, MotionEvent.ACTION_CANCEL, 0f, 0f, timestampMs)
        }
        forwardedTouchIds.clear()
    }

    private fun handleTouchEvent(event: MotionEvent) {
        val actionIndex = event.actionIndex
        val action = event.actionMasked

        when (action) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            -> {
                val id = event.getPointerId(actionIndex)
                if (action == MotionEvent.ACTION_DOWN) {
                    beginKeyboardFocusTap(event, actionIndex)
                } else {
                    pendingKeyboardFocusTap = null
                }
                val point = mapEventToGuest(event, actionIndex)
                forwardedTouchIds.add(id)
                input.sendTouchEvent(
                    id, MotionEvent.ACTION_DOWN,
                    point.x, point.y,
                    event.eventTime,
                )
            }
            MotionEvent.ACTION_MOVE -> {
                updateKeyboardFocusTap(event)
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    if (id !in forwardedTouchIds) continue
                    val point = mapEventToGuest(event, i)
                    input.sendTouchEvent(
                        id, MotionEvent.ACTION_MOVE,
                        point.x, point.y,
                        event.eventTime,
                    )
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            -> {
                val id = event.getPointerId(actionIndex)
                if (id in forwardedTouchIds) {
                    val point = mapEventToGuest(event, actionIndex)
                    input.sendTouchEvent(
                        id, MotionEvent.ACTION_UP,
                        point.x, point.y,
                        event.eventTime,
                    )
                    forwardedTouchIds.remove(id)
                }
                if (action == MotionEvent.ACTION_UP && consumeKeyboardFocusTap(event)) {
                    maybeShowKeyboardAfterUserFocus()
                } else if (action == MotionEvent.ACTION_POINTER_UP) {
                    pendingKeyboardFocusTap = null
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pendingKeyboardFocusTap = null
                for (id in forwardedTouchIds.toList()) {
                    input.sendTouchEvent(
                        id, MotionEvent.ACTION_CANCEL,
                        0f, 0f,
                        event.eventTime,
                    )
                }
                forwardedTouchIds.clear()
            }
        }
    }

    private fun handlePointerEvent(event: MotionEvent) {
        val point = mapEventToGuest(event, 0)
        input.sendPointerMotion(point.x, point.y, event.eventTime)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val button = pointerButtonFromMotionEvent(event)
                if (button == BTN_LEFT) {
                    beginKeyboardFocusTap(event, 0)
                } else {
                    pendingKeyboardFocusTap = null
                }
                input.sendPointerButton(
                    button, 0, event.eventTime,
                )
            }
            MotionEvent.ACTION_MOVE -> updateKeyboardFocusTap(event)
            MotionEvent.ACTION_UP -> {
                input.sendPointerButton(
                    pointerButtonFromMotionEvent(event), 1, event.eventTime,
                )
                if (consumeKeyboardFocusTap(event)) {
                    maybeShowKeyboardAfterUserFocus()
                }
            }
            MotionEvent.ACTION_BUTTON_PRESS -> {
                if (event.actionButton == MotionEvent.BUTTON_PRIMARY) {
                    beginKeyboardFocusTap(event, 0)
                }
                input.sendPointerButton(
                    pointerButtonFromActionButton(event.actionButton), 0, event.eventTime,
                )
            }
            MotionEvent.ACTION_BUTTON_RELEASE -> {
                input.sendPointerButton(
                    pointerButtonFromActionButton(event.actionButton), 1, event.eventTime,
                )
                if (event.actionButton == MotionEvent.BUTTON_PRIMARY && consumeKeyboardFocusTap(event)) {
                    maybeShowKeyboardAfterUserFocus()
                }
            }
            MotionEvent.ACTION_CANCEL -> pendingKeyboardFocusTap = null
        }
    }

    private fun beginKeyboardFocusTap(event: MotionEvent, pointerIndex: Int) {
        pendingKeyboardFocusTap = KeyboardFocusTapCandidate(
            pointerId = event.getPointerId(pointerIndex),
            downX = hostX(event, pointerIndex),
            downY = hostY(event, pointerIndex),
        )
    }

    private fun updateKeyboardFocusTap(event: MotionEvent) {
        val candidate = pendingKeyboardFocusTap ?: return
        val pointerIndex = event.findPointerIndex(candidate.pointerId)
        if (pointerIndex < 0) {
            pendingKeyboardFocusTap = null
            return
        }

        val dx = hostX(event, pointerIndex) - candidate.downX
        val dy = hostY(event, pointerIndex) - candidate.downY
        if (dx * dx + dy * dy > keyboardFocusTapSlopPx * keyboardFocusTapSlopPx) {
            pendingKeyboardFocusTap = null
        }
    }

    private fun consumeKeyboardFocusTap(event: MotionEvent): Boolean {
        val candidate = pendingKeyboardFocusTap ?: return false
        updateKeyboardFocusTap(event)
        val valid = pendingKeyboardFocusTap != null &&
            event.getPointerId(event.actionIndex) == candidate.pointerId
        pendingKeyboardFocusTap = null
        return valid
    }

    private fun maybeShowKeyboardAfterUserFocus() {
        if (!keyboardAutoShowBehavior.opensOnFocusTap) return
        if (!inputMode.hasKeyboardInput || surfaceState.isKeyboardVisible.value) return

        post {
            if (!isAttachedToWindow) return@post
            if (!keyboardAutoShowBehavior.opensOnFocusTap) return@post
            if (!inputMode.hasKeyboardInput || surfaceState.isKeyboardVisible.value) return@post

            val imm = context.getSystemService(InputMethodManager::class.java)
            imm?.restartInput(this)
            showKeyboard(this, surfaceState, notifyNative = true)
        }
    }

    private fun gestureCentroid(event: MotionEvent, skipPointerIndex: Int = -1): Pair<Float, Float> {
        var sumX = 0f
        var sumY = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == skipPointerIndex) continue
            sumX += hostX(event, i)
            sumY += hostY(event, i)
            count++
        }
        if (count == 0) return lastGestureFocusX to lastGestureFocusY
        return sumX / count to sumY / count
    }

    private fun gestureSpan(
        event: MotionEvent,
        focusX: Float,
        focusY: Float,
        skipPointerIndex: Int = -1,
    ): Float {
        var sum = 0f
        var count = 0
        for (i in 0 until event.pointerCount) {
            if (i == skipPointerIndex) continue
            val dx = hostX(event, i) - focusX
            val dy = hostY(event, i) - focusY
            sum += kotlin.math.sqrt(dx * dx + dy * dy)
            count++
        }
        return if (count > 0) sum / count else 0f
    }

    private fun mapEventToGuest(event: MotionEvent, pointerIndex: Int): GuestPoint {
        return surfaceState.mapViewToGuest(hostX(event, pointerIndex), hostY(event, pointerIndex))
    }

    private fun hostX(event: MotionEvent, pointerIndex: Int): Float {
        return event.getRawX(pointerIndex) - hostOriginX()
    }

    private fun hostY(event: MotionEvent, pointerIndex: Int): Float {
        return event.getRawY(pointerIndex) - hostOriginY()
    }

    private fun hostOriginX(): Int {
        val host = parent as? View ?: this
        host.getLocationInWindow(hostLocationInWindow)
        return hostLocationInWindow[0]
    }

    private fun hostOriginY(): Int {
        val host = parent as? View ?: this
        host.getLocationInWindow(hostLocationInWindow)
        return hostLocationInWindow[1]
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

        private data class KeyboardFocusTapCandidate(
            val pointerId: Int,
            val downX: Float,
            val downY: Float,
        )

        private fun pointerButtonFromMotionEvent(event: MotionEvent): Int {
            return when {
                event.isButtonPressed(MotionEvent.BUTTON_SECONDARY) -> BTN_RIGHT
                event.isButtonPressed(MotionEvent.BUTTON_TERTIARY) -> BTN_MIDDLE
                else -> BTN_LEFT
            }
        }

        private fun pointerButtonFromActionButton(actionButton: Int): Int {
            return when (actionButton) {
                MotionEvent.BUTTON_PRIMARY -> BTN_LEFT
                MotionEvent.BUTTON_SECONDARY -> BTN_RIGHT
                MotionEvent.BUTTON_TERTIARY -> BTN_MIDDLE
                else -> BTN_LEFT
            }
        }
    }
}

/**
 * Reads the IME pipe from the compositor to show/hide the software keyboard.
 * The pipe sends 'S' for show and 'H' for hide.
 */
private suspend fun readImePipe(
    surfaceState: CompositorSurfaceState,
    keyboardInputEnabled: () -> Boolean,
    viewProvider: () -> View?,
) {
    val fd = surfaceState.session.input.getImePipeFd()
    if (fd < 0) return

    withContext(Dispatchers.IO) {
        val pfd = android.os.ParcelFileDescriptor.fromFd(fd)
        val pollFd = StructPollfd().apply {
            this.fd = pfd.fileDescriptor
            events = OsConstants.POLLIN.toShort()
        }
        val buffer = ByteArray(1)
        try {
            while (isActive) {
                try {
                    pollFd.revents = 0.toShort()
                    val ready = Os.poll(arrayOf(pollFd), 250)
                    if (ready <= 0) continue

                    val revents = pollFd.revents.toInt()
                    if ((revents and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL)) != 0) {
                        break
                    }
                    if ((revents and OsConstants.POLLIN) == 0) continue

                    val bytesRead = Os.read(pfd.fileDescriptor, buffer, 0, buffer.size)
                    if (bytesRead <= 0) break
                    when (buffer[0].toInt().toChar()) {
                        'S' -> withContext(Dispatchers.Main) {
                            if (keyboardInputEnabled()) {
                                showKeyboard(viewProvider(), surfaceState, notifyNative = false)
                            }
                        }
                        'H' -> withContext(Dispatchers.Main) {
                            hideKeyboard(viewProvider(), surfaceState, notifyNative = false)
                        }
                    }
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) {
                        continue
                    }
                    break
                }
            }
        } finally {
            try {
                pfd.close()
            } catch (_: Exception) {
                // Already closed or invalid — ignore
            }
        }
    }
}

private fun showKeyboard(
    view: View?,
    surfaceState: CompositorSurfaceState,
    notifyNative: Boolean,
): Boolean {
    val targetView = view ?: return false
    targetView.requestFocus()
    val imm = targetView.context.getSystemService(InputMethodManager::class.java)
    val accepted = imm?.showSoftInput(targetView, InputMethodManager.SHOW_IMPLICIT) == true
    if (!accepted) return false

    surfaceState.setKeyboardVisible(true)
    if (notifyNative) {
        surfaceState.session.input.notifyImeShown()
    }
    return true
}

private fun hideKeyboard(
    view: View?,
    surfaceState: CompositorSurfaceState,
    notifyNative: Boolean,
) {
    val targetView = view
    val imm = targetView?.context?.getSystemService(InputMethodManager::class.java)
    if (targetView != null) {
        imm?.hideSoftInputFromWindow(targetView.windowToken, 0)
    }
    surfaceState.setKeyboardVisible(false)
    if (notifyNative) {
        surfaceState.session.input.notifyImeHidden()
    }
}
