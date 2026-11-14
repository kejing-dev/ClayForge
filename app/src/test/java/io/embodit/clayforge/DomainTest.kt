package io.embodit.clayforge

import io.embodit.clayforge.domain.Brush
import io.embodit.clayforge.domain.ClayField
import io.embodit.clayforge.domain.Exporter
import io.embodit.clayforge.domain.Glaze
import io.embodit.clayforge.domain.Layout
import io.embodit.clayforge.domain.MergedMesh
import io.embodit.clayforge.domain.Sculptor
import io.embodit.clayforge.domain.StartShape
import io.embodit.clayforge.domain.SurfaceNets
import io.embodit.clayforge.domain.ToolSize
import io.embodit.clayforge.domain.V3
import io.embodit.clayforge.domain.WorkMeta
import io.embodit.clayforge.domain.WorkStore
import io.embodit.clayforge.scene.Hit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs

class DomainTest {
    private fun ball() = ClayField().apply { fromSdf(StartShape.BALL::sdf) }
    private fun tris(f: ClayField) = (0 until ClayField.CHUNKS).sumOf { SurfaceNets().mesh(f, it).triangles }
    private fun stroke(s: Sculptor, from: V3, to: V3, steps: Int = 20) {
        s.placeTip(from.x, from.y); s.pressHead(0)
        for (i in 1..steps) s.drag(V3((to.x - from.x) / steps, (to.y - from.y) / steps, 0f), i * 50L)
        s.release()
    }

    // M3: the tip sits on the front-most 0.5 surface along the view ray, within one cell
    @Test fun tipSitsOnTheFrontSurface() {
        val f = ball(); val s = Sculptor(f)
        for ((x, y) in listOf(0f to 0.2f, 0.05f to 0.22f, -0.05f to 0.2f, 0f to 0.26f)) {
            assertTrue("hit ($x,$y)", s.placeTip(x, y))
            val v = f.sample(s.tip.x, s.tip.y, s.tip.z)
            assertEquals("field at tip ($x,$y)", 0.5f, v, 0.12f)
            // nothing solid between the ray origin and the tip
            val o = V3(s.orgX, s.orgY, 0.30f)
            for (k in 1 until 20) { val p = o.lerp(s.tip, k / 20f); assertTrue(f.sample(p.x, p.y, p.z) < 0.5f) }
        }
    }

    @Test fun tipAfterTurnLandsOnTheNewFace() {
        val f = ClayField().apply { fromSdf { x, y, z -> io.embodit.clayforge.domain.Sdf.ell(x, y, z, 0.03f, 0.18f, 0f, 0.05f, 0.08f, 0.09f) } }
        val s = Sculptor(f)
        assertTrue(s.placeTip(0f, 0.26f)); val z0 = s.tip.z
        s.yaw = (PI / 2).toFloat(); s.refreshTip()
        assertTrue("tip z changes after a quarter turn ($z0 → ${s.tip.z})", abs(s.tip.z - z0) > 0.02f)
        assertEquals(0.5f, f.sample(s.toClay(s.tip).x, s.tip.y, s.toClay(s.tip).z), 0.12f)
    }

    // M3: dragged 20 cm out and back, the tip follows at once (clamped overshoot is dropped)
    @Test fun overshootIsNotAccumulated() {
        val s = Sculptor(ball())
        s.placeTip(0.1f, 0.2f); s.pressKnob()
        s.drag(V3(0.2f, 0f, 0f), 0); assertEquals(Sculptor.X_LIM, s.orgX, 1e-5f)
        s.drag(V3(-0.05f, 0f, 0f), 50); assertEquals(Sculptor.X_LIM - 0.05f, s.orgX, 1e-5f)
        s.release()
    }

    @Test fun addRaisesCarveLowersAtMostOneRadius() {
        val f = ball(); val s = Sculptor(f)
        s.placeTip(0f, 0.15f); val z0 = s.tip.z
        s.brush = Brush.ADD; s.size = ToolSize.M
        stroke(s, V3(-0.02f, 0.15f, 0f), V3(0.02f, 0.15f, 0f))
        s.placeTip(0f, 0.15f)
        val grow = s.tip.z - z0
        assertTrue("add grows ($grow)", grow > 0.004f)
        assertTrue("add is bounded by ~ one radius ($grow)", grow < ToolSize.M.r * 1.3f)
        val f2 = ball(); val c = Sculptor(f2); c.brush = Brush.CARVE
        c.placeTip(0f, 0.15f); val z1 = c.tip.z
        stroke(c, V3(-0.02f, 0.15f, 0f), V3(0.02f, 0.15f, 0f))
        c.placeTip(0f, 0.15f)
        assertTrue("carve digs (${z1 - c.tip.z})", z1 - c.tip.z > 0.003f)
    }

    // M2: mirror makes two changes, one undo brings both back
    @Test fun mirrorAndUndo() {
        val f = ball(); val s = Sculptor(f); s.mirror = true; s.brush = Brush.ADD
        val before = f.f.copyOf()
        stroke(s, V3(0.04f, 0.13f, 0f), V3(0.06f, 0.17f, 0f))
        val l = s.toClay(V3(-0.05f, 0.15f, 0.08f)); val r = s.toClay(V3(0.05f, 0.15f, 0.08f))
        var dl = 0f; var dr = 0f
        for (k in f.f.indices) { val d = f.f[k] - before[k]; if (d != 0f) { val i = k % 64; if (ClayField.px(i) < 0) dl += d else dr += d } }
        assertTrue("both sides grew ($dl, $dr)", dl > 0.5f && dr > 0.5f)
        assertEquals("mirror symmetric", dl, dr, dr * 0.05f)
        assertTrue(s.undoOne())
        var maxDiff = 0f; for (k in f.f.indices) maxDiff = maxOf(maxDiff, abs(f.f[k] - before[k]))
        assertTrue("undo restores ($maxDiff)", maxDiff < 1e-4f)
        assertFalse(s.undoOne())
    }

    // M17: a poke changes the clay for add / carve / smooth, never for pull; it does not change the size
    @Test fun pokeDoesNotChangeSize() {
        for (b in Brush.entries) {
            val f = ball(); val s = Sculptor(f); s.brush = b; s.size = ToolSize.M
            if (b == Brush.SMOOTH) { stroke(Sculptor(f).apply { brush = Brush.CARVE; size = ToolSize.S }, V3(-0.03f, 0.15f, 0f), V3(0.03f, 0.15f, 0f)) }
            s.placeTip(0f, 0.15f)
            val step = s.poke()
            assertNotNull("$b poke", step)
            assertEquals(ToolSize.M, s.size)
        }
    }

    @Test fun pullDrawsClayOutward() {
        val f = ball(); val s = Sculptor(f); s.brush = Brush.PULL; s.size = ToolSize.M
        s.placeTip(0.07f, 0.15f); s.pressHead(0)
        val before = f.solidCells()
        for (i in 1..12) s.drag(V3(0.004f, 0f, 0f), i * 50L)
        s.release()
        assertTrue("pull changes the shape", f.solidCells() != before)
        val tipC = s.toClay(s.tip)
        assertTrue("tip stays in the cylinder", kotlin.math.hypot(tipC.x, tipC.z) <= ClayField.R_MAX + 1e-4f)
    }

    // M7 / S20: budget hysteresis 55k in, 52k out
    @Test fun budgetHysteresis() {
        val s = Sculptor(ball()); var t = 40_000; s.triangles = { t }
        s.updateBudget(); assertFalse(s.tooMuch)
        t = 55_000; s.updateBudget(); assertTrue(s.tooMuch)
        t = 53_000; s.updateBudget(); assertTrue(s.tooMuch)
        t = 52_000; s.updateBudget(); assertFalse(s.tooMuch)
        t = 60_000; s.brush = Brush.ADD; s.placeTip(0f, 0.15f)
        assertNull("add is blocked over budget", s.poke())
        s.brush = Brush.CARVE; assertNotNull("carve still works", s.poke())
    }

    // M7: clay never leaves the cylinder, whatever the strokes
    @Test fun clayStaysInTheCylinder() {
        val f = ball(); val s = Sculptor(f); s.brush = Brush.ADD; s.size = ToolSize.L
        repeat(6) { k -> s.yaw = k.toFloat(); stroke(s, V3(-0.15f, 0.1f + 0.05f * k, 0f), V3(0.15f, 0.12f + 0.05f * k, 0f), 30) }
        val sn = SurfaceNets()
        for (c in 0 until ClayField.CHUNKS) { val m = sn.mesh(f, c)
            for (v in 0 until m.vertexCount) { val x = m.pos[v * 3]; val y = m.pos[v * 3 + 1]; val z = m.pos[v * 3 + 2]
                assertTrue("r ${kotlin.math.hypot(x, z)}", kotlin.math.hypot(x, z) <= Layout.CLAY_R)
                assertTrue("y $y", y >= Layout.CLAY_Y0 - 0.003f && y <= Layout.CLAY_Y1) } }
    }

    // M13: chips outside the clay / kiln cylinders from both eyes
    // M13 panelNotOccluded: nominal eye, every bench box (kiln, open door, clay at the door, chips, lesson panels) is out of the window
    @Test fun panelNotOccluded() {
        assertEquals(emptyList<String>(), Layout.windowOcclusion())
        assertEquals(emptyList<String>(), Layout.windowOcclusion(wM = 0.52f, hM = 0.52f))
    }

    // L5: lesson caption / transport / score and chips clear the clay cylinder, tray, each other and the door sweep
    @Test fun panelsClearEachOther() { assertEquals(emptyList<String>(), Layout.panelOverlaps()) }

    // M7: the tool in hand stays in the volume and off the tray tools at the extreme tips (design activeTool / lowTip)
    @Test fun toolBoundsAtExtremes() {
        val tips = listOf(V3(0.15f, 0.39f, 0f), V3(0.106f, 0.39f, 0.106f), V3(-0.15f, 0.07f, 0f), V3(0f, 0.07f, 0.15f), V3(0.106f, 0.08f, 0.106f), V3(0f, 0.2f, 0.15f), V3(-0.106f, 0.39f, 0.106f))
        for (t in tips) assertEquals("tip $t", emptyList<String>(), Layout.toolProblems(t))
    }

    // M7 / M18: the turntable's L-shaped way into the kiln never cuts a wall; the door's sweep misses tray / chips / home
    @Test fun kilnClearsTheClay() { assertEquals(emptyList<String>(), Layout.kilnProblems()) }

    // D9 / D54: placeUpright only takes the heading; seated 1.2 m and standing 1.6 m both land the bench in reach
    @Test fun placeUprightSeatedAndStanding() {
        for (eyeY in listOf(1.2f, 1.6f)) for (pitch in listOf(-30f, 0f, 30f)) {
            val p = Math.toRadians(pitch.toDouble()).toFloat()
            val fwd = V3(0f, kotlin.math.sin(p), -kotlin.math.cos(p))
            val b = Layout.placeUpright(V3(0.2f, eyeY, 0.1f), fwd)
            assertEquals("eye $eyeY: bench 0.58 under the eye", eyeY - 0.58f, b.origin.y, 1e-4f)
            assertEquals(0.62f, Layout.horiz(b.origin - V3(0.2f, eyeY, 0.1f)), 1e-4f)
            assertEquals("pitch never reaches the yaw", 0f, Layout.angDiffDeg(b.yaw, 0f), 1e-3f)
            // the clay front is within an arm (≤ 0.65 m from the shoulder ~ 0.25 m below the eye)
            val clayFront = b.toWorld(V3(0f, 0.2f, 0.09f)); val shoulder = V3(0.2f, eyeY - 0.25f, 0.1f)
            assertTrue("reach ${clayFront.dist(shoulder)}", clayFront.dist(shoulder) <= 0.65f)
        }
    }

    // profile 2026-10-28 四方位放置: facing N / E / S / W the bench's +z points back at the user (< 10°)
    @Test fun placementFourDirections() {
        for (deg in listOf(0f, 90f, 180f, 270f)) {
            val a = Math.toRadians(deg.toDouble()).toFloat()
            val fwd = V3(-kotlin.math.sin(a), 0f, -kotlin.math.cos(a))
            val head = V3(1f, 1.5f, -2f)
            val b = Layout.placeUpright(head, fwd)
            val toUser = (head - b.origin).let { V3(it.x, 0f, it.z).normalized() }
            val benchZ = b.dirToWorld(V3(0f, 0f, 1f))
            assertTrue("facing $deg°", benchZ.dot(toUser) > kotlin.math.cos(Math.toRadians(10.0)).toFloat())
        }
    }

    // design bench snap: in at 6 cm / 10°, out only past 9 cm / 15°
    @Test fun benchSnapHysteresis() {
        val home = Layout.BenchPose(V3(0f, 0.9f, -0.6f), 0f); val s = Layout.BenchSnap()
        assertFalse(s.step(Layout.BenchPose(V3(0.07f, 0.9f, -0.6f), 0f), home))
        assertTrue(s.step(Layout.BenchPose(V3(0.05f, 0.9f, -0.6f), 0f), home))
        assertTrue("stays snapped at 8 cm", s.step(Layout.BenchPose(V3(0.08f, 0.9f, -0.6f), 0f), home))
        assertFalse("lets go past 9 cm", s.step(Layout.BenchPose(V3(0.095f, 0.9f, -0.6f), 0f), home))
        assertFalse(s.step(Layout.BenchPose(V3(0.0f, 0.9f, -0.6f), Math.toRadians(12.0).toFloat()), home))
    }

    // D17: bench tap targets ≥ 4 cm and ≥ 2° from the nominal eye (Full Space ~0.75 m); gaps ≥ 1.5 cm and ≥ 0.3 × target
    @Test fun tapTargetsAreBigEnough() {
        val dist = 0.80f // design tapTargets: Full Space viewing distance 0.65–0.8 m
        fun deg(m: Float) = Math.toDegrees(2 * kotlin.math.atan((m / 2 / dist).toDouble())).toFloat()
        val targets = mapOf("tool w" to Layout.TOOL_BOX.x, "tool h" to Layout.TOOL_BOX.y, "head" to Layout.HEAD_D, "knob" to Layout.KNOB_D, "jar" to Layout.JAR_D, "chip w" to Layout.CHIP_W, "chip h" to Layout.CHIP_H)
        for ((n, s) in targets) { assertTrue("$n ${s}m", s >= 0.04f); assertTrue("$n ${deg(s)}°", deg(s) >= 2f) }
        val toolGap = Layout.TOOL_X[1] - Layout.TOOL_X[0] - Layout.TOOL_BOX.x
        assertTrue(toolGap >= 0.015f && toolGap >= 0.3f * Layout.TOOL_BOX.x)
        val jarGap = Layout.JAR_X[1] - Layout.JAR_X[0] - Layout.JAR_D
        assertTrue("jar gap $jarGap", jarGap >= 0.015f && jarGap >= 0.3f * Layout.JAR_D - 1e-4f)
        val chipGap = Layout.CHIP_MIRROR.x - Layout.CHIP_TURN.x - Layout.CHIP_W
        assertTrue("chip gap $chipGap", chipGap >= 0.015f && chipGap >= 0.018f - 1e-4f)
        val tGapX = Layout.TRANSPORT[1].x - Layout.TRANSPORT[0].x - Layout.CHIP_W
        val tGapY = Layout.TRANSPORT[0].y - Layout.TRANSPORT[2].y - Layout.CHIP_H
        assertTrue("transport gaps $tGapX / $tGapY ≥ 2 cm", tGapX >= 0.02f - 1e-4f && tGapY >= 0.02f - 1e-4f)
    }

    // M6: GLB header / chunks and STL triangle count match the mesh
    @Test fun exportFormats() {
        val f = ball()
        val m = MergedMesh.of((0 until ClayField.CHUNKS).map { SurfaceNets().mesh(f, it) })
        val glb = Exporter.glb(m, Glaze.CELADON.argb, 0.25f, "test")
        val bb = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546C67, bb.getInt(0)); assertEquals(2, bb.getInt(4)); assertEquals(glb.size, bb.getInt(8))
        val jsonLen = bb.getInt(12); assertEquals(0x4E4F534A, bb.getInt(16))
        val json = String(glb, 20, jsonLen)
        assertTrue(json.contains("\"count\":${m.idx.size}"))
        val stl = Exporter.stl(m, "test")
        assertEquals(m.triangles, ByteBuffer.wrap(stl).order(ByteOrder.LITTLE_ENDIAN).getInt(80))
        assertEquals(84 + m.triangles * 50, stl.size)
    }

    @Test fun workStoreRoundTrip() {
        val dir = createTempDir("clayforge")
        val store = WorkStore(dir)
        val f = ball(); val id = store.newId()
        store.save(WorkMeta(id, "ball", fired = true, glaze = "junRed"), f)
        val g = ClayField(); val m = store.load(id, g)
        assertEquals("junRed", m.glaze); assertTrue(m.fired)
        var maxDiff = 0f; for (k in f.f.indices) maxDiff = maxOf(maxDiff, abs(f.f[k] - g.f[k]))
        assertTrue(maxDiff < 0.003f)
        assertEquals(1, store.list().size)
        store.corrupt(id)
        assertTrue(runCatching { store.load(id, ClayField()) }.isFailure)
        dir.deleteRecursively()
    }

    // build.md 命中回放: logged hit paths resolve to targets (GLB children walk up to their holder)
    @Test fun hitReplay() {
        assertEquals(Hit.Slot(Brush.CARVE), Hit.resolvePath(listOf("mesh_0", "toolModel:carve", "slot:carve")))
        assertEquals(Hit.Jar(Glaze.JUN_RED), Hit.resolvePath(listOf("node0", "jar:junRed")))
        assertEquals(Hit.Head, Hit.resolvePath(listOf("head")))
        assertEquals(Hit.Knob, Hit.resolvePath(listOf("knobBead", "knob")))
        assertEquals(Hit.Clay, Hit.resolvePath(listOf("clayProxy", "turn")))
        assertEquals(Hit.Ring, Hit.resolvePath(listOf("ring")))
        assertNull(Hit.resolvePath(listOf("ttTop", "turn", "benchRoot")))
        assertEquals(Hit.Bench, Hit.resolvePath(listOf("benchEdge:2", "bench")))
    }
}

class ExportMinMaxTest {
    @Test fun accessorBoundsAreExact() {
        val f = ClayField().apply { fromSdf(StartShape.EGG::sdf) }
        val m = MergedMesh.of((0 until ClayField.CHUNKS).map { SurfaceNets().mesh(f, it) })
        val glb = Exporter.glb(m, Glaze.CELADON.argb, 0.25f, "t")
        val bb = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        val json = String(glb, 20, bb.getInt(12))
        val min = Regex("\"min\":\\[([^\\]]+)]").find(json)!!.groupValues[1].split(",").map { it.toFloat() }
        val binStart = 20 + bb.getInt(12) + 8
        var mx = Float.MAX_VALUE
        for (v in 0 until m.pos.size / 3) mx = minOf(mx, bb.getFloat(binStart + v * 12))
        assertEquals(mx, min[0], 0f)
    }
}
