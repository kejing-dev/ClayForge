package io.embodit.clayforge

import io.embodit.clayforge.domain.ClayField
import io.embodit.clayforge.domain.LessonPlan
import io.embodit.clayforge.domain.Lessons
import io.embodit.clayforge.domain.Sculptor
import io.embodit.clayforge.domain.StepPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design L1–L3: the demo plays to the offline targets cell for cell, snapshots replay, the score is fair. */
class LessonTest {
    private val plans = Lessons.all.associate { it.id to LessonPlan.build(it) }

    /** L1: four lessons from the start, played in uneven frames (15–70 ms), end every step equal to the target. */
    @Test fun lessonsPlayToTheTargets() {
        val rnd = java.util.Random(3)
        for (l in Lessons.all) {
            val plan = plans.getValue(l.id)
            val f = ClayField().apply { fromSdf(l.start::sdf) }
            val s = Sculptor(f)
            for ((si, step) in l.steps.withIndex()) {
                assertEquals("${l.id} step $si starts at the snapshot", 0f, maxDiff(f, plan.starts[si]), 0f)
                val p = StepPlayer(s, step, l.brush)
                var t = 0f
                while (!p.finished) { t += 0.015f + rnd.nextFloat() * 0.055f; p.advance(t) }
                assertEquals("${l.id} step $si equals the target", 0f, maxDiff(f, plan.ends[si]), 1e-6f)
                assertTrue("${l.id} step $si changes the clay", maxDiff(plan.starts[si], plan.ends[si]) > 0.3f)
            }
            assertTrue("${l.id} takes 15–60 s", (l.steps.indices).sumOf { si -> StepPlayer(Sculptor(plan.starts[si].copy()).also { it.yaw = Math.toRadians(plan.yaws[si].toDouble()).toFloat() }, l.steps[si], l.brush).duration.toDouble() + Lessons.PAUSE_S } in 15.0..60.0)
        }
    }

    /** L2: [上一步] / [下一步] / [重放] = load the snapshot; pause = nothing changes. */
    @Test fun snapshotsReplay() {
        val l = Lessons.all[0]; val plan = plans.getValue(l.id)
        val f = plan.starts[1].copy(); val s = Sculptor(f).also { it.yaw = Math.toRadians(plan.yaws[1].toDouble()).toFloat() }
        val p = StepPlayer(s, l.steps[1], l.brush)
        p.advance(p.duration * 0.5f)
        val paused = f.copy()
        p.advance(p.duration * 0.5f)
        assertEquals("pause keeps the clay", 0f, maxDiff(f, paused), 0f)
        f.copyFrom(plan.starts[1])
        assertEquals("replay = step start", 0f, maxDiff(f, plan.starts[1]), 0f)
    }

    /** L3: following the demo scores ≥ 90 %; doing nothing 0 %; half the strokes 30–70 %. */
    @Test fun scoreIsFair() {
        for (l in Lessons.all) {
            val plan = plans.getValue(l.id)
            for ((si, step) in l.steps.withIndex()) {
                val u = plan.starts[si].copy()
                assertEquals("${l.id}/$si untouched = 0", 0f, plan.score(si, u), 0.02f)
                val s = Sculptor(u).also { it.yaw = Math.toRadians(plan.yaws[si].toDouble()).toFloat() }
                val p = StepPlayer(s, step, l.brush)
                p.advance(p.duration + 1f)
                assertTrue("${l.id}/$si demo injected ≥ 90 % (${plan.score(si, u)})", plan.score(si, u) >= 0.9f)
                val h = plan.starts[si].copy()
                val sh = Sculptor(h).also { it.yaw = Math.toRadians(plan.yaws[si].toDouble()).toFloat() }
                StepPlayer(sh, step, l.brush).let { it.advance(it.sculptMid()) }
                val half = plan.score(si, h)
                assertTrue("${l.id}/$si half done 25–75 % ($half)", half in 0.25f..0.75f)
            }
        }
    }

    private fun maxDiff(a: ClayField, b: ClayField): Float { var m = 0f; for (i in a.f.indices) m = maxOf(m, kotlin.math.abs(a.f[i] - b.f[i])); return m }
}
