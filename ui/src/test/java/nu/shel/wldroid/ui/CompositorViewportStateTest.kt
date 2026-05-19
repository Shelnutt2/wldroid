package nu.shel.wldroid.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CompositorViewportStateTest {

    @Test
    fun `defaults to identity transform`() {
        val viewport = ViewportTransform()

        assertThat(viewport.scale).isEqualTo(1f)
        assertThat(viewport.panX).isEqualTo(0f)
        assertThat(viewport.panY).isEqualTo(0f)
        assertThat(viewport.mapViewToGuest(12f, 34f)).isEqualTo(GuestPoint(12f, 34f))
    }

    @Test
    fun `zoom clamps to scale bounds`() {
        val viewport = ViewportTransform(
            viewWidth = 100,
            viewHeight = 100,
            contentWidth = 100,
            contentHeight = 100,
            maxScale = 2f,
        )

        assertThat(viewport.zoomBy(10f, 50f, 50f).scale).isEqualTo(2f)
        assertThat(viewport.zoomBy(0.1f, 50f, 50f).scale).isEqualTo(1f)
    }

    @Test
    fun `zoom preserves guest point under focal point`() {
        val viewport = ViewportTransform(
            viewWidth = 100,
            viewHeight = 100,
            contentWidth = 200,
            contentHeight = 200,
            maxScale = 4f,
        )

        val zoomed = viewport.zoomBy(2f, 25f, 30f)

        assertThat(zoomed.mapViewToGuest(25f, 30f).x).isWithin(0.001f).of(25f)
        assertThat(zoomed.mapViewToGuest(25f, 30f).y).isWithin(0.001f).of(30f)
    }

    @Test
    fun `pan clamps to scaled content bounds`() {
        val viewport = ViewportTransform(
            viewWidth = 100,
            viewHeight = 100,
            contentWidth = 100,
            contentHeight = 100,
            maxScale = 4f,
        ).zoomBy(2f, 0f, 0f)

        assertThat(viewport.panBy(-500f, -500f).panX).isEqualTo(-100f)
        assertThat(viewport.panBy(-500f, -500f).panY).isEqualTo(-100f)
        assertThat(viewport.panBy(500f, 500f).panX).isEqualTo(0f)
        assertThat(viewport.panBy(500f, 500f).panY).isEqualTo(0f)
    }

    @Test
    fun `ime inset expands upward pan range`() {
        val viewport = ViewportTransform(
            viewWidth = 100,
            viewHeight = 100,
            contentWidth = 100,
            contentHeight = 100,
            imeBottomInsetPx = 40,
        )

        assertThat(viewport.panBy(0f, -100f).panY).isEqualTo(-40f)
    }

    @Test
    fun `reset returns to min scale and clamped pan`() {
        val viewport = ViewportTransform(
            scale = 3f,
            panX = -100f,
            panY = -100f,
            viewWidth = 100,
            viewHeight = 100,
            contentWidth = 100,
            contentHeight = 100,
            minScale = 1.25f,
            maxScale = 4f,
        )

        val reset = viewport.reset()

        assertThat(reset.scale).isEqualTo(1.25f)
        assertThat(reset.panX).isEqualTo(0f)
        assertThat(reset.panY).isEqualTo(0f)
    }
}
