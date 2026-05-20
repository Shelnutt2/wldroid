package nu.shel.wldroid.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KeyboardFocusTapTrackerTest {
    @Test
    fun `tap within slop is consumed`() {
        val tracker = KeyboardFocusTapTracker(slopPx = 10f)

        tracker.begin(pointerId = 1, x = 100f, y = 100f)

        assertThat(tracker.consume(pointerId = 1, x = 104f, y = 103f)).isTrue()
    }

    @Test
    fun `movement exactly at slop is still a tap`() {
        val tracker = KeyboardFocusTapTracker(slopPx = 10f)

        tracker.begin(pointerId = 1, x = 100f, y = 100f)

        assertThat(tracker.consume(pointerId = 1, x = 110f, y = 100f)).isTrue()
    }

    @Test
    fun `drag beyond slop is not consumed`() {
        val tracker = KeyboardFocusTapTracker(slopPx = 10f)

        tracker.begin(pointerId = 1, x = 100f, y = 100f)
        tracker.update(pointerId = 1, x = 111f, y = 100f)

        assertThat(tracker.consume(pointerId = 1, x = 111f, y = 100f)).isFalse()
    }

    @Test
    fun `consume with different pointer id cancels tap`() {
        val tracker = KeyboardFocusTapTracker(slopPx = 10f)

        tracker.begin(pointerId = 1, x = 100f, y = 100f)

        assertThat(tracker.consume(pointerId = 2, x = 100f, y = 100f)).isFalse()
        assertThat(tracker.consume(pointerId = 1, x = 100f, y = 100f)).isFalse()
    }

    @Test
    fun `cancel clears pending tap`() {
        val tracker = KeyboardFocusTapTracker(slopPx = 10f)

        tracker.begin(pointerId = 1, x = 100f, y = 100f)
        tracker.cancel()

        assertThat(tracker.consume(pointerId = 1, x = 100f, y = 100f)).isFalse()
    }

    @Test
    fun `consume clears pending tap`() {
        val tracker = KeyboardFocusTapTracker(slopPx = 10f)

        tracker.begin(pointerId = 1, x = 100f, y = 100f)

        assertThat(tracker.consume(pointerId = 1, x = 100f, y = 100f)).isTrue()
        assertThat(tracker.consume(pointerId = 1, x = 100f, y = 100f)).isFalse()
    }

    @Test
    fun `pending pointer id tracks active candidate`() {
        val tracker = KeyboardFocusTapTracker(slopPx = 10f)

        assertThat(tracker.pendingPointerId).isNull()

        tracker.begin(pointerId = 7, x = 100f, y = 100f)
        assertThat(tracker.pendingPointerId).isEqualTo(7)

        tracker.update(pointerId = 7, x = 120f, y = 100f)
        assertThat(tracker.pendingPointerId).isNull()
    }
}
