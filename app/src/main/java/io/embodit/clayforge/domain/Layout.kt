package io.embodit.clayforge.domain

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where everything sits on the work bench (design `spatialLayout`, `tapTargets`, r4/r5). Bench frame W: metres, origin
 * = turntable bottom centre on the bench top, +y up, +z toward the user. One place, so the scene, the bounds check and
 * the unit tests agree.
 */
object Layout {
    // turntable
    const val TT_TOP_Y = 0.07f; const val TT_R = 0.15f; const val TT_THICK = 0.025f; const val TT_BASE_R = 0.10f
    // clay cylinder (design)
    const val CLAY_R = 0.15f; const val CLAY_Y0 = 0.07f; const val CLAY_Y1 = 0.39f

    // bench (virtual walnut board): top y = 0
    const val BENCH_X0 = -0.74f; const val BENCH_X1 = 0.50f; const val BENCH_Z0 = -0.65f; const val BENCH_Z1 = 0.30f
    const val BENCH_THICK = 0.03f
    /** The outer band you grab to move the bench. */
    const val BENCH_EDGE = 0.06f

    // electric kiln (front door, design spatialLayout.kiln / assets.kiln)
    val KILN_C = V3(-0.47f, 0f, -0.40f)
    const val KILN_W = 0.46f; const val KILN_H = 0.52f; const val KILN_D = 0.46f; const val KILN_WALL = 0.045f; const val KILN_CORNER = 0.03f
    /** Door opening (in the +z face at z = −0.17). */
    const val KILN_HOLE_X0 = -0.64f; const val KILN_HOLE_X1 = -0.30f; const val KILN_HOLE_Y1 = 0.42f
    val KILN_FRONT_Z = KILN_C.z + KILN_D / 2
    /** Chamber inside the walls. */
    val KILN_IN_X0 = KILN_C.x - KILN_W / 2 + KILN_WALL; val KILN_IN_X1 = KILN_C.x + KILN_W / 2 - KILN_WALL
    val KILN_IN_Z0 = KILN_C.z - KILN_D / 2 + KILN_WALL; val KILN_IN_Z1 = KILN_FRONT_Z - KILN_WALL
    const val KILN_IN_Y1 = 0.43f
    // door: 0.40 × 0.45 × 0.05 hung on the left (hinge x −0.67), opens outward to the left 110°
    const val DOOR_W = 0.40f; const val DOOR_H = 0.45f; const val DOOR_T = 0.05f
    val HINGE = V3(-0.67f, 0f, KILN_C.z + KILN_D / 2)
    const val DOOR_OPEN_DEG = 110f
    /** Turntable stops on its L-shaped way into the kiln. */
    val CARRIAGE_MID = V3(-0.47f, 0f, 0f)
    val CARRIAGE_IN = V3(-0.47f, 0f, -0.40f)

    // tray and tools
    val TRAY = V3(0f, 0.02f, 0.24f)
    const val TRAY_TILT_DEG = 15f
    val TOOL_X = floatArrayOf(-0.18f, -0.06f, 0.06f, 0.18f)
    const val TOOL_Y = 0.045f; const val TOOL_Z = 0.24f
    /** Tap box of a tool slot (w × h × d). */
    val TOOL_BOX = V3(0.08f, 0.065f, 0.07f)
    const val TOOL_LEN = 0.11f
    val JAR_X = floatArrayOf(-0.205f, -0.123f, -0.041f, 0.041f, 0.123f, 0.205f)
    const val JAR_Y = 0.05f; const val JAR_D = 0.062f; const val JAR_H = 0.06f

    // the tool on the clay (eye-hand / pointer)
    val HANDLE = V3(0.35f, 0.30f, 0.89f).normalized()
    val HANDLE_LOW = V3(0.35f, 0.55f, 0.76f).normalized()
    const val LOW_TIP_Y = 0.11f
    const val HEAD_LEN = 0.06f; const val HEAD_LEN_LOW = 0.02f; const val HEAD_D = 0.065f
    const val KNOB_D = 0.065f; const val KNOB_AT = 0.12f
    /** Controller tool: the tip 0.10 m ahead of the grip along the controller (design container.inputs.controllerRay). */
    const val CTRL_TOOL_LEN = 0.10f

    // bench chips (attachment panels): centre, 0.10 × 0.06 m, facing +z tilted back 25°
    val CHIP_TURN = V3(0.31f, 0.035f, 0.265f)
    /** [对称] (r5) — and [打开面板] in its place while the control window is closed. */
    val CHIP_MIRROR = V3(0.43f, 0.035f, 0.265f)
    const val CHIP_W = 0.10f; const val CHIP_H = 0.06f; const val CHIP_TILT_DEG = 25f

    // lesson panels (design lessons.watch.caption / transport, tryIt.score)
    val CAPTION = V3(0.30f, 0.31f, 0.03f); const val CAPTION_W = 0.30f; const val CAPTION_H = 0.105f; const val CAPTION_YAW_DEG = -18f
    val TRANSPORT = listOf(V3(0.25f, 0.205f, 0.06f), V3(0.37f, 0.205f, 0.06f), V3(0.25f, 0.125f, 0.06f), V3(0.37f, 0.125f, 0.06f))
    val SCORE = V3(-0.24f, 0.36f, 0.04f); const val SCORE_W = 0.14f; const val SCORE_H = 0.055f

    /** Nominal eye in W (design occlusionCheck): 0.62 m in front, 0.58 m above the bench top. */
    val EYE = V3(0f, 0.58f, 0.62f)
    /** The control window: system default 1.75 m straight ahead of the eye. */
    const val WINDOW_DIST = 1.75f

    fun handleFor(tipY: Float) = if (tipY < LOW_TIP_Y) HANDLE_LOW else HANDLE
    fun headLenFor(tipY: Float) = if (tipY < LOW_TIP_Y) HEAD_LEN_LOW else HEAD_LEN
    fun knobCentre(tip: V3) = tip + handleFor(tip.y) * KNOB_AT

    // ------------------------------------------------------------------------------------------------ placement
    /** The bench in Stage space: origin (bench top under the turntable centre) and yaw (W +z = −head forward). */
    data class BenchPose(val origin: V3, val yaw: Float) {
        fun toWorld(p: V3) = origin + p.rotY(yaw)
        fun toLocal(w: V3) = (w - origin).rotY(-yaw)
        fun dirToLocal(d: V3) = d.rotY(-yaw)
        fun dirToWorld(d: V3) = d.rotY(yaw)
    }

    /** Yaw (radians) that turns W's +z toward a head looking along [forward] (only the horizontal part counts, D9). */
    fun yawFacing(forward: V3): Float = atan2(-forward.x, -forward.z)

    /**
     * placeUpright (design container.placement, profile D9 / D54): only the head's horizontal heading; bench 0.62 m in
     * front, top at head height − 0.58 m clamped to 0.55…1.15 m above the Stage floor (seated 1.20 m → 0.62).
     */
    fun placeUpright(head: V3, forward: V3): BenchPose {
        var f = V3(forward.x, 0f, forward.z)
        if (f.length < 1e-4f) f = V3(0f, 0f, -1f)
        f = f.normalized()
        val y = clampf(head.y - 0.58f, 0.55f, 1.15f)
        return BenchPose(V3(head.x + f.x * 0.62f, y, head.z + f.z * 0.62f), yawFacing(f))
    }

    // snapping to the default frame while moving the bench (design bench: in at 6 cm / 10°, out at 9 cm / 15°)
    class BenchSnap {
        var snapped = false; private set
        fun step(cur: BenchPose, home: BenchPose): Boolean {
            val d = horiz(cur.origin - home.origin)
            val a = abs(angDiffDeg(cur.yaw, home.yaw))
            snapped = if (snapped) !(d > 0.09f || a > 15f) else (d <= 0.06f && a <= 10f)
            return snapped
        }
    }

    fun horiz(v: V3) = sqrt(v.x * v.x + v.z * v.z)
    fun angDiffDeg(a: Float, b: Float): Float { var d = Math.toDegrees((a - b).toDouble()).toFloat(); while (d > 180f) d -= 360f; while (d <= -180f) d += 360f; return d }

    // ------------------------------------------------------------------------------------------------ kiln
    /** Door angle (degrees open) → the door's four bottom / top corners' x-z footprint (W). */
    fun doorCorners(openDeg: Float): List<V3> {
        val a = Math.toRadians(openDeg.toDouble()).toFloat()
        // closed door spans x hinge → hinge + DOOR_W in front of the face; opening swings the free edge out (+z) and left
        val dir = V3(cos(a), 0f, sin(a))
        val out = V3(-sin(a), 0f, cos(a))
        val h = HINGE
        return listOf(h, h + dir * DOOR_W, h + out * DOOR_T, h + dir * DOOR_W + out * DOOR_T)
    }

    /** Carriage (turntable + clay) position along the L path, u 0 … 1 (0 = home, 0.5 = at the door, 1 = inside). */
    fun carriageAt(u: Float): V3 = if (u <= 0.5f) V3.ZERO.lerp(CARRIAGE_MID, u / 0.5f) else CARRIAGE_MID.lerp(CARRIAGE_IN, (u - 0.5f) / 0.5f)

    /** Does the turntable + clay cylinder (r 0.15, y 0 … 0.39) at carriage [c] cut a kiln wall? */
    fun cylinderHitsKiln(c: V3): List<String> {
        val bad = ArrayList<String>()
        val r = CLAY_R
        val outsideShell = c.x + r < KILN_C.x - KILN_W / 2 || c.x - r > KILN_C.x + KILN_W / 2 || c.z + r < KILN_C.z - KILN_D / 2 || c.z - r > KILN_FRONT_Z
        if (outsideShell) return bad
        // inside the shell's footprint: must be inside the chamber (x / z), or passing through the door hole
        val inChamberX = c.x - r >= KILN_IN_X0 - 1e-4f && c.x + r <= KILN_IN_X1 + 1e-4f
        val inChamberZ = c.z - r >= KILN_IN_Z0 - 1e-4f
        val inHoleX = c.x - r >= KILN_HOLE_X0 - 1e-4f && c.x + r <= KILN_HOLE_X1 + 1e-4f
        if (!(inChamberX && inHoleX && inChamberZ)) bad += "cylinder at $c cuts the kiln wall"
        if (CLAY_Y1 > KILN_HOLE_Y1) bad += "clay top above the door hole"
        return bad
    }

    /** M7 / M18: the carriage's whole way in and out, and the door's sweep vs tray and chips. */
    fun kilnProblems(): List<String> {
        val bad = ArrayList<String>()
        for (i in 0..200) bad += cylinderHitsKiln(carriageAt(i / 200f))
        // door sweep: quarter disc round the hinge, radius = door width; must miss the tray, chips and the turntable at home
        val sweepR = sqrt(DOOR_W * DOOR_W + DOOR_T * DOOR_T)
        fun inSweep(p: V3): Boolean { val d = p - HINGE; return horiz(d) <= sweepR && d.z >= -1e-4f }
        val trayCorners = listOf(V3(-0.24f, 0f, 0.20f), V3(-0.24f, 0f, 0.28f))
        for (p in trayCorners) if (inSweep(p)) bad += "door sweeps the tray at $p"
        for (c in listOf(CHIP_TURN, CHIP_MIRROR)) if (inSweep(c)) bad += "door sweeps a chip"
        // turntable at home (r 0.15 at the origin) vs the sweep: closest point distance ≥ 0.25 − r … design says ≥ 0.25 to the disc edge
        val gapHome = horiz(HINGE) - sweepR - CLAY_R
        if (gapHome < 0.0f) bad += "door sweep reaches the turntable at home"
        // door hole fits the turntable (x) and the clay (y)
        if (KILN_HOLE_X1 - KILN_HOLE_X0 < 2 * CLAY_R) bad += "door hole narrower than the turntable"
        return bad
    }

    // ------------------------------------------------------------------------------------------------ boxes
    /** Every bench thing's bounding box (W), for the occlusion check and the bounds check. */
    fun benchBoxes(): Map<String, Pair<V3, V3>> = linkedMapOf(
        "clay" to (V3(-CLAY_R, 0f, -CLAY_R) to V3(CLAY_R, CLAY_Y1, CLAY_R)),
        "kiln" to (V3(KILN_C.x - KILN_W / 2, 0f, KILN_C.z - KILN_D / 2) to V3(KILN_C.x + KILN_W / 2, KILN_H, KILN_FRONT_Z)),
        "doorOpen" to (doorCorners(DOOR_OPEN_DEG).let { c -> V3(c.minOf { it.x }, 0f, c.minOf { it.z }) to V3(c.maxOf { it.x }, DOOR_H, c.maxOf { it.z }) }),
        "clayAtDoor" to (V3(CARRIAGE_MID.x - CLAY_R, 0f, -CLAY_R) to V3(CARRIAGE_MID.x + CLAY_R, CLAY_Y1, CLAY_R)),
        "tray" to (V3(-0.24f, 0f, 0.20f) to V3(0.24f, 0.08f, 0.28f)),
        "chipTurn" to chipBox(CHIP_TURN, CHIP_W, CHIP_H),
        "chipMirror" to chipBox(CHIP_MIRROR, CHIP_W, CHIP_H),
        "caption" to chipBox(CAPTION, CAPTION_W, CAPTION_H),
        "score" to chipBox(SCORE, SCORE_W, SCORE_H),
    ) + TRANSPORT.withIndex().associate { (i, c) -> "transport$i" to chipBox(c, CHIP_W, CHIP_H) }

    private fun chipBox(c: V3, w: Float, h: Float) = V3(c.x - w / 2, c.y - h / 2, c.z - 0.02f) to V3(c.x + w / 2, c.y + h / 2, c.z + 0.02f)

    private fun corners(b: Pair<V3, V3>): List<V3> { val (a, z) = b; return listOf(V3(a.x, a.y, a.z), V3(z.x, a.y, a.z), V3(a.x, z.y, a.z), V3(z.x, z.y, a.z), V3(a.x, a.y, z.z), V3(z.x, a.y, z.z), V3(a.x, z.y, z.z), V3(z.x, z.y, z.z)) }

    /** Azimuth (+ = right) and elevation (+ = up) of [p] seen from [eye] looking along −z, degrees. */
    fun azEl(eye: V3, p: V3): Pair<Float, Float> {
        val d = p - eye
        val az = Math.toDegrees(atan2(d.x.toDouble(), (-d.z).toDouble())).toFloat()
        val el = Math.toDegrees(atan2(d.y.toDouble(), sqrt((d.x * d.x + d.z * d.z).toDouble()))).toFloat()
        return az to el
    }

    /**
     * M13 panelNotOccluded: from the eye, every bench box corner stays outside the control window's angular rectangle
     * (window [wM] × [hM] at 1.75 m straight ahead at eye height). A box counts as covering the window only when
     * it is in front of the window and its angular box overlaps the window's.
     */
    fun windowOcclusion(eye: V3 = EYE, wM: Float = 0.52f, hM: Float = 0.46f): List<String> {
        val halfAz = Math.toDegrees(atan((wM / 2 / WINDOW_DIST).toDouble())).toFloat()
        val halfEl = Math.toDegrees(atan((hM / 2 / WINDOW_DIST).toDouble())).toFloat()
        val bad = ArrayList<String>()
        for ((name, b) in benchBoxes()) {
            val ae = corners(b).map { azEl(eye, it) }
            val azMin = ae.minOf { it.first }; val azMax = ae.maxOf { it.first }
            val elMin = ae.minOf { it.second }; val elMax = ae.maxOf { it.second }
            val overlap = azMax > -halfAz && azMin < halfAz && elMax > -halfEl && elMin < halfEl
            if (overlap) bad += "$name az ${"%.1f".format(azMin)}…${"%.1f".format(azMax)} el ${"%.1f".format(elMin)}…${"%.1f".format(elMax)} vs window ±${"%.1f".format(halfAz)} / ±${"%.1f".format(halfEl)}"
        }
        return bad
    }

    /** L5: lesson panels / chips vs the clay cylinder, tray, chips and the door's sweep (no two boxes intersect). */
    fun panelOverlaps(): List<String> {
        val bad = ArrayList<String>()
        val boxes = benchBoxes()
        val panels = boxes.filterKeys { it.startsWith("transport") || it == "caption" || it == "score" || it.startsWith("chip") }
        for ((n, b) in panels) {
            // clay cylinder: the box's nearest point to the axis must be ≥ r
            val nx = clampf(0f, b.first.x, b.second.x); val nz = clampf(0f, b.first.z, b.second.z)
            if (sqrt(nx * nx + nz * nz) < CLAY_R && b.second.y > CLAY_Y0) bad += "$n cuts the clay cylinder"
            for ((m, o) in boxes) if (m != n && !(m == "clay") && intersects(b, o)) bad += "$n overlaps $m"
        }
        return bad.distinct()
    }

    private fun intersects(a: Pair<V3, V3>, b: Pair<V3, V3>) =
        a.first.x < b.second.x && a.second.x > b.first.x && a.first.y < b.second.y && a.second.y > b.first.y && a.first.z < b.second.z && a.second.z > b.first.z

    /** Bounds of the tool at a tip (design M7): the tip inside the clay cylinder, knob / head clear of the tray boxes. */
    fun toolProblems(tip: V3): List<String> {
        val bad = ArrayList<String>()
        val r = sqrt(tip.x * tip.x + tip.z * tip.z)
        if (r > CLAY_R + 1e-4f) bad += "tip r ${"%.3f".format(r)}"
        if (tip.y < CLAY_Y0 - 1e-4f || tip.y > CLAY_Y1 + 1e-4f) bad += "tip y ${"%.3f".format(tip.y)}"
        val knob = knobCentre(tip)
        val kr = KNOB_D / 2
        val headEnd = tip + V3(0f, 0f, headLenFor(tip.y))
        for (x in TOOL_X) {
            val lo = V3(x - TOOL_BOX.x / 2, TOOL_Y - TOOL_BOX.y / 2, TOOL_Z - TOOL_BOX.z / 2)
            val hi = V3(x + TOOL_BOX.x / 2, TOOL_Y + TOOL_BOX.y / 2, TOOL_Z + TOOL_BOX.z / 2)
            if (sphereHitsBox(knob, kr, lo, hi)) bad += "knob hits tool box x=$x"
            if (sphereHitsBox(headEnd, HEAD_D / 2, lo, hi)) bad += "head hits tool box x=$x"
        }
        return bad
    }

    private fun sphereHitsBox(c: V3, r: Float, lo: V3, hi: V3): Boolean {
        val dx = max(lo.x - c.x, max(0f, c.x - hi.x)); val dy = max(lo.y - c.y, max(0f, c.y - hi.y)); val dz = max(lo.z - c.z, max(0f, c.z - hi.z))
        return dx * dx + dy * dy + dz * dz < r * r
    }

    fun minOf3(a: Float, b: Float, c: Float) = min(a, min(b, c))
    @Suppress("unused") private fun unusedSin(a: Float) = sin(a)
}
