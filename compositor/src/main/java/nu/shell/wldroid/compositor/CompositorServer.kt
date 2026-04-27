package nu.shell.wldroid.compositor

import android.view.Surface

/**
 * Low-level wrapper around the native wlroots compositor.
 * This is the JNI bridge class — native methods are registered here via JNI_OnLoad.
 */
class CompositorServer {
    companion object {
        init {
            System.loadLibrary("wldroid-compositor")
        }
    }

    // Compositor lifecycle
    external fun nativeStartCompositor(surface: Surface, cacheDir: String, xkbBasePath: String)
    external fun nativeStopCompositor()
    external fun nativeGetSocketName(): String?
    external fun nativeGetClientCount(): Int
    external fun nativeResizeOutput(width: Int, height: Int)

    // Input
    external fun nativeSendTouchEvent(id: Int, action: Int, x: Float, y: Float, timestampMs: Long)
    external fun nativeSendKeyEvent(androidKeyCode: Int, action: Int, timestampMs: Long)
    external fun nativeSendPointerMotion(x: Float, y: Float, timestampMs: Long)
    external fun nativeSendPointerButton(button: Int, action: Int, timestampMs: Long)
    external fun nativeSendPointerScroll(dx: Float, dy: Float, timestampMs: Long)

    // IME
    external fun nativeCommitText(text: String)
    external fun nativeImeShown()
    external fun nativeImeHidden()
    external fun nativeGetImePipeFd(): Int

    // Test
    external fun nativeStartTestClient()
}
