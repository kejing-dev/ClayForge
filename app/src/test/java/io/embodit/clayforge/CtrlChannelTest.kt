package io.embodit.clayforge

import io.embodit.clayforge.domain.Brush
import io.embodit.clayforge.domain.ClayField
import io.embodit.clayforge.domain.CtrlAct
import io.embodit.clayforge.domain.CtrlChannel
import io.embodit.clayforge.domain.Sculptor
import io.embodit.clayforge.domain.StartShape
import io.embodit.clayforge.domain.ToolSize
import io.embodit.clayforge.domain.TriggerEdges
import io.embodit.clayforge.domain.V3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA round 2 q2-1: an injected trigger and a real trigger go down the same path (TriggerEdges → CtrlChannel →
 * Sculptor.pressAt / dragTo / release), and every tool riding the controller changes the clay (undo grows).
 */
class CtrlChannelTest {
    @Test fun injectedAndRealPressesAreCountedAlike() {
        for (injected in listOf(false, true)) {
            val e = TriggerEdges()
            assertFalse(e.set(1, false, injected))
            assertTrue("rising edge (injected=$injected)", e.set(1, true, injected))
            assertFalse("held is not a new press", e.set(1, true, injected))
            assertTrue(e.held(1))
            assertEquals(1, e.take(1)); assertEquals(0, e.take(1))
            e.set(1, false, injected); assertFalse(e.held(1))
        }
    }

    @Test fun injectedHeldWinsOverTheIdleRealListener() {
        val e = TriggerEdges()
        e.set(1, true, injected = true)
        e.set(1, false)            // the emulator listener keeps saying "up"
        assertTrue(e.held(1)); assertEquals(1, e.take(1))
        e.clearInjected(1); assertFalse(e.held(1))
    }

    @Test fun decisions() {
        fun d(on: Boolean = false, other: Boolean = false, held: Boolean = true, p: Int = 1, own: Boolean = false, slot: Boolean = false, can: Boolean = true, near: Boolean = true) =
            CtrlChannel.decide(on, other, held, p, own, slot, can, near)
        assertEquals(CtrlAct.BEGIN, d())
        assertEquals(CtrlAct.MOVE, d(on = true, p = 0))
        assertEquals(CtrlAct.END, d(on = true, held = false, p = 0))
        assertEquals(CtrlAct.NONE, d(p = 0))                 // held without a new press: no stroke
        assertEquals(CtrlAct.NONE, d(other = true))         // a hand stroke owns the clay
        assertEquals(CtrlAct.PUT_BACK, d(own = true))
        assertEquals(CtrlAct.SWAP, d(slot = true))
        assertEquals(CtrlAct.AWAY, d(near = false))
        assertEquals(CtrlAct.AWAY, d(can = false))
    }

    /** The debug `ctrlstroke <brush>` path: tip 3 mm out, trigger down, 31 tip samples 40 ms apart, trigger up. */
    private fun ctrlStroke(b: Brush, shape: StartShape): Pair<Int, Int> {
        val f0 = ClayField().apply { fromSdf(shape::sdf) }
        val f = f0.copy()
        val sc = Sculptor(f).also { it.brush = b; it.size = ToolSize.M }
        sc.placeTip(0f, 0.25f)
        val front = sc.tip; val n = sc.tipNormal()
        val out = b == Brush.PULL
        val pts = if (out) (0..30).map { i -> front + n * (0.04f * i / 30f) } else (0..30).map { i -> front + V3(-0.05f + 0.1f * i / 30f, 0f, 0f) }
        val e = TriggerEdges()
        var on = false
        var t = 0L
        fun frame(tip: V3, trigger: Boolean) {
            e.set(1, trigger, injected = true)
            val presses = e.take(1)
            val near = presses > 0 && (f.sample(sc.toClay(tip).x, sc.toClay(tip).y, sc.toClay(tip).z) > 0.5f || sc.nearestSurface(tip, sc.size.r * 1.5f + 0.003f) != null)
            when (CtrlChannel.decide(on, false, e.held(1), presses, false, false, true, near)) {
                CtrlAct.BEGIN -> { sc.pressAt(tip, t); on = true }
                CtrlAct.MOVE -> sc.dragTo(tip, t)
                CtrlAct.END -> { sc.release(); on = false }
                else -> {}
            }
            t += 40
        }
        frame(pts[0] + n * 0.002f, false)
        frame(pts[0], true)
        for (p in pts) frame(p, true)
        frame(pts.last(), false)
        val changed = (f.f.indices).count { kotlin.math.abs(f.f[it] - f0.f[it]) > 0.02f }
        return sc.undo.size to changed
    }

    @Test fun everyToolOnTheControllerChangesTheClay() {
        for (shape in StartShape.entries) for (b in Brush.entries) {
            val (undo, changed) = ctrlStroke(b, shape)
            println("METRIC ctrlStroke ${b.id} ${shape.id} undo=$undo changedCells=$changed")
            assertEquals("${b.id} ${shape.id}: one undo step", 1, undo)
            assertTrue("${b.id} ${shape.id}: the clay changes ($changed cells)", changed >= 50)
        }
    }
}
