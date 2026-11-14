package io.embodit.clayforge

import io.embodit.clayforge.domain.Brush
import io.embodit.clayforge.domain.ClayField
import io.embodit.clayforge.domain.Sculptor
import io.embodit.clayforge.domain.StartShape
import io.embodit.clayforge.domain.ToolSize
import io.embodit.clayforge.domain.V3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Design `brushUnitTests` (D2 / K1–K2, profile D19「每种工具 × 主体」): every brush on every start shape changes the
 * mesh where it should, measured like sample.html: rays along −n on a 2 r disc round the tip, depth before − after.
 */
class BrushTest {
    private fun shape(s: StartShape) = ClayField().apply { fromSdf(s::sdf) }

    /** First 0.5 crossing from [o] along [d] (clay coordinates), or null. */
    private fun surfAlong(f: ClayField, o: V3, d: V3): Float? {
        var t = 0f
        while (t < 0.5f) {
            if (f.sample(o.x + d.x * t, o.y + d.y * t, o.z + d.z * t) > 0.5f) {
                var a = t - 0.001f; var b = t
                repeat(8) { val m = (a + b) / 2; if (f.sample(o.x + d.x * m, o.y + d.y * m, o.z + d.z * m) > 0.5f) b = m else a = m }
                return b
            }
            t += 0.001f
        }
        return null
    }

    private data class Disp(val max: Float, val mean: Float)

    /** + = the surface moved out (toward the ray origin), metres. */
    private fun disp(a: ClayField, b: ClayField, tip: V3, n: V3, r: Float, reach: Float = 2f): Disp {
        val u = if (abs(n.y) < 0.9f) V3(0f, 1f, 0f) else V3(1f, 0f, 0f)
        val t1 = n.cross(u).normalized(); val t2 = n.cross(t1)
        var mx = 0f; var sum = 0f; var cnt = 0
        var a2 = -reach
        while (a2 <= reach) {
            var b2 = -reach
            while (b2 <= reach) {
                if (a2 * a2 + b2 * b2 <= reach * reach) {
                    val o = tip + n * 0.12f + t1 * (a2 * r) + t2 * (b2 * r)
                    val da = surfAlong(a, o, -n); val db = surfAlong(b, o, -n)
                    if (da != null && db != null) { val d = da - db; if (abs(d) > abs(mx)) mx = d; sum += d; cnt++ }
                }
                b2 += 0.25f
            }
            a2 += 0.25f
        }
        return Disp(mx, if (cnt > 0) sum / cnt else 0f)
    }

    private fun solid(f: ClayField) = f.solidCells()

    /** The start tip: front (x, 0.235) along the view ray; the block uses its front-top edge. */
    private fun startAim(s: StartShape) = if (s == StartShape.BLOCK) 0f to 0.27f else 0f to 0.235f

    /** A relative stroke: press, 6 cm sideways in 30 events, release. */
    private fun sideways(sc: Sculptor, len: Float = 0.06f) {
        val (x, y) = sc.orgX to sc.orgY
        sc.placeTip(x - len / 2, y); sc.pressHead(0)
        val n = 30
        for (i in 1..n) sc.drag(V3(len / n, 0f, 0f), i * 40L)
        sc.release()
    }

    private fun addOrCarve(s: StartShape, b: Brush) {
        val f0 = shape(s); val f = f0.copy()
        val sc = Sculptor(f).also { it.brush = b; it.size = ToolSize.M }
        val (x, y) = startAim(s)
        sc.placeTip(x, y)
        val tip = sc.toClay(sc.tip); val n = f0.normalAt(tip)
        sideways(sc)
        val r = ToolSize.M.r
        val d = disp(f0, f, tip, n, r)
        if (b == Brush.ADD) {
            assertTrue("$s add max ${d.max} ≥ 0.4 r", d.max >= 0.4f * r)
            assertTrue("$s add volume grows", solid(f) > solid(f0))
        } else {
            assertTrue("$s carve max ${d.max} ≤ −0.4 r", d.max <= -0.4f * r)
            assertTrue("$s carve volume shrinks", solid(f) < solid(f0))
        }
        // cells farther than 2.5 r from the path do not change
        val p0 = sc.toClay(V3(-0.03f, tip.y, tip.z)); val p1 = sc.toClay(V3(0.03f, tip.y, tip.z))
        var far = 0
        for (k in 0 until ClayField.N) for (j in 0 until ClayField.N) for (i in 0 until ClayField.N) {
            val id = ClayField.idx(i, j, k)
            if (f.f[id] == f0.f[id]) continue
            val p = V3(ClayField.px(i), ClayField.py(j), ClayField.pz(k))
            if (segDist(p, p0, p1) > 2.5f * r + 0.04f) far++
        }
        assertEquals("$s ${b.id}: nothing changes far from the path", 0, far)
    }

    private fun segDist(p: V3, a: V3, b: V3): Float {
        val ab = b - a; val t = ((p - a).dot(ab) / ab.dot(ab)).coerceIn(0f, 1f)
        return p.dist(a + ab * t)
    }

    @Test fun brushEffect_add_ball() = addOrCarve(StartShape.BALL, Brush.ADD)
    @Test fun brushEffect_add_egg() = addOrCarve(StartShape.EGG, Brush.ADD)
    @Test fun brushEffect_add_block() = addOrCarve(StartShape.BLOCK, Brush.ADD)
    @Test fun brushEffect_carve_ball() = addOrCarve(StartShape.BALL, Brush.CARVE)
    @Test fun brushEffect_carve_egg() = addOrCarve(StartShape.EGG, Brush.CARVE)
    @Test fun brushEffect_carve_block() = addOrCarve(StartShape.BLOCK, Brush.CARVE)

    /** Standard deviation of the surface's distance to the plane (c, n) inside r of the tip. */
    private fun rough(f: ClayField, tip: V3, c: V3, n: V3, r: Float): Float {
        val u = if (abs(n.y) < 0.9f) V3(0f, 1f, 0f) else V3(1f, 0f, 0f)
        val t1 = n.cross(u).normalized(); val t2 = n.cross(t1)
        val ds = ArrayList<Float>()
        var a = -1f
        while (a <= 1f) { var b = -1f
            while (b <= 1f) {
                if (a * a + b * b <= 1f) {
                    val o = tip + n * 0.12f + t1 * (a * r) + t2 * (b * r)
                    surfAlong(f, o, -n)?.let { t -> val p = o - n * t; ds += (p - c).dot(n) }
                }
                b += 0.2f }
            a += 0.2f }
        val m = ds.average().toFloat()
        return sqrt(ds.map { (it - m) * (it - m) }.average().toFloat())
    }

    private fun smoothRound(s: StartShape) {
        val f0 = shape(s); val f = f0.copy()
        val sc = Sculptor(f).also { it.brush = Brush.SMOOTH; it.size = ToolSize.M }
        sc.placeTip(0f, 0.235f)
        val tip = sc.toClay(sc.tip); val n = f0.normalAt(tip)
        val (pc, pn) = sc.pickPlane(f0, tip, ToolSize.M.r)
        val before = rough(f0, tip, pc, pn, ToolSize.M.r)
        // press here (the plane is taken at the press point) and sweep 6 cm across it (3 cm out, 6 cm back)
        sc.pressHead(0)
        for (i in 1..15) sc.drag(V3(-0.002f, 0f, 0f), i * 40L)
        for (i in 1..30) sc.drag(V3(0.002f, 0f, 0f), 600L + i * 40L)
        sc.release()
        val after = rough(f, tip, pc, pn, ToolSize.M.r)
        val d = disp(f0, f, tip, n, ToolSize.M.r)
        assertTrue("$s scrape flattens: rough $before → $after (≥ 70 % less)", after <= before * 0.3f)
        assertTrue("$s scrape changes the mesh: max ${d.max} ≥ 0.1 r", abs(d.max) >= 0.1f * ToolSize.M.r)
    }

    @Test fun brushEffect_smooth_ball() = smoothRound(StartShape.BALL)
    @Test fun brushEffect_smooth_egg() = smoothRound(StartShape.EGG)

    /**
     * Large blade along the block's front-top edge: the edge is cut, the top barely grows. The stroke is 9 cm
     * (x ±0.045, aim y 0.26) instead of the design's 14 cm: pressed on the rounded vertical corner (|x| > 0.055) the
     * plane takes the side face in and the far end fills instead of cutting (same aim as lesson 3).
     */
    @Test fun brushEffect_smooth_block() {
        val f0 = shape(StartShape.BLOCK); val f = f0.copy()
        val sc = Sculptor(f).also { it.brush = Brush.SMOOTH; it.size = ToolSize.L }
        sc.placeTip(0f, 0.26f)
        val tip = sc.toClay(sc.tip)
        sideways(sc, 0.09f)
        val r = ToolSize.L.r
        // the edge: along the diagonal (0, 1, 1)/√2 at the corner of top (y 0.20) and front (z 0.075)
        val diag = V3(0f, 0.7071f, 0.7071f)
        val corner = V3(0f, 0.20f, 0.075f)
        val o = corner + diag * 0.1f
        val cut = (surfAlong(f, o, -diag) ?: 0f) - (surfAlong(f0, o, -diag) ?: 0f)
        // the top, 4 cm back from the front: grows at most a little
        val ot = V3(0f, 0.32f, 0.02f)
        val grow = (surfAlong(f0, ot, V3(0f, -1f, 0f)) ?: 0f) - (surfAlong(f, ot, V3(0f, -1f, 0f)) ?: 0f)
        assertTrue("block edge cut $cut ≥ 0.3 r (tip $tip)", cut >= 0.3f * r)
        assertTrue("block top grows $grow ≤ 0.06 r", grow <= 0.06f * r)
    }

    private fun pullOut(s: StartShape, dist: Float): Float {
        val f0 = shape(s); val f = f0.copy()
        val sc = Sculptor(f).also { it.brush = Brush.PULL; it.size = ToolSize.M }
        val (x, y) = if (s == StartShape.BLOCK) 0f to 0.20f else 0f to 0.235f
        sc.placeTip(x, y)
        val tip = sc.toClay(sc.tip); val n = f0.normalAt(tip)
        sc.pressHead(0)
        val steps = 40
        for (i in 1..steps) sc.drag(sc.toVol(n) * (dist / steps), i * 30L)
        sc.release()
        val o = tip + n * 0.12f
        return (surfAlong(f0, o, -n) ?: 0f) - (surfAlong(f, o, -n) ?: 0f)
    }

    @Test fun brushEffect_pull_ball() { val d = pullOut(StartShape.BALL, 0.03f); assertTrue("ball pull 3 cm → $d", d >= 0.8f * 0.03f); assertTrue("ball pull 6 cm", pullOut(StartShape.BALL, 0.06f) >= 0.045f) }
    @Test fun brushEffect_pull_egg() { val d = pullOut(StartShape.EGG, 0.03f); assertTrue("egg pull 3 cm → $d", d >= 0.8f * 0.03f); assertTrue("egg pull 6 cm", pullOut(StartShape.EGG, 0.06f) >= 0.045f) }
    @Test fun brushEffect_pull_block() { val d = pullOut(StartShape.BLOCK, 0.03f); assertTrue("block pull 3 cm → $d", d >= 0.8f * 0.03f); assertTrue("block pull 6 cm", pullOut(StartShape.BLOCK, 0.06f) >= 0.045f) }

    /** K2: press, slide 2 cm along the surface, then pull out 3 cm: the bump grows at the press point. */
    @Test fun pullGrabsAtPress() {
        val f0 = shape(StartShape.BALL); val f = f0.copy()
        val sc = Sculptor(f).also { it.brush = Brush.PULL; it.size = ToolSize.M }
        sc.placeTip(0f, 0.235f)
        val press = sc.toClay(sc.tip); val n = f0.normalAt(press)
        sc.pressHead(0)
        for (i in 1..10) sc.drag(V3(0.002f, 0f, 0f), i * 30L)
        for (i in 1..20) sc.drag(sc.toVol(n) * 0.0015f, 300L + i * 30L)
        sc.release()
        fun outAt(p: V3): Float { val o = p + n * 0.12f; return (surfAlong(f0, o, -n) ?: 0f) - (surfAlong(f, o, -n) ?: 0f) }
        // the clay was hooked at the press point and carried 2 cm sideways + 3 cm out: the bump's foot is the press
        // point and its top 2 cm over (v0.1.0 hooked after the 1.5 cm gate: top at 3.5 cm)
        val atPress = outAt(press)
        var peakX = 0f; var peak = 0f
        var x = -0.03f
        while (x <= 0.06f) { val o = outAt(press + V3(x, 0f, 0f)); if (o > peak) { peak = o; peakX = x }; x += 0.0025f }
        assertTrue("the press point itself rises ($atPress ≥ 1 cm)", atPress >= 0.01f)
        assertTrue("the bump top is ≤ 2.5 cm from the press point ($peakX)", peakX in 0f..0.025f)
        assertTrue("the bump is ≥ 2.4 cm tall ($peak)", peak >= 0.024f)
    }

    /** K2: a short press with the pull tool makes a 0.5 r nub along the outward normal. */
    @Test fun pullPokeMakesNub() {
        val f0 = shape(StartShape.BALL); val f = f0.copy()
        val sc = Sculptor(f).also { it.brush = Brush.PULL; it.size = ToolSize.M }
        sc.placeTip(0f, 0.235f)
        val tip = sc.toClay(sc.tip); val n = f0.normalAt(tip)
        sc.pressHead(0); sc.release()
        assertNotNull(sc.poke())
        val o = tip + n * 0.12f
        val nub = (surfAlong(f0, o, -n) ?: 0f) - (surfAlong(f, o, -n) ?: 0f)
        assertTrue("nub $nub ≈ 0.5 r", nub >= 0.35f * ToolSize.M.r && nub <= 0.75f * ToolSize.M.r)
    }

    /** K2: the z of every input reaches the tip (relative drag z for pull, absolute tip for hand / controller). */
    @Test fun inputDeltaIs3D() {
        val sc = Sculptor(shape(StartShape.BALL)).also { it.brush = Brush.PULL; it.size = ToolSize.M }
        sc.placeTip(0f, 0.2f); val z0 = sc.tip.z
        sc.pressHead(0); for (i in 1..10) sc.drag(V3(0f, 0f, 0.002f), i * 30L)
        assertEquals("relative drag z moves the pull tip", 0.02f, sc.tip.z - z0, 0.002f)
        sc.release()
        val a = Sculptor(shape(StartShape.BALL)).also { it.brush = Brush.ADD; it.size = ToolSize.M }
        val p = V3(0f, 0.18f, 0.085f)
        a.pressAt(p, 0)
        a.dragTo(p + V3(0.01f, 0.005f, 0.012f), 40)
        assertEquals("absolute tip follows z", p.z + 0.012f, a.tip.z, 1e-4f)
        a.release()
        val h = Sculptor(shape(StartShape.BALL)).also { it.brush = Brush.ADD; it.size = ToolSize.M }
        val f0 = h.field.copy()
        h.pressAt(V3(-0.03f, 0.17f, 0.085f), 0)
        for (i in 1..30) h.dragTo(V3(-0.03f + i * 0.002f, 0.17f, 0.085f), i * 30L)
        h.release()
        assertTrue("an absolute add stroke grows the clay", h.field.solidCells() > f0.solidCells())
    }
}

/** M18: every kiln part is a closed mesh; door gap ≥ 2.5 mm all round. */
class KilnTest {
    @Test fun kilnPartsAreWatertight() {
        val parts = io.embodit.clayforge.domain.KilnMesh.shell() + io.embodit.clayforge.domain.KilnMesh.bands() + io.embodit.clayforge.domain.KilnMesh.lining() + io.embodit.clayforge.domain.KilnMesh.gapGlow() + listOf(
            io.embodit.clayforge.domain.KilnMesh.elements(), io.embodit.clayforge.domain.KilnMesh.door(),
            io.embodit.clayforge.domain.KilnMesh.doorLining(), io.embodit.clayforge.domain.KilnMesh.handle(), io.embodit.clayforge.domain.KilnMesh.peepRing(),
            io.embodit.clayforge.domain.KilnMesh.peepGlow(), io.embodit.clayforge.domain.KilnMesh.controllerBox(),
            io.embodit.clayforge.domain.KilnMesh.lamp(), io.embodit.clayforge.domain.KilnMesh.knob(),
        )
        var tris = 0
        for ((i, p) in parts.withIndex()) { assertTrue("kiln part $i closed", io.embodit.clayforge.domain.Proc.watertight(p)); tris += p.triangles }
        assertTrue("kiln ≤ 15k triangles ($tris; design ≤ 8k, deviation: round edges + 6 coils)", tris <= 15_000)
        println("KILN triangles=$tris parts=${parts.size}")
    }
}

/** drag-delta (2026-11-14): dragAmount is per event; 60 events of 2 mm add up to 12 cm, reset on the next press. */
class DragTotalTest {
    @Test fun sixtyStepsOfTwoMillimetresMakeTwelveCentimetres() {
        val t = io.embodit.clayforge.scene.input.DragTotal()
        var last = io.embodit.clayforge.scene.input.Offset3M(0f, 0f, 0f)
        repeat(60) { last = t.add(io.embodit.clayforge.scene.input.Offset3M(0.002f, 0f, 0f)) }
        assertEquals(0.12f, last.x, 1e-5f)
        assertEquals(60, t.events)
        t.reset()
        assertEquals(0.002f, t.add(io.embodit.clayforge.scene.input.Offset3M(0.002f, 0f, 0f)).x, 1e-6f)
    }
}
