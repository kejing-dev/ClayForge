package io.embodit.clayforge

import io.embodit.clayforge.domain.Brush
import io.embodit.clayforge.domain.ClayField
import io.embodit.clayforge.domain.Layout
import io.embodit.clayforge.domain.Sculptor
import io.embodit.clayforge.domain.StartShape
import io.embodit.clayforge.domain.SurfaceNets
import io.embodit.clayforge.domain.ToolSize
import io.embodit.clayforge.domain.V3
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * QA round 2 q2-2: 刮平 × 方墩 × 射线 (eye-hand / hand ray / controller pointer = relative strokes on the tool head with
 * the depth ray from the head). The same paths as the debug `stroke smooth line|zigzag|circle` command.
 */
class RayScrapeTest {
    private fun meshAll(f: ClayField) = (0 until ClayField.CHUNKS).map { SurfaceNets().mesh(f, it) }

    /** Mesh vertices that moved more than 0.5 mm (positions rounded to 0.1 mm), plus triangle counts. */
    private fun meshDiff(a: ClayField, b: ClayField): Triple<Int, Int, Int> {
        fun keys(f: ClayField): Set<Long> {
            val s = HashSet<Long>()
            for (m in meshAll(f)) for (v in 0 until m.vertexCount) {
                val x = Math.round(m.pos[v * 3] * 2000.0); val y = Math.round(m.pos[v * 3 + 1] * 2000.0); val z = Math.round(m.pos[v * 3 + 2] * 2000.0)
                s += (x + 1000) * 4_000_000L + (y + 1000) * 2000L + (z + 1000)
            }
            return s
        }
        val ka = keys(a); val kb = keys(b)
        return Triple((kb - ka).size, meshAll(a).sumOf { it.triangles }, meshAll(b).sumOf { it.triangles })
    }

    private fun path(kind: String): List<V3> = when (kind) {
        "zigzag" -> (0..40).map { i -> val t = i / 40f; V3(-0.08f + 0.16f * t, 0.03f * (if ((i / 5) % 2 == 0) (i % 5) / 5f else 1 - (i % 5) / 5f) - 0.015f, 0f) }
        "circle" -> (0..48).map { i -> val a = 2 * PI.toFloat() * i / 48; V3(0.05f * cos(a) - 0.05f, 0.05f * sin(a), 0f) }
        else -> (0..30).map { i -> V3(-0.07f + 0.14f * i / 30f, 0f, 0f) }
    }

    /** Knob to the path start (no sculpting), press the head, drag along, release — like the debug `stroke`. */
    private fun rayStroke(kind: String, size: ToolSize, eye: V3?): Pair<ClayField, ClayField> {
        val f0 = ClayField().apply { fromSdf(StartShape.BLOCK::sdf) }
        val f = f0.copy()
        val sc = Sculptor(f).also { it.brush = Brush.SMOOTH; it.size = size; it.eye = eye }
        sc.placeTip(0f, 0.2f)
        val pts = path(kind)
        val start = pts.first()
        sc.pressKnob(); sc.drag(start, 0); sc.release()
        sc.pressHead(1000)
        var prev = V3.ZERO
        for ((i, p) in pts.withIndex()) { val d = p - start; sc.drag(d - prev, 1000L + 50L * (i + 1)); prev = d }
        val step = sc.release()
        assertNotNull("$kind ${size.id}: the stroke is committed (undo grows)", step)
        return f0 to f
    }

    private fun check(kind: String, size: ToolSize, eye: V3?) {
        val (f0, f) = rayStroke(kind, size, eye)
        val (moved, t0, t1) = meshDiff(f0, f)
        val removed = f0.solidCells() - f.solidCells()
        println("METRIC rayScrape block $kind ${size.id} eye=${eye != null} movedVerts=$moved tris $t0→$t1 removedCells=$removed")
        assertTrue("$kind ${size.id}: the mesh changes ($moved vertices moved)", moved >= 300)
        assertTrue("$kind ${size.id}: scraping takes clay off ($removed cells)", removed > 0)
    }

    @Test fun scrapeBlockByRay_line() = check("line", ToolSize.M, Layout.EYE)
    @Test fun scrapeBlockByRay_zigzag() = check("zigzag", ToolSize.M, Layout.EYE)
    @Test fun scrapeBlockByRay_circle() = check("circle", ToolSize.M, Layout.EYE)
    @Test fun scrapeBlockByRay_zigzag_small() = check("zigzag", ToolSize.S, Layout.EYE)
    @Test fun scrapeBlockByRay_zigzag_large() = check("zigzag", ToolSize.L, Layout.EYE)
    @Test fun scrapeBlockByFixedRay_line() = check("line", ToolSize.M, null)
}

/** The scene's order of events: the tip refreshed with the fixed ray (eye = null), then a press with the head's ray. */
class RayScrapeSceneOrderTest {
    @Test fun pressAfterFixedRayRefreshScrapesTheBlock() {
        for (kind in listOf("line", "zigzag", "circle")) {
            val f0 = ClayField().apply { fromSdf(StartShape.BLOCK::sdf) }
            val f = f0.copy()
            val sc = Sculptor(f).also { it.brush = Brush.SMOOTH; it.size = ToolSize.M }
            val pts = when (kind) {
                "zigzag" -> (0..40).map { i -> val t = i / 40f; V3(-0.08f + 0.16f * t, 0.03f * (if ((i / 5) % 2 == 0) (i % 5) / 5f else 1 - (i % 5) / 5f) - 0.015f, 0f) }
                "circle" -> (0..48).map { i -> val a = 2 * PI.toFloat() * i / 48; V3(0.05f * cos(a) - 0.05f, 0.05f * sin(a), 0f) }
                else -> (0..30).map { i -> V3(-0.07f + 0.14f * i / 30f, 0f, 0f) }
            }
            sc.placeTip(0f, 0.2f)
            // knob drag with the head's ray
            sc.eye = Layout.EYE; sc.pressKnob(); sc.drag(pts.first(), 0); sc.release(); sc.eye = null
            // the turntable / reload refreshes the tip along the fixed ray
            sc.refreshTip()
            // the press on the head: the head's ray again
            sc.eye = Layout.EYE
            sc.pressHead(1000)
            var prev = V3.ZERO
            for ((i, p) in pts.withIndex()) { val d = p - pts.first(); sc.drag(d - prev, 1000L + 50L * (i + 1)); prev = d }
            val step = sc.release()
            val removed = f0.solidCells() - f.solidCells()
            println("METRIC rayScrape sceneOrder $kind removedCells=$removed committed=${step != null}")
            assertNotNull("$kind: committed", step)
            assertTrue("$kind: clay taken off ($removed)", removed > 20)
        }
    }
}

/** QA r2 q2-2: a sweep across the block's flat top along the head's ray shaves a visible layer (the blade bites). */
class RayScrapeFlatTopTest {
    private fun topY(f: ClayField, x: Float, z: Float): Float {
        var y = 0.40f
        while (y > 0.08f) { if (f.sample(x, y, z) > 0.5f) return y; y -= 0.0005f }
        return 0f
    }

    @Test fun sweepAcrossTheTopLowersIt() {
        for (size in ToolSize.entries) {
            val f0 = ClayField().apply { fromSdf(StartShape.BLOCK::sdf) }
            val f = f0.copy()
            val sc = Sculptor(f).also { it.brush = Brush.SMOOTH; it.size = size; it.eye = Layout.EYE }
            sc.placeTip(-0.03f, 0.2f)
            val c = sc.toClay(sc.tip)
            sc.pressHead(0)
            for (i in 1..30) sc.drag(V3(0.002f, 0f, 0f), i * 40L)
            assertNotNull("${size.id}: committed", sc.release())
            val drop = topY(f0, 0f, c.z) - topY(f, 0f, c.z)
            println("METRIC rayScrape flatTop ${size.id} tip=$c drop=${"%.1f".format(drop * 1000)}mm")
            assertTrue("${size.id}: the top goes down ${drop * 1000} mm ≥ 0.15 r", drop >= 0.15f * size.r)
        }
    }
}
