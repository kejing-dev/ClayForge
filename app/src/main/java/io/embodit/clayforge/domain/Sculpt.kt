package io.embodit.clayforge.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The four tools on the tray (design `brushes`). */
enum class Brush(val id: String, val zh: String, val en: String) {
    ADD("add", "加泥", "Add"), CARVE("carve", "挖", "Carve"), SMOOTH("smooth", "抹平", "Smooth"), PULL("pull", "拉", "Pull");
    companion object { fun of(id: String) = entries.firstOrNull { it.id == id } }
}

/** Tool sizes: radius in metres (design: 小 0.012 / 中 0.024 / 大 0.040). */
enum class ToolSize(val id: String, val r: Float, val zh: String, val en: String, val smoothK: Int) {
    S("s", 0.012f, "小", "S", 1), M("m", 0.024f, "中", "M", 2), L("l", 0.040f, "大", "L", 4);
    fun next() = entries[(ordinal + 1) % entries.size]
    companion object { fun of(id: String) = entries.firstOrNull { it.id == id } }
}

/** One undo step: the 16³ point blocks a stroke changed, as they were before it (16-bit). */
class UndoStep(val blocks: Map<Int, ShortArray>)

/**
 * The sculpting core (design `strokeRules`): one tip point kept in volume coordinates (it does not turn with the
 * turntable), its depth found by a ray from the front, dabs / smoothing / pulling on the [field], mirror, undo.
 * Pure Kotlin: the scene feeds drag deltas in volume metres and remeshes the dirty chunks.
 */
class Sculptor(val field: ClayField) {
    var brush = Brush.ADD
    var size = ToolSize.M
    var mirror = false
    /** Turntable yaw, radians. clay = rotY(−yaw)(volume). */
    var yaw = 0f
    /** Ray origin (x, y) in volume coordinates, clamped (design strokeRules ⑥ / clamp). */
    var orgX = 0f; private set
    var orgY = 0.2f; private set
    /** The tip in volume coordinates. */
    var tip = V3(0f, 0.2f, 0.1f); private set
    var lastHit = false; private set
    private var lastT = 0.3f
    /** Push / pull along the view ray from the drag's z (device only), clamped to ± one radius. */
    private var zPush = 0f

    // stroke state
    enum class Phase { IDLE, GATE, SCULPT, MOVE }
    var phase = Phase.IDLE; private set
    private val snap = ClayField()
    private var gateTravel = 0f
    private var pressMs = 0L
    private var dabCarry = 0f
    private var strokeChanged = false
    /** Tip is at the cylinder edge (the boundary frame lights for 0.4 s). */
    var edgeHit = false

    // pull state
    private val pullBase = ClayField()
    private var pullStart = V3.ZERO
    private var pullAcc = V3.ZERO
    private var pullBox: IntArray? = null

    /** Budget gate (design S20): triangles provider and hysteresis 55k / 52k. */
    var triangles: () -> Int = { 0 }
    var tooMuch = false; private set

    val undo = ArrayDeque<UndoStep>()
    var strokes = 0; private set

    fun toClay(v: V3) = v.rotY(-yaw)
    fun toVol(c: V3) = c.rotY(yaw)

    // ------------------------------------------------------------------------------------------------ tip
    /** Ray march from (x, y, 0.30) along DIR into [f]; returns the distance of the first 0.5 crossing or null. */
    fun rayDepth(x: Float, y: Float, f: ClayField = field): Float? {
        val ox = x - DIR.x * 0.02f; val oy = y - DIR.y * 0.02f; val oz = RAY_Z - DIR.z * 0.02f
        val c = cos(-yaw); val s = sin(-yaw)
        fun at(t: Float): Float {
            val px = ox + DIR.x * t; val py = oy + DIR.y * t; val pz = oz + DIR.z * t
            return f.sample(c * px + s * pz, py, -s * px + c * pz)
        }
        var t = 0f
        while (t < 0.72f) {
            if (at(t) > 0.5f) {
                var a = t - STEP; var b = t
                repeat(7) { val m = (a + b) / 2; if (at(m) > 0.5f) b = m else a = m }
                return b - 0.02f
            }
            t += STEP
        }
        return null
    }

    /** Puts the ray origin at (x, y) (clamped, the overshoot is dropped) and the tip on the front-most clay. */
    fun placeTip(x: Float, y: Float, f: ClayField = field): Boolean {
        orgX = clampf(x, -X_LIM, X_LIM); orgY = clampf(y, Y_LIM_LO, Y_LIM_HI)
        edgeHit = orgX != x || orgY != y
        val t = rayDepth(orgX, orgY, f)
        lastHit = t != null
        if (t != null) lastT = t
        tip = clampToCylinder(V3(orgX, orgY, RAY_Z) + DIR * (lastT + zPush))
        return lastHit
    }

    /** Re-finds the depth for the same (x, y) (after a turn, an undo or a new lump). */
    fun refreshTip() { placeTip(orgX, orgY) }

    private fun clampToCylinder(vol: V3): V3 {
        var c = toClay(vol)
        val r = sqrt(c.x * c.x + c.z * c.z)
        if (r > ClayField.R_MAX) { val k = ClayField.R_MAX / r; c = V3(c.x * k, c.y, c.z * k); edgeHit = true }
        val y = clampf(c.y, CLAY_Y_LO, ClayField.Y_TOP)
        if (y != c.y) edgeHit = true
        return toVol(V3(c.x, y, c.z))
    }

    /** Surface normal at the tip, volume coordinates. */
    fun tipNormal(): V3 = toVol(field.normalAt(toClay(tip)))

    /** The mirrored tip (clay x negated), volume coordinates. */
    fun mirrorTip(): V3 { val c = toClay(tip); return toVol(V3(-c.x, c.y, c.z)) }

    // ------------------------------------------------------------------------------------------------ strokes
    /** Press on the tool head: snapshot the field (the tip depth is found against it for the whole stroke). */
    fun pressHead(nowMs: Long) {
        snap.copyFrom(field)
        field.clearTouched()
        phase = Phase.GATE; gateTravel = 0f; pressMs = nowMs; dabCarry = 0f; strokeChanged = false; zPush = 0f
    }

    /** Press on the move knob: the tip slides, nothing is sculpted. */
    fun pressKnob() { phase = Phase.MOVE; zPush = 0f }

    /** One drag event: [d] in volume metres (already ÷ volume scale and smoothed). Returns true if the clay changed. */
    fun drag(d: V3, nowMs: Long): Boolean {
        when (phase) {
            Phase.IDLE -> return false
            Phase.MOVE -> { placeTip(orgX + d.x, orgY + d.y); return false }
            Phase.GATE -> {
                gateTravel += sqrt(d.x * d.x + d.y * d.y)
                placeTip(orgX + d.x, orgY + d.y, snap)
                val held = nowMs - pressMs
                if (gateTravel >= GATE_M || (held >= GATE_HOLD_MS && gateTravel >= GATE_SLOW_M)) {
                    phase = Phase.SCULPT
                    if (brush == Brush.PULL) grabPull()
                }
                return false
            }
            Phase.SCULPT -> {
                if (brush == Brush.PULL) return pullBy(d)
                zPush = clampf(zPush - d.z, -size.r, size.r)
                val from = tip
                placeTip(orgX + d.x, orgY + d.y, snap)
                return applyPath(from, tip)
            }
        }
    }

    /** Release. Returns the undo step if the stroke changed the clay. */
    fun release(): UndoStep? {
        val was = phase
        phase = Phase.IDLE
        zPush = 0f
        pullBox = null
        if (was == Phase.SCULPT || was == Phase.GATE) {
            placeTip(orgX, orgY)
            if (strokeChanged) return commit()
        }
        return null
    }

    /** Short press on the tool head (design strokeRules.poke): one ball / one dent / one smooth; pull does nothing. */
    fun poke(): UndoStep? {
        if (brush == Brush.PULL) return null
        snap.copyFrom(field)
        field.clearTouched()
        placeTip(orgX, orgY)
        strokeChanged = false
        if (brush == Brush.ADD && blockedForGrowth()) return null
        dab(tip, POKE_AMOUNT)
        placeTip(orgX, orgY)
        return if (strokeChanged) commit() else null
    }

    private fun commit(): UndoStep {
        val blocks = HashMap<Int, ShortArray>()
        for (b in 0 until ClayField.CHUNKS) if (field.touched[b]) blocks[b] = blockOf(snap, b)
        val step = UndoStep(blocks)
        undo.addLast(step)
        while (undo.size > UNDO_MAX) undo.removeFirst()
        strokes++
        return step
    }

    fun undoOne(): Boolean {
        val step = undo.removeLastOrNull() ?: return false
        for ((b, data) in step.blocks) restoreBlock(b, data)
        refreshTip()
        updateBudget()
        return true
    }

    fun clearUndo() { undo.clear(); strokes = 0 }

    private fun applyPath(from: V3, to: V3): Boolean {
        val r = size.r
        val len = from.dist(to)
        val step = r * 0.35f
        if (brush == Brush.ADD && blockedForGrowth()) return false
        val n = max(1, kotlin.math.ceil(len / step).toInt())
        val amt = DAB_AMOUNT * min(1f, len / n / step)
        if (len < 1e-5f) return false
        val before = strokeChanged
        for (s in 1..n) dab(from.lerp(to, s / n.toFloat()), amt)
        return strokeChanged || before
    }

    private fun blockedForGrowth(): Boolean { updateBudget(); return tooMuch }

    fun updateBudget() {
        val t = triangles()
        if (!tooMuch && t >= BUDGET_IN) tooMuch = true
        else if (tooMuch && t <= BUDGET_OUT) tooMuch = false
    }

    /** One dab at a volume point, mirrored when on (once only near the plane). */
    private fun dab(pVol: V3, amt: Float) {
        val r = size.r
        val c = toClay(pVol)
        val centers = if (mirror && abs(c.x) >= r) listOf(c, V3(-c.x, c.y, c.z)) else listOf(c)
        for (cc in centers) when (brush) {
            Brush.ADD -> addKernel(cc, r, amt)
            Brush.CARVE -> addKernel(cc, r, -amt)
            Brush.SMOOTH -> smoothKernel(cc, r)
            Brush.PULL -> {}
        }
    }

    private fun addKernel(c: V3, r: Float, amt: Float) {
        val reach = 2f * r
        val (i0, i1) = ClayField.rangeX(c.x, reach); val (j0, j1) = ClayField.rangeY(c.y, reach); val (k0, k1) = ClayField.rangeZ(c.z, reach)
        if (i0 > i1 || j0 > j1 || k0 > k1) return
        val s2 = 2f * (0.5f * r) * (0.5f * r)
        val f = field.f; val mask = ClayField.MASK
        var any = false
        for (k in k0..k1) { val dz = ClayField.pz(k) - c.z
            for (j in j0..j1) { val dy = ClayField.py(j) - c.y
                for (i in i0..i1) { val dx = ClayField.px(i) - c.x
                    val d2 = dx * dx + dy * dy + dz * dz
                    if (d2 > reach * reach) continue
                    val id = ClayField.idx(i, j, k)
                    if (!mask[id]) continue
                    val old = f[id]
                    val nv = clampf(old + amt * exp(-d2 / s2), 0f, 1f)
                    if (nv != old) { f[id] = nv; any = true }
                } } }
        if (any) { field.touchBox(i0, i1, j0, j1, k0, k1); strokeChanged = true }
    }

    private var smoothBuf = FloatArray(0)

    private fun smoothKernel(c: V3, r: Float) {
        val reach = 2f * r
        val kk = size.smoothK
        val (i0, i1) = ClayField.rangeX(c.x, reach); val (j0, j1) = ClayField.rangeY(c.y, reach); val (k0, k1) = ClayField.rangeZ(c.z, reach)
        if (i0 > i1 || j0 > j1 || k0 > k1) return
        // copy the box + kk margin as the source
        val bi0 = max(0, i0 - kk); val bi1 = min(ClayField.N - 1, i1 + kk)
        val bj0 = max(0, j0 - kk); val bj1 = min(ClayField.N - 1, j1 + kk)
        val bk0 = max(0, k0 - kk); val bk1 = min(ClayField.N - 1, k1 + kk)
        val w = bi1 - bi0 + 1; val h = bj1 - bj0 + 1; val dd = bk1 - bk0 + 1
        if (smoothBuf.size < w * h * dd) smoothBuf = FloatArray(w * h * dd)
        val src = smoothBuf; val f = field.f; val mask = ClayField.MASK
        for (k in bk0..bk1) for (j in bj0..bj1) System.arraycopy(f, ClayField.idx(bi0, j, k), src, ((k - bk0) * h + (j - bj0)) * w, w)
        val s2 = 2f * (0.5f * r) * (0.5f * r)
        var any = false
        for (k in k0..k1) { val dz = ClayField.pz(k) - c.z
            for (j in j0..j1) { val dy = ClayField.py(j) - c.y
                for (i in i0..i1) { val dx = ClayField.px(i) - c.x
                    val d2 = dx * dx + dy * dy + dz * dz
                    if (d2 > reach * reach) continue
                    val id = ClayField.idx(i, j, k)
                    if (!mask[id]) continue
                    var sum = 0f; var n = 0
                    for (c3 in max(bk0, k - kk)..min(bk1, k + kk)) for (b3 in max(bj0, j - kk)..min(bj1, j + kk)) {
                        val row = ((c3 - bk0) * h + (b3 - bj0)) * w
                        for (a3 in max(bi0, i - kk)..min(bi1, i + kk)) { sum += src[row + a3 - bi0]; n++ }
                    }
                    val old = f[id]
                    val nv = clampf(old + (sum / n - old) * SMOOTH_RATE * exp(-d2 / s2), 0f, 1f)
                    if (abs(nv - old) > 1e-5f) { f[id] = nv; any = true }
                } } }
        if (any) { field.touchBox(i0, i1, j0, j1, k0, k1); strokeChanged = true }
    }

    // ------------------------------------------------------------------------------------------------ pull
    private fun grabPull() {
        pullBase.copyFrom(field)
        pullStart = tip
        pullAcc = V3.ZERO
        pullBox = null
    }

    /** Pull: the tip follows the hand; the grabbed clay is resampled from the grab snapshot by the total offset. */
    private fun pullBy(d: V3): Boolean {
        if (blockedForGrowth()) return false
        val target = clampToCylinder(tip + d)
        val moved = target - tip
        if (moved.length < 1e-6f) return false
        tip = target
        // keep the ray origin under the tip so release / refresh finds the same spot
        orgX = clampf(tip.x, -X_LIM, X_LIM); orgY = clampf(tip.y, Y_LIM_LO, Y_LIM_HI)
        pullAcc = pullAcc + moved
        val r = size.r * 1.5f
        val c = toClay(pullStart)
        val dd = toClay(pullStart + pullAcc) - c
        val centers = if (mirror && abs(c.x) >= size.r) listOf(c to dd, V3(-c.x, c.y, c.z) to V3(-dd.x, dd.y, dd.z)) else listOf(c to dd)
        // restore the previous warp region from the base, then warp again from the base
        pullBox?.let { b -> restoreBox(b) }
        val box = intArrayOf(ClayField.N, -1, ClayField.N, -1, ClayField.N, -1)
        val f = field.f; val mask = ClayField.MASK
        val s2 = 2f * (0.6f * r) * (0.6f * r)
        for ((cc, dv) in centers) {
            val reach = r + dv.length
            val m = cc + dv * 0.5f
            val (i0, i1) = ClayField.rangeX(m.x, 2 * reach); val (j0, j1) = ClayField.rangeY(m.y, 2 * reach); val (k0, k1) = ClayField.rangeZ(m.z, 2 * reach)
            if (i0 > i1 || j0 > j1 || k0 > k1) continue
            box[0] = min(box[0], i0); box[1] = max(box[1], i1); box[2] = min(box[2], j0); box[3] = max(box[3], j1); box[4] = min(box[4], k0); box[5] = max(box[5], k1)
            for (k in k0..k1) for (j in j0..j1) for (i in i0..i1) {
                val id = ClayField.idx(i, j, k)
                if (!mask[id]) continue
                val px = ClayField.px(i); val py = ClayField.py(j); val pz = ClayField.pz(k)
                val mx = px - m.x; val my = py - m.y; val mz = pz - m.z
                if (mx * mx + my * my + mz * mz > 4 * reach * reach) continue
                val qx = px - cc.x - dv.x; val qy = py - cc.y - dv.y; val qz = pz - cc.z - dv.z
                val w = exp(-(qx * qx + qy * qy + qz * qz) / s2)
                f[id] = pullBase.sample(px - dv.x * w, py - dv.y * w, pz - dv.z * w)
            }
        }
        if (box[1] >= 0) {
            field.touchBox(box[0], box[1], box[2], box[3], box[4], box[5])
            pullBox = box
            strokeChanged = true
        }
        // regrab past 2 × radius (Snakehook): keep pulling from the current result
        if (pullAcc.length > 2 * size.r) { pullBase.copyFrom(field); pullStart = tip; pullAcc = V3.ZERO; pullBox = null }
        return true
    }

    private fun restoreBox(b: IntArray) {
        val w = b[1] - b[0] + 1
        for (k in b[4]..b[5]) for (j in b[2]..b[3]) { val o = ClayField.idx(b[0], j, k); System.arraycopy(pullBase.f, o, field.f, o, w) }
        field.touchBox(b[0], b[1], b[2], b[3], b[4], b[5])
    }

    // ------------------------------------------------------------------------------------------------ blocks
    private fun blockRange(b: Int): IntArray {
        val a = b % ClayField.CPA; val bb = (b / ClayField.CPA) % ClayField.CPA; val c = b / (ClayField.CPA * ClayField.CPA)
        return intArrayOf(a * ClayField.C, bb * ClayField.C, c * ClayField.C)
    }

    private fun blockOf(src: ClayField, b: Int): ShortArray {
        val (i0, j0, k0) = blockRange(b).let { Triple(it[0], it[1], it[2]) }
        val out = ShortArray(ClayField.C * ClayField.C * ClayField.C)
        var o = 0
        for (k in k0 until k0 + ClayField.C) for (j in j0 until j0 + ClayField.C) for (i in i0 until i0 + ClayField.C)
            out[o++] = (src.f[ClayField.idx(i, j, k)] * 65535f + 0.5f).toInt().toShort()
        return out
    }

    private fun restoreBlock(b: Int, data: ShortArray) {
        val (i0, j0, k0) = blockRange(b).let { Triple(it[0], it[1], it[2]) }
        var o = 0
        for (k in k0 until k0 + ClayField.C) for (j in j0 until j0 + ClayField.C) for (i in i0 until i0 + ClayField.C)
            field.f[ClayField.idx(i, j, k)] = (data[o++].toInt() and 0xFFFF) / 65535f
        field.touchBox(i0, i0 + ClayField.C - 1, j0, j0 + ClayField.C - 1, k0, k0 + ClayField.C - 1)
    }

    companion object {
        /** Ray: from z 0.30 going −z, tilted 15° down (design strokeRules ⑥). */
        val DIR = V3(0f, -sin(15.0 * PI / 180).toFloat(), -cos(15.0 * PI / 180).toFloat())
        const val RAY_Z = 0.30f
        const val STEP = 0.0025f
        const val X_LIM = 0.15f
        const val Y_LIM_LO = 0.07f
        const val Y_LIM_HI = 0.47f
        const val CLAY_Y_LO = 0.072f
        const val GATE_M = 0.015f
        const val GATE_SLOW_M = 0.008f
        const val GATE_HOLD_MS = 150L
        const val DAB_AMOUNT = 0.22f
        const val POKE_AMOUNT = 0.45f
        const val SMOOTH_RATE = 0.3f
        const val UNDO_MAX = 30
        const val BUDGET_IN = 55_000
        const val BUDGET_OUT = 52_000
    }
}
