package nu.shel.wldroid.compositor

/**
 * Input dispatcher — forwards Android input events to the native compositor.
 */
class CompositorInput(private val server: CompositorServer) {
    fun sendTouchEvent(id: Int, action: Int, x: Float, y: Float, timestampMs: Long) {
        server.nativeSendTouchEvent(id, action, x, y, timestampMs)
    }

    fun sendKeyEvent(androidKeyCode: Int, action: Int, timestampMs: Long) {
        server.nativeSendKeyEvent(androidKeyCode, action, timestampMs)
    }

    fun sendPointerMotion(x: Float, y: Float, timestampMs: Long) {
        server.nativeSendPointerMotion(x, y, timestampMs)
    }

    fun sendPointerButton(button: Int, action: Int, timestampMs: Long) {
        server.nativeSendPointerButton(button, action, timestampMs)
    }

    fun sendPointerScroll(dx: Float, dy: Float, timestampMs: Long) {
        server.nativeSendPointerScroll(dx, dy, timestampMs)
    }

    fun commitText(text: String) {
        server.nativeCommitText(text)
    }

    fun notifyImeShown() {
        server.nativeImeShown()
    }

    fun notifyImeHidden() {
        server.nativeImeHidden()
    }

    fun getImePipeFd(): Int = server.nativeGetImePipeFd()
}
