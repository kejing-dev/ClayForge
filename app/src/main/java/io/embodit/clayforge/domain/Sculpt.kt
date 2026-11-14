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
 * The sculpting core (design `strokeRules`, `brushes[].spec`, r5): one tip point in bench coordinates (W, it does not
 * turn with the turntable), dabs / scraping / pulling on the [field], mirror, undo. Pure Kotlin.
 *
 * Two ways to drive a stroke:
 *  - relative (eye-hand, system pointer): [pressHead] / [drag] (deltas) / [release] — the tip slides on the front-most
 *    surface along a ray (fixed 15° down from the front, or from [eye] when the head position is known);
 *  - absolute (hand pinch point, controller tool tip): [pressAt] / [dragTo] (bench point) / [release] — every brush acts
 *    on the nearest point of the press snapshot's surface within 1.5 r (add keeps its last depth past that: a sausage).
 * Pull grabs at the press point in both (r5 `pullGrabsAtPress`); a short press is a poke ([poke]).
 */
class Sculptor(val field: ClayField) {
    var brush = Brush.ADD
    var size = ToolSize.M
    var mirror = false
    /** Turntable yaw, radians. clay = rotY(−yaw)(bench). */
    var yaw = 0f
    /** Eye-hand depth ray origin (head position in bench coordinates); null = the fixed front ray (lessons, tests). */
    var eye: V3? = null
    /** Ray aim (x, y) in bench coordinates, clamped (design strokeRules ⑥ / clamp). */
    var orgX = 0f; private set
    var orgY = 0.2f; private set
    /** The tip in bench coordinates. */
    var tip = V3(0f, 0.2f, 0.1f); private set
    var lastHit = false; private set
    private var lastT = 0.3f
    /** Absolute strokes: the tip is farther than 1.5 r from the snapshot surface (ring turns grey「贴近泥面」). */
    var offSurface = false; private set

    // stroke state
    enum class Phase { IDLE, GATE, SCULPT, MOVE }
    var phase = Phase.IDLE; private set
    private val snap = ClayField()
    private var gateTravel = 0f
    private var pressMs = 0L
    private var strokeChanged = false
    private var absolute = false
    private var pressTip = V3.ZERO
    private var pressSurf: V3? = null
    private var lastSurf: V3? = null
    private var lastOffset: V3 = V3.ZERO
    /** Scrape plane of this stroke (clay coordinates), picked at the first scrape from the press snapshot. */
    private var plane: Pair<V3, V3>? = null
    /** Tip is at the cylinder edge (the boundary frame lights for 0.4 s). */
    var edgeHit = false

    // pull state
    private val pullBase = ClayField()
    private var pullStart = V3.ZERO
    private var pullAcc = V3.ZERO
    private var pullBox: IntArray? = null
    private var pullGrabbed = false

    /** Budget gate (design S20): triangles provider and hysteresis 55k / 52k. */
    var triangles: () -> Int = { 0 }
    var tooMuch = false; private set

    val undo = ArrayDeque<UndoStep>()
    var strokes = 0; private set

    fun toClay(v: V3) = v.rotY(-yaw)
    fun toVol(c: V3) = c.rotY(yaw)

    // ------------------------------------------------------------------------------------------------ tip
    /** The tip ray for aim (x, y): origin and unit direction in bench coordinates, and the march length. */
    private fun ray(x: Float, y: Float): Triple<V3, V3, Float> {
        val e = eye
        if (e == null) return Triple(V3(x, y, RAY_Z) - DIR * 0.02f, DIR, 0.72f)
        // from the head through the aim point on the clay axis plane (z = 0), marching across the clay cylinder
        val aim = V3(x, y, 0f)
        val d = (aim - e).normalized()
        val dist = e.dist(aim)
        val start = maxOf(0f, dist - 0.40f)
        return Triple(e + d * start, d, 0.80f)
    }

    /** Ray march into [f]; returns the distance of the first 0.5 crossing or null. */
    fun rayDepth(x: Float, y: Float, f: ClayField = field): Float? {
        val (o, d, len) = ray(x, y)
        val c = cos(-yaw); val s = sin(-yaw)
        fun at(t: Float): Float {
            val px = o.x + d.x * t; val py = o.y + d.y * t; val pz = o.z + d.z * t
            return f.sample(c * px + s * pz, py, -s * px + c * pz)
        }
        var t = 0f
        while (t < len) {
            if (at(t) > 0.5f) {
                var a = t - STEP; var b = t
                repeat(7) { val m = (a + b) / 2; if (at(m) > 0.5f) b = m else a = m }
                return b - 0.02f
            }
            t += STEP
        }
        return null
    }

    /** Puts the ray aim at (x, y) (clamped, the overshoot is dropped) and the tip on the front-most clay. */
    fun placeTip(x: Float, y: Float, f: ClayField = field): Boolean {
        orgX = clampf(x, -X_LIM, X_LIM); orgY = clampf(y, Y_LIM_LO, Y_LIM_HI)
        edgeHit = orgX != x || orgY != y
        val t = rayDepth(orgX, orgY, f)
        lastHit = t != null
        if (t != null) lastT = t
        val (o, d, _) = ray(orgX, orgY)
        tip = clampToCylinder(o + d * (lastT + 0.02f + zPush))
        return lastHit
    }

    /** Push / pull along the view ray from the drag's z (device only), clamped to ± one radius. */
    private var zPush = 0f

    /** Re-finds the depth for the same (x, y) (after a turn, an undo or a new lump). */
    fun refreshTip() { if (!absolute || phase == Phase.IDLE) placeTip(orgX, orgY) }

    private fun clampToCylinder(vol: V3): V3 {
        var c = toClay(vol)
        val r = sqrt(c.x * c.x + c.z * c.z)
        if (r > ClayField.R_MAX) { val k = ClayField.R_MAX / r; c = V3(c.x * k, c.y, c.z * k); edgeHit = true }
        val y = clampf(c.y, CLAY_Y_LO, ClayField.Y_TOP)
        if (y != c.y) edgeHit = true
        return toVol(V3(c.x, y, c.z))
    }

    /** Surface normal at the tip, bench coordinates. */
    fun tipNormal(): V3 = toVol(field.normalAt(toClay(tip)))

    /** The mirrored tip (clay x negated), bench coordinates. */
    fun mirrorTip(): V3 { val c = toClay(tip); return toVol(V3(-c.x, c.y, c.z)) }

    /** Nearest surface point of [f] to a bench point within [reach] (marching along the field normal), or null. */
    fun nearestSurface(p: V3, reach: Float, f: ClayField = field): V3? {
        val c = toClay(p)
        val inside = f.sample(c.x, c.y, c.z) > 0.5f
        val n = f.normalAt(c)
        val dir = if (inside) n else -n
        var t = 0f
        val step = ClayField.H * 0.25f
        while (t <= reach) {
            val q = c + dir * t
            val v = f.sample(q.x, q.y, q.z)
            if ((v > 0.5f) != inside) return toVol(q)
            t += step
        }
        return null
    }

    // ------------------------------------------------------------------------------------------------ strokes
    private fun begin(nowMs: Long) {
        snap.copyFrom(field)
        field.clearTouched()
        phase = Phase.GATE; gateTravel = 0f; pressMs = nowMs; strokeChanged = false; zPush = 0f
        plane = null; lastSurf = null; pullGrabbed = false; offSurface = false
        pressTip = tip
        pressSurf = if (absolute) nearestSurface(tip, size.r * 1.5f, snap) else tip
        // r5: the scrape plane is taken at the press point and kept for the whole stroke
        if (brush == Brush.SMOOTH) plane = pickPlane(snap, toClay(pressSurf ?: tip), size.r)
        // r5: pull grabs at the press point (not after the start gate)
        if (brush == Brush.PULL) grabPull()
    }

    /** Press on the tool head (relative stroke): snapshot the field (the tip depth is found against it). */
    fun pressHead(nowMs: Long) { absolute = false; begin(nowMs) }

    /** Absolute stroke (hand pinch point / controller tool tip): the tip is [p] (bench coordinates). */
    fun pressAt(p: V3, nowMs: Long) {
        absolute = true
        tip = clampToCylinder(p)
        orgX = clampf(tip.x, -X_LIM, X_LIM); orgY = clampf(tip.y, Y_LIM_LO, Y_LIM_HI)
        val s = nearestSurface(tip, size.r * 1.5f, field)
        lastHit = s != null
        if (s != null) lastOffset = s - tip
        begin(nowMs)
    }

    /** Press on the move knob: the tip slides, nothing is sculpted. */
    fun pressKnob() { absolute = false; phase = Phase.MOVE; zPush = 0f }

    /** One relative drag event: [d] in bench metres (already gained and smoothed). Returns true if the clay changed. */
    fun drag(d: V3, nowMs: Long): Boolean {
        when (phase) {
            Phase.IDLE -> return false
            Phase.MOVE -> { placeTip(orgX + d.x, orgY + d.y); return false }
            Phase.GATE -> {
                gateTravel += d.length
                if (brush == Brush.PULL) return pullBy(d).also { passGate(nowMs) }
                placeTip(orgX + d.x, orgY + d.y, snap)
                passGate(nowMs)
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

    /** The start gate only tells a poke from a stroke (r5): when it opens, the path from the press point is sculpted too. */
    private fun passGate(nowMs: Long) {
        val held = nowMs - pressMs
        if (gateTravel >= GATE_M || (held >= GATE_HOLD_MS && gateTravel >= GATE_SLOW_M)) {
            phase = Phase.SCULPT
            if (brush != Brush.PULL) {
                val from = pressSurf
                if (!absolute && from != null) applyPath(from, tip)
                else if (absolute && from != null) lastSurf = from
            }
        }
    }

    /** One absolute drag event: the tip is now [p] (bench). Returns true if the clay changed. */
    fun dragTo(p: V3, nowMs: Long): Boolean {
        if (phase == Phase.IDLE || phase == Phase.MOVE) return false
        val target = clampToCylinder(p)
        if (brush == Brush.PULL) {
            val d = target - tip
            gateTravel += d.length
            val changed = pullBy(d)
            if (phase == Phase.GATE) passGate(nowMs)
            return changed
        }
        val prevTip = tip
        tip = target
        orgX = clampf(tip.x, -X_LIM, X_LIM); orgY = clampf(tip.y, Y_LIM_LO, Y_LIM_HI)
        if (phase == Phase.GATE) {
            gateTravel += prevTip.dist(target)
            passGate(nowMs)
            if (phase == Phase.GATE) return false
        }
        // act on the nearest point of the press snapshot's surface; past 1.5 r only add goes on at its last depth
        val s = nearestSurface(tip, size.r * 1.5f, snap)
        val at = when {
            s != null -> { lastOffset = s - tip; offSurface = false; s }
            brush == Brush.ADD -> { offSurface = false; tip + lastOffset }
            else -> { offSurface = true; null }
        }
        lastHit = s != null
        if (at == null) { lastSurf = null; return false }
        val from = lastSurf ?: at
        lastSurf = at
        return if (from === at) { dabAt(at, DAB_AMOUNT * 0.5f); strokeChanged } else applyPath(from, at)
    }

    /** Release. Returns the undo step if the stroke changed the clay. */
    fun release(): UndoStep? {
        val was = phase
        phase = Phase.IDLE
        zPush = 0f
        if (was == Phase.GATE && brush == Brush.PULL && pullGrabbed) {
            // a press that never passed the start gate is a poke: undo the little warp, the caller pokes a nub
            pullBox?.let { restoreBox(it) }
            strokeChanged = false
        }
        pullBox = null; pullGrabbed = false; offSurface = false
        val abs = absolute
        absolute = false
        if (was == Phase.SCULPT || was == Phase.GATE) {
            if (!abs) placeTip(orgX, orgY)
            if (strokeChanged) return commit()
        }
        return null
    }

    /**
     * Short press on the tool head (design strokeRules.poke, r5): one ball / one dent / one scrape; pull = a nub of
     * 0.5 r along the outward normal (`pullPokeMakesNub`).
     */
    fun poke(): UndoStep? {
        snap.copyFrom(field)
        field.clearTouched()
        if (!absolute) placeTip(orgX, orgY)
        strokeChanged = false; plane = null
        if ((brush == Brush.ADD || brush == Brush.PULL) && blockedForGrowth()) return null
        if (brush == Brush.PULL) {
            pullBase.copyFrom(field)
            val c = toClay(nearestSurface(tip, size.r * 1.5f) ?: tip)
            val n = field.normalAt(c)
            warp(c, n * (0.5f * size.r), mirrorPairs = true)
        } else dab(tip, POKE_AMOUNT)
        if (!absolute) placeTip(orgX, orgY)
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

    /** One dab at a bench point (add / carve / scrape), mirrored when on (once only near the plane). */
    private fun dab(pVol: V3, amt: Float) {
        val r = size.r
        val c = toClay(pVol)
        if (brush == Brush.SMOOTH && plane == null) plane = pickPlane(snap, c, r)
        val centers = if (mirror && abs(c.x) >= r) listOf(c, V3(-c.x, c.y, c.z)) else listOf(c)
        for ((k, cc) in centers.withIndex()) when (brush) {
            Brush.ADD -> addKernel(cc, r, amt)
            Brush.CARVE -> addKernel(cc, r, -amt)
            Brush.SMOOTH -> {
                val (pc, pn) = plane!!
                val pl = if (k == 0) pc to pn else V3(-pc.x, pc.y, pc.z) to V3(-pn.x, pn.y, pn.z)
                scrapeKernel(cc, r, pl, SCRAPE_RATE)
            }
            Brush.PULL -> {}
        }
    }

    /** Public single dab at a bench point with the current brush (lessons, absolute pokes). */
    fun dabAt(pVol: V3, amt: Float) = dab(pVol, amt)

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

    /**
     * r5 scrape plane: weighted centroid and mean normal of the snapshot's near-surface points (|f − 0.5| < 0.25)
     * within 1.5 r of the tip (clay coordinates).
     */
    fun pickPlane(src: ClayField, c: V3, r: Float): Pair<V3, V3> {
        val reach = 1.5f * r
        val (i0, i1) = ClayField.rangeX(c.x, reach); val (j0, j1) = ClayField.rangeY(c.y, reach); val (k0, k1) = ClayField.rangeZ(c.z, reach)
        var cx = 0f; var cy = 0f; var cz = 0f; var w = 0f; var nx = 0f; var ny = 0f; var nz = 0f
        // only the face the blade lies on: normals within 60° of the tip's (a block's side face near a stroke end
        // would tilt the plane and the far end would fill instead of cut)
        val n0 = src.normalAt(c)
        for (k in k0..k1) for (j in j0..j1) for (i in i0..i1) {
            val p = V3(ClayField.px(i), ClayField.py(j), ClayField.pz(k))
            if (p.dist(c) > reach) continue
            val v = src.f[ClayField.idx(i, j, k)]
            val s = 1f - abs(v - 0.5f) * 2f
            if (s <= 0.5f) continue
            val n = src.normalAt(p)
            if (n.dot(n0) < 0.5f) continue
            cx += p.x * s; cy += p.y * s; cz += p.z * s; nx += n.x * s; ny += n.y * s; nz += n.z * s; w += s
        }
        if (w == 0f) return c to src.normalAt(c)
        return V3(cx / w, cy / w, cz / w) to V3(nx, ny, nz).normalized()
    }

    /**
     * r5 「刮平」: every cell within 1.6 r goes toward the plane's field value (cut above the plane; below it only a
     * thin layer is filled, half as fast), then a 3³ mean at 0.8 r takes the burr off.
     */
    private fun scrapeKernel(c: V3, r: Float, pl: Pair<V3, V3>, rate: Float) {
        val reach = 1.6f * r
        val (pc, pn) = pl
        val (i0, i1) = ClayField.rangeX(c.x, reach); val (j0, j1) = ClayField.rangeY(c.y, reach); val (k0, k1) = ClayField.rangeZ(c.z, reach)
        if (i0 > i1 || j0 > j1 || k0 > k1) return
        val f = field.f; val mask = ClayField.MASK
        val s2 = 2f * (0.7f * r) * (0.7f * r)
        val width = ClayField.H * 0.55f
        var any = false
        for (k in k0..k1) { val pz = ClayField.pz(k); val dz = pz - c.z
            for (j in j0..j1) { val py = ClayField.py(j); val dy = py - c.y
                for (i in i0..i1) { val px = ClayField.px(i); val dx = px - c.x
                    val d2 = dx * dx + dy * dy + dz * dz
                    if (d2 > reach * reach) continue
                    val id = ClayField.idx(i, j, k)
                    if (!mask[id]) continue
                    val sd = (px - pc.x) * pn.x + (py - pc.y) * pn.y + (pz - pc.z) * pn.z
                    val ft = 1f / (1f + exp(sd / width))
                    val w = exp(-d2 / s2)
                    val old = f[id]
                    val nv = when {
                        ft < old -> old + (ft - old) * rate * w
                        // fill only where clay already is (old > 0.15): the thin layer under a plane that floats above
                        // the surface must not grow a film of loose specks (v0.1.1 hand stroke on the ball)
                        sd > -0.12f * r && old > 0.15f -> old + (ft - old) * rate * 0.5f * w
                        else -> old
                    }
                    if (abs(nv - old) > 1e-6f) { f[id] = clampf(nv, 0f, 1f); any = true }
                } } }
        if (any) { field.touchBox(i0, i1, j0, j1, k0, k1); strokeChanged = true }
        meanKernel(c, 0.8f * r, 1)
    }

    private var smoothBuf = FloatArray(0)

    /** v0.1.0 neighbourhood mean (kept only as the scrape's burr pass). */
    private fun meanKernel(c: V3, r: Float, kk: Int) {
        val reach = 2f * r
        val (i0, i1) = ClayField.rangeX(c.x, reach); val (j0, j1) = ClayField.rangeY(c.y, reach); val (k0, k1) = ClayField.rangeZ(c.z, reach)
        if (i0 > i1 || j0 > j1 || k0 > k1) return
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
                    val nv = clampf(old + (sum / n - old) * MEAN_RATE * exp(-d2 / s2), 0f, 1f)
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
        pullGrabbed = true
    }

    /** Pull: the tip follows the hand in 3D; the grabbed clay is resampled from the grab snapshot by the total offset. */
    private fun pullBy(d: V3): Boolean {
        if (!pullGrabbed) grabPull()
        if (blockedForGrowth()) return false
        val target = clampToCylinder(tip + d)
        val moved = target - tip
        if (moved.length < 1e-6f) return false
        tip = target
        // keep the ray aim under the tip so release / refresh finds the same spot
        orgX = clampf(tip.x, -X_LIM, X_LIM); orgY = clampf(tip.y, Y_LIM_LO, Y_LIM_HI)
        pullAcc = pullAcc + moved
        val c = toClay(pullStart)
        val dd = toClay(pullStart + pullAcc) - c
        pullBox?.let { b -> restoreBox(b) }
        warp(c, dd, mirrorPairs = true)
        // regrab past 2 × radius (Snakehook): keep pulling from the current result
        if (pullAcc.length > 2 * size.r) { pullBase.copyFrom(field); pullStart = tip; pullAcc = V3.ZERO; pullBox = null }
        return true
    }

    /**
     * Backward warp of [pullBase] around clay point [c] by displacement [dv] (clay coordinates):
     * f(p) = base(p − dv·w(p)), w = exp(−|p − c − dv|² / (2 (0.6 · 1.5 r)²)). Remembers the box for the next restore.
     */
    private fun warp(c: V3, dv: V3, mirrorPairs: Boolean) {
        val r = size.r * 1.5f
        val centers = if (mirrorPairs && mirror && abs(c.x) >= size.r) listOf(c to dv, V3(-c.x, c.y, c.z) to V3(-dv.x, dv.y, dv.z)) else listOf(c to dv)
        val box = intArrayOf(ClayField.N, -1, ClayField.N, -1, ClayField.N, -1)
        val f = field.f; val mask = ClayField.MASK
        val s2 = 2f * (0.6f * r) * (0.6f * r)
        for ((cc, d) in centers) {
            val reach = r + d.length + 2 * ClayField.H
            val m = cc + d * 0.5f
            val (i0, i1) = ClayField.rangeX(m.x, reach); val (j0, j1) = ClayField.rangeY(m.y, reach); val (k0, k1) = ClayField.rangeZ(m.z, reach)
            if (i0 > i1 || j0 > j1 || k0 > k1) continue
            box[0] = min(box[0], i0); box[1] = max(box[1], i1); box[2] = min(box[2], j0); box[3] = max(box[3], j1); box[4] = min(box[4], k0); box[5] = max(box[5], k1)
            for (k in k0..k1) for (j in j0..j1) for (i in i0..i1) {
                val id = ClayField.idx(i, j, k)
                if (!mask[id]) continue
                val px = ClayField.px(i); val py = ClayField.py(j); val pz = ClayField.pz(k)
                val mx = px - m.x; val my = py - m.y; val mz = pz - m.z
                if (mx * mx + my * my + mz * mz > reach * reach) continue
                val qx = px - cc.x - d.x; val qy = py - cc.y - d.y; val qz = pz - cc.z - d.z
                val w = exp(-(qx * qx + qy * qy + qz * qz) / s2)
                f[id] = pullBase.sample(px - d.x * w, py - d.y * w, pz - d.z * w)
            }
        }
        if (box[1] >= 0) {
            field.touchBox(box[0], box[1], box[2], box[3], box[4], box[5])
            pullBox = box
            strokeChanged = true
        }
    }

    private fun restoreBox(b: IntArray) {
        val w = b[1] - b[0] + 1
        for (k in b[4]..b[5]) for (j in b[2]..b[3]) { val o = ClayField.idx(b[0], j, k); System.arraycopy(pullBase.f, o, field.f, o, w) }
        field.touchBox(b[0], b[1], b[2], b[3], b[4], b[5])
    }

    // ------------------------------------------------------------------------------------------------ scripted (lessons)
    /** Lessons: start an op (snapshot = the field now; the tip depth of the whole op is found against it). */
    fun scriptBegin() { snap.copyFrom(field); field.clearTouched(); strokeChanged = false; plane = null; absolute = false; phase = Phase.IDLE; lastT = 0.3f; pullBox = null }

    /** Lessons: tip at aim (x, y) on the op snapshot (stroke / spin) or on the live field ([live]). */
    fun scriptPlace(x: Float, y: Float, live: Boolean = false) { placeTip(x, y, if (live) field else snap) }

    /** Lessons: the pull of an op, from the field at its start: grab at aim [at] (live), warp by [d] × [u] (bench). */
    fun scriptPull(atX: Float, atY: Float, d: V3, u: Float, first: Boolean) {
        if (first) { placeTip(atX, atY); pullBase.copyFrom(field); pullStart = tip; pullBox = null }
        pullBox?.let { restoreBox(it) }
        val c = toClay(pullStart)
        val dd = toClay(d * u)
        warp(c, dd, mirrorPairs = false)
        tip = toVol(c + dd)
        orgX = clampf(tip.x, -X_LIM, X_LIM); orgY = clampf(tip.y, Y_LIM_LO, Y_LIM_HI)
    }

    /** Lessons: the tip pressed into the surface by [push] metres along the normal (poke animation only). */
    fun scriptNudge(push: Float) { tip = tip - tipNormal() * push }

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
        /** Fixed ray: from z 0.30 going −z, tilted 15° down (design strokeRules ⑥; lessons and eye-hand without a head). */
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
        const val SCRAPE_RATE = 0.5f
        const val MEAN_RATE = 0.3f
        const val UNDO_MAX = 30
        const val BUDGET_IN = 55_000
        const val BUDGET_OUT = 52_000
    }
}
