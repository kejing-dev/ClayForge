package io.embodit.clayforge.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** One scripted move of a lesson step (design `lessons.data`; sample.html LESSONS is the first version). */
sealed class Op {
    /** Turntable to an absolute angle (degrees; 360 = once round from 270, not back). */
    data class Turn(val to: Float) : Op()
    /** Drag along a polyline of aims (bench x, y); depth on the op-start snapshot. */
    data class Stroke(val pts: List<Pair<Float, Float>>) : Op()
    /** [n] pokes at an aim. */
    data class Poke(val atX: Float, val atY: Float, val n: Int) : Op()
    /** Tool held still at an aim while the turntable turns [deg]. */
    data class Spin(val atX: Float, val atY: Float, val deg: Float) : Op()
    /** Pull: grab at an aim, move by [d] (bench metres). */
    data class Pull(val atX: Float, val atY: Float, val d: V3) : Op()
}

data class Step(val size: ToolSize, val zh: String, val en: String, val ops: List<Op>)

data class Lesson(
    val id: String, val brush: Brush, val start: StartShape,
    val titleZh: String, val titleEn: String, val pieceZh: String, val pieceEn: String,
    val hintZh: String, val hintEn: String, val steps: List<Step>,
)

/**
 * The four demo lessons (design `lessons.schedule`, D1). Coordinates are bench (x, y) aims as in sample.html's LESSONS,
 * depth found from the front like the eye-hand tool.
 */
object Lessons {
    private fun st(size: ToolSize, zh: String, en: String, vararg ops: Op) = Step(size, zh, en, ops.toList())
    private fun line(vararg p: Float) = Op.Stroke(p.toList().chunked(2).map { it[0] to it[1] })

    val all: List<Lesson> = listOf(
        Lesson("bird", Brush.ADD, StartShape.EGG, "第 1 课 · 加泥", "Lesson 1 · Add", "胖胖小鸟", "A chubby bird",
            "这边再多推一下", "Push a little more here",
            listOf(
                st(ToolSize.L, "加泥刀像大拇指：刀尖顶着蛋的上前方，往上推两下，鸟头就鼓出来了。", "The Add tool is like a thumb. Push up twice at the front-top and a head pops out.",
                    line(0f, 0.282f, 0f, 0.318f), line(0f, 0.282f, 0f, 0.318f)),
                st(ToolSize.L, "转 90° 看侧面，从肩膀往后拖一道，这就是一只翅膀。", "Turn 90°. Drag from the shoulder back: that's a wing.",
                    Op.Turn(90f), line(-0.035f, 0.262f, 0f, 0.250f, 0.035f, 0.228f)),
                st(ToolSize.L, "再转到另一边，照样拖一道，第二只翅膀。", "Turn to the other side and drag the same line.",
                    Op.Turn(270f), line(0.035f, 0.262f, 0f, 0.250f, -0.035f, 0.228f)),
                st(ToolSize.M, "转回正面，换中号，在头前面戳两下，就是小尖嘴。", "Back to the front, medium size, two pokes: a beak.",
                    Op.Turn(360f), Op.Poke(0f, 0.300f, 2)),
            )),
        Lesson("bowl", Brush.CARVE, StartShape.BALL, "第 2 课 · 挖", "Lesson 2 · Carve", "带腰线的小碗", "A little bowl",
            "这里挖深一点", "Dig a little deeper here",
            listOf(
                st(ToolSize.L, "挖刀是个小钢环：刀口朝下，在顶上正中按几下，挖出一个坑。", "The Carve tool is a little loop. Press down at the top centre a few times.",
                    Op.Poke(0f, 0.308f, 4)),
                st(ToolSize.L, "刀别动，让转盘转一圈，坑就被挖成一个圆圆的碗口。", "Hold the tool still and turn the wheel once. The pit becomes a round rim.",
                    Op.Spin(0.03f, 0.300f, 360f)),
                st(ToolSize.S, "换小号贴着肚子最鼓的地方，再转一圈，挖出一道腰线。", "Small size on the widest part, turn once more: a waistline.",
                    Op.Spin(0f, 0.212f, 360f)),
            )),
        Lesson("stone", Brush.SMOOTH, StartShape.BLOCK, "第 3 课 · 抹平", "Lesson 3 · Smooth", "圆角石头镇纸", "A stone paperweight",
            "贴平了多抹两下", "Keep it flat and sweep again",
            listOf(
                st(ToolSize.L, "抹平刀是一块薄片：平着贴在前面那条上棱上，从左抹到右，棱角就削成一个斜面。", "The Smooth tool is a flat blade. Lay it on the top front edge and sweep left to right.",
                    line(-0.045f, 0.26f, 0f, 0.26f, 0.045f, 0.26f)),
                st(ToolSize.L, "转 90°，下一条棱照样抹一遍。", "Turn 90° and sweep the next edge the same way.",
                    Op.Turn(90f), line(-0.045f, 0.26f, 0f, 0.26f, 0.045f, 0.26f)),
                st(ToolSize.L, "剩下两条也一样：转一下、抹一下。抹完就是一块圆润的石头。", "Same for the last two: turn, sweep. Now it's a smooth stone.",
                    Op.Turn(180f), line(-0.045f, 0.26f, 0f, 0.26f, 0.045f, 0.26f), Op.Turn(270f), line(-0.045f, 0.26f, 0f, 0.26f, 0.045f, 0.26f)),
            )),
        Lesson("cat", Brush.PULL, StartShape.BALL, "第 4 课 · 拉", "Lesson 4 · Pull", "打盹的猫", "A napping cat",
            "再往外拉一点", "Pull out a little further",
            listOf(
                st(ToolSize.M, "钩刀钩住左上角一点泥，捏紧了往上往外拉，拉多远就长多远：左耳。", "Hook a bit of clay at the top left, hold and pull up and out: the left ear.",
                    Op.Pull(-0.050f, 0.262f, V3(-0.016f, 0.040f, 0.006f))),
                st(ToolSize.M, "右上角照样拉一只：右耳。", "Same at the top right: the right ear.",
                    Op.Pull(0.050f, 0.262f, V3(0.016f, 0.040f, 0.006f))),
                st(ToolSize.M, "转 90°，钩住屁股那边往外拉一条：小尾巴。", "Turn 90°, hook the back and pull out a tail.",
                    Op.Turn(90f), Op.Pull(0.075f, 0.215f, V3(0.042f, 0.004f, 0f))),
            )),
    )

    fun of(id: String) = all.firstOrNull { it.id == id }

    // timing (design lessons.watch.speed: tool 2.5 cm/s, turntable 60°/s, 1.1 s per poke; fly-in 0.45 s)
    const val LEAD_S = 0.45f
    const val PAUSE_S = 2f

    /**
     * Watch time shown on the pick card (QA r2: the card said「约 35 秒」, the demos run 17–26 s). Measured on the
     * emulator (QA r2: 26 / 22.6 / 25.5 / 16.9 s); LessonTest keeps them within 2 s of the players' durations.
     */
    val watchS = mapOf("bird" to 27, "bowl" to 23, "stone" to 26, "cat" to 17)
    fun opDur(op: Op, yawFrom: Float): Float = when (op) {
        is Op.Turn -> max(0.6f, abs(op.to - yawFrom) / 60f)
        is Op.Stroke -> max(2f, polyLen(op.pts) / 0.025f)
        is Op.Poke -> 1.1f * op.n
        is Op.Spin -> op.deg / 60f
        is Op.Pull -> max(2.4f, op.d.length / 0.015f)
    }
    fun lead(op: Op) = if (op is Op.Turn) 0f else LEAD_S

    fun polyLen(pts: List<Pair<Float, Float>>): Float { var l = 0f; for (i in 1 until pts.size) l += hypot(pts[i].first - pts[i - 1].first, pts[i].second - pts[i - 1].second); return l }
    fun polyAt(pts: List<Pair<Float, Float>>, s: Float): Pair<Float, Float> {
        var acc = 0f
        for (i in 1 until pts.size) {
            val l = hypot(pts[i].first - pts[i - 1].first, pts[i].second - pts[i - 1].second)
            if (acc + l >= s || i == pts.size - 1) {
                val u = if (l > 0f) min(1f, (s - acc) / l) else 1f
                return (pts[i - 1].first + (pts[i].first - pts[i - 1].first) * u) to (pts[i - 1].second + (pts[i].second - pts[i - 1].second) * u)
            }
            acc += l
        }
        return pts.last()
    }

    fun easeInOutSine(t: Float) = 0.5f - 0.5f * cos(PI.toFloat() * t.coerceIn(0f, 1f))
    fun easeInOutCubic(t: Float): Float { val x = t.coerceIn(0f, 1f); return if (x < 0.5f) 4 * x * x * x else 1 - (-2 * x + 2).pow(3) / 2 }
    fun easeOutCubic(t: Float) = 1 - (1 - t.coerceIn(0f, 1f)).pow(3)
}

/**
 * Plays one lesson step on a [Sculptor] (watch mode and the offline targets use the same code, design L1): ops in
 * order, each from its own start snapshot, dabs at fixed arc-length steps so the result does not depend on how the
 * time is cut into frames. [advance] to any time; the field at the end equals the precomputed target cell for cell.
 */
class StepPlayer(private val s: Sculptor, val step: Step, private val brush: Brush) {
    private class Ctx { var started = false; var yaw0 = 0f; var cur = 0f; var done = 0; var first = true; var p = 0f; var finished = false }
    private val ctx = step.ops.map { Ctx() }
    private val starts = FloatArray(step.ops.size)
    private val durs = FloatArray(step.ops.size)
    val duration: Float
    var t = 0f; private set
    /** The tool: where it is (bench) and whether it hovers off the surface (fly-in). */
    var hover = 0f; private set
    var finished = false; private set

    init {
        var acc = 0f
        var yaw = Math.toDegrees(s.yaw.toDouble()).toFloat()
        for ((i, op) in step.ops.withIndex()) {
            starts[i] = acc
            val d = Lessons.lead(op) + Lessons.opDur(op, yaw)
            durs[i] = d
            acc += d
            if (op is Op.Turn) yaw = op.to else if (op is Op.Spin) yaw += op.deg
        }
        duration = acc
    }

    /** Time at which half of the step's sculpting (non-turn) time is done (tests: "half injected"). */
    fun sculptMid(): Float {
        val active = step.ops.indices.filter { step.ops[it] !is Op.Turn }
        val total = active.sumOf { (durs[it] - Lessons.lead(step.ops[it])).toDouble() }.toFloat()
        var left = total / 2
        for (i in active) {
            val d = durs[i] - Lessons.lead(step.ops[i])
            if (left <= d) return starts[i] + Lessons.lead(step.ops[i]) + left
            left -= d
        }
        return duration
    }

    private fun setYawDeg(d: Float) { s.yaw = Math.toRadians(d.toDouble()).toFloat() }
    private fun yawDeg() = Math.toDegrees(s.yaw.toDouble()).toFloat()

    /** Runs the step up to time [tEnd] seconds. Returns true when the clay changed. */
    fun advance(tEnd: Float): Boolean {
        s.brush = brush; s.size = step.size; s.mirror = false
        var changed = false
        hover = 0f
        for ((i, op) in step.ops.withIndex()) {
            val c = ctx[i]
            if (c.finished) continue
            val tA = starts[i]
            if (tEnd <= tA) break
            if (!c.started) { c.started = true; c.yaw0 = yawDeg(); if (op !is Op.Turn) s.scriptBegin() }
            val lead = Lessons.lead(op)
            val d = durs[i] - lead
            if (tEnd < tA + lead) {
                // flying to the start point, 5 cm off the surface
                val (x, y) = startAim(op)
                s.scriptPlace(x, y)
                hover = 0.05f * (1 - Lessons.easeOutCubic((tEnd - tA) / lead))
                break
            }
            val p1 = min(1f, (tEnd - tA - lead) / d)
            if (run(op, c, p1)) changed = true
            c.p = p1
            if (p1 >= 1f) c.finished = true else break
        }
        t = tEnd
        if (ctx.all { it.finished }) finished = true
        return changed
    }

    private fun startAim(op: Op): Pair<Float, Float> = when (op) {
        is Op.Stroke -> op.pts[0]
        is Op.Poke -> op.atX to op.atY
        is Op.Spin -> op.atX to op.atY
        is Op.Pull -> op.atX to op.atY
        is Op.Turn -> s.orgX to s.orgY
    }

    private fun run(op: Op, c: Ctx, p1: Float): Boolean {
        val r = step.size.r
        when (op) {
            is Op.Turn -> {
                setYawDeg(c.yaw0 + (op.to - c.yaw0) * Lessons.easeInOutSine(p1))
                s.scriptPlace(s.orgX, s.orgY, live = true)
                return false
            }
            is Op.Stroke -> {
                val len = Lessons.polyLen(op.pts)
                val stepL = r * 0.35f
                val sEnd = len * p1
                var changed = false
                while (c.cur <= sEnd + 1e-6f) {
                    val (x, y) = Lessons.polyAt(op.pts, c.cur)
                    s.scriptPlace(x, y)
                    if (!c.first) { s.dabAt(s.tip, Sculptor.DAB_AMOUNT); changed = true }
                    c.first = false
                    c.cur += stepL
                    if (len == 0f) break
                }
                val (x, y) = Lessons.polyAt(op.pts, sEnd)
                s.scriptPlace(x, y)
                return changed
            }
            is Op.Poke -> {
                val due = kotlin.math.floor(p1 * op.n + 1e-6f).toInt()
                var changed = false
                while (c.done < due) { s.scriptPlace(op.atX, op.atY, live = true); s.dabAt(s.tip, Sculptor.POKE_AMOUNT); c.done++; changed = true }
                s.scriptPlace(op.atX, op.atY, live = true)
                val ph = (p1 * op.n) % 1f
                if (p1 < 1f) s.scriptNudge(sin(PI.toFloat() * min(1f, ph * 1.6f)) * 0.006f)
                return changed
            }
            is Op.Spin -> {
                val arcStep = max(4f, (r * 0.35f / max(0.01f, abs(op.atX) + 0.06f)) * 180f / PI.toFloat())
                var changed = false
                while (c.cur <= op.deg * p1 + 1e-6f) {
                    setYawDeg(c.yaw0 + c.cur)
                    s.scriptPlace(op.atX, op.atY)
                    s.dabAt(s.tip, Sculptor.DAB_AMOUNT)
                    changed = true
                    c.cur += arcStep
                }
                setYawDeg(c.yaw0 + op.deg * p1)
                s.scriptPlace(op.atX, op.atY)
                return changed
            }
            is Op.Pull -> {
                val u = Lessons.easeInOutCubic(p1)
                s.scriptPull(op.atX, op.atY, op.d, u, c.first)
                c.first = false
                return true
            }
        }
    }

    /** The aim points this step's tool travels (for the try-it guide dots), with the yaw at each, clay coordinates. */
    companion object {
        /**
         * Guide marks of a step on its start field (design tryIt.guide): white dots every 8 mm along the path, the
         * start ring (pokes have only that), pull arrows. Clay coordinates (they turn with the turntable).
         */
        fun guide(step: Step, brush: Brush, start: ClayField, startYawDeg: Float): Guide {
            val f = start.copy()
            val sc = Sculptor(f).also { it.brush = brush; it.size = step.size; it.yaw = Math.toRadians(startYawDeg.toDouble()).toFloat() }
            val dots = ArrayList<V3>(); var ring: V3? = null; val arrows = ArrayList<V3>()
            var yaw = startYawDeg
            fun clayAt(x: Float, y: Float): V3 { sc.yaw = Math.toRadians(yaw.toDouble()).toFloat(); sc.placeTip(x, y); return sc.toClay(sc.tip) }
            for (op in step.ops) when (op) {
                is Op.Turn -> yaw = op.to
                is Op.Stroke -> {
                    val len = Lessons.polyLen(op.pts)
                    var s = 0f
                    while (s <= len + 1e-6f) { val (x, y) = Lessons.polyAt(op.pts, s); dots += clayAt(x, y); s += 0.008f }
                    if (ring == null) ring = clayAt(op.pts[0].first, op.pts[0].second)
                }
                is Op.Poke -> if (ring == null) ring = clayAt(op.atX, op.atY) else dots += clayAt(op.atX, op.atY)
                is Op.Spin -> {
                    val p = clayAt(op.atX, op.atY)
                    if (ring == null) ring = p
                    val r = hypot(p.x, p.z); val a0 = kotlin.math.atan2(p.z, p.x)
                    val n = max(8, (2 * PI * r / 0.008f).toInt())
                    for (k in 1 until n) { val a = a0 + 2 * PI.toFloat() * k / n; dots += V3(r * cos(a), p.y, r * sin(a)) }
                    yaw += op.deg
                }
                is Op.Pull -> {
                    val p = clayAt(op.atX, op.atY)
                    if (ring == null) ring = p
                    sc.yaw = Math.toRadians(yaw.toDouble()).toFloat()
                    val d = sc.toClay(op.d)
                    val n = max(3, (op.d.length / 0.008f).toInt())
                    for (k in 1..n) arrows += p + d * (k / n.toFloat())
                }
            }
            return Guide(dots, ring, arrows)
        }
    }
}

data class Guide(val dots: List<V3>, val ring: V3?, val arrows: List<V3>)

/** Offline targets of a lesson (design lessons.watch.snapshots / tryIt.score): start and end field + yaw per step. */
class LessonPlan(val lesson: Lesson, val starts: List<ClayField>, val ends: List<ClayField>, val yaws: List<Float>, val endYaw: Float) {
    private val masks: List<IntArray>
    private val dens: FloatArray

    init {
        val m = ArrayList<IntArray>(); val d = FloatArray(starts.size)
        for (si in starts.indices) {
            val s = starts[si].f; val t = ends[si].f
            val idx = ArrayList<Int>()
            var den = 0f
            for (i in t.indices) { val dd = abs(t[i] - s[i]); if (dd > 0.04f) { idx += i; den += dd } }
            m += idx.toIntArray(); d[si] = den
        }
        masks = m; dens = d
    }

    /** 完成度 = 1 − Σ_M |U − T| ÷ Σ_M |S − T| over the cells this step changes (design tryIt.score). */
    fun score(si: Int, u: ClayField): Float {
        val den = dens[si]
        if (den <= 0f) return 1f
        val t = ends[si].f; val uf = u.f
        var e = 0f
        for (i in masks[si]) e += abs(uf[i] - t[i])
        return (1f - e / den).coerceIn(0f, 1f)
    }

    /** The guide dot index where you are farthest from the target (design tryIt.whereOff), or −1. */
    fun worstDot(si: Int, u: ClayField, dots: List<V3>): Int {
        var best = -1; var worst = 0f
        val t = ends[si]
        for ((k, p) in dots.withIndex()) {
            var e = 0f
            for (dy in -1..1) for (dx in -1..1) for (dz in -1..1) {
                val q = V3(p.x + dx * 0.008f, p.y + dy * 0.008f, p.z + dz * 0.008f)
                e += abs(u.sample(q.x, q.y, q.z) - t.sample(q.x, q.y, q.z))
            }
            if (e > worst) { worst = e; best = k }
        }
        return best
    }

    companion object {
        /** Plays every step of [lesson] instantly from its start shape. */
        fun build(lesson: Lesson): LessonPlan {
            val f = ClayField().apply { fromSdf(lesson.start::sdf) }
            val s = Sculptor(f)
            val starts = ArrayList<ClayField>(); val ends = ArrayList<ClayField>(); val yaws = ArrayList<Float>()
            for (step in lesson.steps) {
                starts += f.copy(); yaws += Math.toDegrees(s.yaw.toDouble()).toFloat()
                val p = StepPlayer(s, step, lesson.brush)
                p.advance(p.duration + 1f)
                ends += f.copy()
            }
            return LessonPlan(lesson, starts, ends, yaws, Math.toDegrees(s.yaw.toDouble()).toFloat())
        }
    }
}
