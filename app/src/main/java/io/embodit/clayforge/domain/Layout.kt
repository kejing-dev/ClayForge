package io.embodit.clayforge.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Where everything sits in the studio volume (design `spatialLayout`, `tapTargets`), volume frame: metres, origin on
 * the floor centre, +y up, +z toward the user. One place, so the scene, the bounds check and the unit tests agree.
 */
object Layout {
    const val VOL_W = 0.60f; const val VOL_H = 0.55f; const val VOL_D = 0.60f

    // turntable
    const val TT_TOP_Y = 0.07f; const val TT_R = 0.15f; const val TT_THICK = 0.025f; const val TT_BASE_R = 0.10f
    // clay cylinder (design) and the kiln
    const val CLAY_R = 0.15f; const val CLAY_Y0 = 0.07f; const val CLAY_Y1 = 0.39f
    const val KILN_R = 0.18f; const val KILN_BODY_H = 0.37f; const val KILN_BODY_Y = 0.03f; const val KILN_LID_Y = 0.40f; const val KILN_LID_H = 0.09f
    const val KILN_LID_OPEN_Y = 0.55f
    /** Smallest radius of the warped Tripo body (tools/prep_assets.py output, min |x| 0.167). */
    const val KILN_INNER_R = 0.165f

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

    // the tool in hand
    val HANDLE = V3(0.35f, 0.30f, 0.89f).normalized()
    val HANDLE_LOW = V3(0.35f, 0.55f, 0.76f).normalized()
    const val LOW_TIP_Y = 0.11f
    const val HEAD_LEN = 0.06f; const val HEAD_LEN_LOW = 0.02f; const val HEAD_D = 0.065f
    const val KNOB_D = 0.065f; const val KNOB_AT = 0.12f

    // chips (attachment panels), centre, size
    val CHIP_TURN = V3(-0.25f, 0.125f, 0.17f)
    val CHIP_OPEN = V3(-0.25f, 0.22f, 0.17f)
    const val CHIP_W = 0.08f; const val CHIP_H = 0.065f

    // default eyes for the d35 check: in front of the volume at 1.75 m, low and high
    val EYES = listOf(V3(0f, 0.30f, 1.75f + VOL_D / 2), V3(0f, 0.55f, 1.75f + VOL_D / 2))

    fun handleFor(tipY: Float) = if (tipY < LOW_TIP_Y) HANDLE_LOW else HANDLE
    fun headLenFor(tipY: Float) = if (tipY < LOW_TIP_Y) HEAD_LEN_LOW else HEAD_LEN
    fun knobCentre(tip: V3) = tip + handleFor(tip.y) * KNOB_AT

    fun insideVolume(p: V3, margin: Float = 0f) =
        abs(p.x) <= VOL_W / 2 - margin && abs(p.z) <= VOL_D / 2 - margin && p.y >= -1e-4f + margin && p.y <= VOL_H - margin

    /** Corners of a chip that faces +z (it only turns about y toward the user; default eyes are straight ahead). */
    fun chipCorners(c: V3): List<V3> = listOf(
        V3(c.x - CHIP_W / 2, c.y - CHIP_H / 2, c.z), V3(c.x + CHIP_W / 2, c.y - CHIP_H / 2, c.z),
        V3(c.x - CHIP_W / 2, c.y + CHIP_H / 2, c.z), V3(c.x + CHIP_W / 2, c.y + CHIP_H / 2, c.z),
    )

    /** Does segment a→b pass through the vertical cylinder (axis x = z = 0, radius r, y0..y1)? */
    fun segmentHitsCylinder(a: V3, b: V3, r: Float, y0: Float, y1: Float, steps: Int = 400): Boolean {
        for (i in 0..steps) {
            val p = a.lerp(b, i / steps.toFloat())
            if (p.y in y0..y1 && sqrt(p.x * p.x + p.z * p.z) < r) return true
        }
        return false
    }

    /** design d35PanelBoxCheck / M13: both chips × 4 corners × 2 eyes, against the clay and the kiln cylinders. */
    fun panelsOutsideClayCylinder(): List<String> {
        val bad = ArrayList<String>()
        for ((name, c) in listOf("turn90" to CHIP_TURN, "open" to CHIP_OPEN)) for (corner in chipCorners(c)) {
            val rr = sqrt(corner.x * corner.x + corner.z * corner.z)
            if (rr <= KILN_R) bad += "$name corner $corner inside r ${"%.3f".format(rr)}"
            if (!insideVolume(corner)) bad += "$name corner $corner outside the volume"
            for (eye in EYES) {
                if (segmentHitsCylinder(eye, corner, CLAY_R, CLAY_Y0, CLAY_Y1)) bad += "$name eye ${eye.y} → $corner crosses the clay"
                if (segmentHitsCylinder(eye, corner, KILN_R, KILN_BODY_Y, KILN_LID_Y + KILN_LID_H)) bad += "$name eye ${eye.y} → $corner crosses the kiln"
            }
        }
        // chip vs chip vertical gap ≥ 2 cm, chip bottom above the tool tap boxes
        val gap = (CHIP_OPEN.y - CHIP_H / 2) - (CHIP_TURN.y + CHIP_H / 2)
        if (gap < 0.02f - 1e-4f) bad += "chip gap ${"%.3f".format(gap)}"
        if (CHIP_TURN.y - CHIP_H / 2 <= TOOL_Y + TOOL_BOX.y / 2) bad += "turn chip overlaps the tool boxes"
        return bad
    }

    /**
     * Bounds of everything a tip position puts in the volume (design M7): the tip inside the clay cylinder, the head
     * box and the knob ball inside the volume, the knob clear of the tray tool boxes.
     */
    fun toolProblems(tip: V3): List<String> {
        val bad = ArrayList<String>()
        val r = sqrt(tip.x * tip.x + tip.z * tip.z)
        if (r > CLAY_R + 1e-4f) bad += "tip r ${"%.3f".format(r)}"
        if (tip.y < CLAY_Y0 - 1e-4f || tip.y > CLAY_Y1 + 1e-4f) bad += "tip y ${"%.3f".format(tip.y)}"
        val knob = knobCentre(tip)
        val kr = KNOB_D / 2
        for (d in listOf(V3(kr, 0f, 0f), V3(-kr, 0f, 0f), V3(0f, kr, 0f), V3(0f, -kr, 0f), V3(0f, 0f, kr), V3(0f, 0f, -kr)))
            if (!insideVolume(knob + d)) bad += "knob ${knob + d} outside"
        val headEnd = tip + V3(0f, 0f, headLenFor(tip.y))
        for (p in listOf(tip, headEnd)) for (d in listOf(V3(HEAD_D / 2, 0f, 0f), V3(-HEAD_D / 2, 0f, 0f), V3(0f, HEAD_D / 2, 0f), V3(0f, 0f, 0f)))
            if (!insideVolume(p + d)) bad += "head ${p + d} outside"
        // knob and head vs tray tool boxes (tilted tray ignored: boxes are axis-aligned at TOOL_Y)
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

    /** The kiln at animation time [u] (0 open … 1 closed): body bottom y, body scale, lid bottom y. */
    fun kilnPose(u: Float): Triple<Float, Float, Float> {
        val e = clampf(u, 0f, 1f)
        val bodyScale = 0.92f + 0.08f * e
        val bodyY = KILN_BODY_Y - 0.07f * (1 - e)
        val lidY = KILN_LID_Y + (KILN_LID_OPEN_Y - KILN_LID_Y) * (1 - e)
        return Triple(bodyY, bodyScale, lidY)
    }

    /** M18: the kiln never cuts the clay cylinder and the lid bottom never drops under 0.40. */
    fun kilnProblems(u: Float, bodyInnerR: Float = KILN_INNER_R): List<String> {
        val (_, s, lidY) = kilnPose(u)
        val bad = ArrayList<String>()
        if (bodyInnerR * s <= CLAY_R) bad += "body inner r ${"%.3f".format(bodyInnerR * s)} ≤ clay r at u=$u"
        if (lidY < KILN_LID_Y - 1e-4f) bad += "lid bottom $lidY < 0.40 at u=$u"
        return bad
    }

    fun minOf3(a: Float, b: Float, c: Float) = min(a, min(b, c))
}
