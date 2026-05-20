package nu.shel.wldroid.ui

/** Tracks whether a pointer sequence still qualifies as a keyboard focus tap. */
internal class KeyboardFocusTapTracker(
    private val slopPx: Float,
) {
    private var candidate: Candidate? = null

    val pendingPointerId: Int?
        get() = candidate?.pointerId

    fun begin(pointerId: Int, x: Float, y: Float) {
        candidate = Candidate(pointerId, x, y)
    }

    fun update(pointerId: Int, x: Float, y: Float): Boolean {
        val current = candidate ?: return false
        if (pointerId != current.pointerId) return true

        val dx = x - current.downX
        val dy = y - current.downY
        if (dx * dx + dy * dy > slopPx * slopPx) {
            candidate = null
            return false
        }
        return true
    }

    fun consume(pointerId: Int, x: Float, y: Float): Boolean {
        val current = candidate ?: return false
        if (pointerId != current.pointerId) {
            candidate = null
            return false
        }

        update(pointerId, x, y)
        val valid = candidate != null
        candidate = null
        return valid
    }

    fun cancel() {
        candidate = null
    }

    private data class Candidate(
        val pointerId: Int,
        val downX: Float,
        val downY: Float,
    )
}
