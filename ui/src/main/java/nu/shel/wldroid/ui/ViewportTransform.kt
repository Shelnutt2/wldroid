package nu.shel.wldroid.ui

import kotlin.math.max
import kotlin.math.min

/** A point in Wayland guest/output coordinates. */
data class GuestPoint(val x: Float, val y: Float)

/**
 * Host-side viewport transform for the compositor surface.
 *
 * The guest output size stays fixed. This model only describes how that fixed
 * output is scaled and translated inside the Android host view.
 */
data class ViewportTransform(
    val scale: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
    val viewWidth: Int = 0,
    val viewHeight: Int = 0,
    val contentWidth: Int = 0,
    val contentHeight: Int = 0,
    val imeBottomInsetPx: Int = 0,
    val minScale: Float = 1f,
    val maxScale: Float = 4f,
) {
    init {
        require(minScale > 0f) { "minScale must be > 0" }
        require(maxScale >= minScale) { "maxScale must be >= minScale" }
    }

    /** Maps a host-view coordinate to the fixed guest/output coordinate space. */
    fun mapViewToGuest(viewX: Float, viewY: Float): GuestPoint {
        return GuestPoint(
            x = (viewX - panX) / scale,
            y = (viewY - panY) / scale,
        )
    }

    /** Returns a transform clamped to the configured scale and pan bounds. */
    fun clamped(): ViewportTransform {
        val clampedScale = scale.coerceIn(minScale, maxScale)
        val scaledWidth = contentWidth * clampedScale
        val scaledHeight = contentHeight * clampedScale
        val minPanX = min(0f, viewWidth - scaledWidth)
        val maxPanX = 0f
        val safeHeight = max(0, viewHeight - imeBottomInsetPx)
        val minPanY = min(0f, safeHeight - scaledHeight)
        val maxPanY = 0f

        return copy(
            scale = clampedScale,
            panX = panX.coerceIn(minPanX, maxPanX),
            panY = panY.coerceIn(minPanY, maxPanY),
        )
    }

    /** Zooms around [focalX], [focalY], preserving the guest point under the focal point. */
    fun zoomBy(factor: Float, focalX: Float, focalY: Float): ViewportTransform {
        if (factor <= 0f) return this

        val oldScale = scale
        val newScale = (scale * factor).coerceIn(minScale, maxScale)
        if (newScale == oldScale) return clamped()

        val guestX = (focalX - panX) / oldScale
        val guestY = (focalY - panY) / oldScale
        return copy(
            scale = newScale,
            panX = focalX - guestX * newScale,
            panY = focalY - guestY * newScale,
        ).clamped()
    }

    /** Pans by host-view pixels. */
    fun panBy(dx: Float, dy: Float): ViewportTransform {
        return copy(panX = panX + dx, panY = panY + dy).clamped()
    }

    fun reset(): ViewportTransform {
        return copy(scale = minScale, panX = 0f, panY = 0f).clamped()
    }

    fun withViewSize(width: Int, height: Int): ViewportTransform {
        return copy(viewWidth = width.coerceAtLeast(0), viewHeight = height.coerceAtLeast(0)).clamped()
    }

    fun withContentSize(width: Int, height: Int): ViewportTransform {
        return copy(contentWidth = width.coerceAtLeast(0), contentHeight = height.coerceAtLeast(0)).clamped()
    }

    fun withImeBottomInset(heightPx: Int): ViewportTransform {
        return copy(imeBottomInsetPx = heightPx.coerceAtLeast(0)).clamped()
    }

    fun withScaleBounds(minScale: Float, maxScale: Float): ViewportTransform {
        val safeMin = minScale.coerceAtLeast(0.01f)
        val safeMax = max(maxScale, safeMin)
        return copy(minScale = safeMin, maxScale = safeMax).clamped()
    }
}
