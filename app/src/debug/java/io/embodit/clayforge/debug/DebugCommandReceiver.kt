package io.embodit.clayforge.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import io.embodit.clayforge.Cf
import io.embodit.clayforge.Page
import io.embodit.clayforge.Phase
import io.embodit.clayforge.Studio
import io.embodit.clayforge.domain.Brush
import io.embodit.clayforge.domain.Glaze
import io.embodit.clayforge.domain.Layout
import io.embodit.clayforge.domain.Sample
import io.embodit.clayforge.domain.StartShape
import io.embodit.clayforge.domain.ToolSize
import io.embodit.clayforge.domain.V3
import io.embodit.clayforge.platform.CfLog
import io.embodit.clayforge.scene.Hit
import io.embodit.clayforge.scene.input.InputSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Debug builds only (design `verification.debugHooks`). Every command goes through the same Studio / scene entry as
 * the real pointer and buttons (strokes are fed to the same dragStart / dragMove / dragEnd at 20 Hz).
 *
 *   adb shell "am broadcast -a io.embodit.clayforge.DEBUG -p io.embodit.clayforge --es cmd '<cmd>[; <cmd> …]'"
 *
 * See AGENTS.md for the full list: state S05..S26 · lesson … · hand / ctrl injection · handstroke / ctrlstroke ·
 * stroke / poke / movetip (eye-hand pointer path) · benchmove · recall · fire [hold <s>] · page … · dump.
 */
class DebugCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        Studio.scope.launch { cmd.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { line -> runCatching { run(line) }.onFailure { CfLog.e("CMD $line failed", it) } } }
    }

    private suspend fun waitIdle(ms: Long = 6000) {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < ms) { val s = Cf.scene?.get(); if (s != null && s.ready && Studio.phase != Phase.FIRING) return; delay(50) }
    }

    private suspend fun run(line: String) {
        val a = line.split(Regex("\\s+"))
        val arg = a.getOrNull(1)
        CfLog.i("CMD $line")
        val s = Cf.scene?.get()
        when (a[0]) {
            "state" -> state(arg ?: "S05")
            "tool" -> { Brush.of(arg!!)?.let { b -> if (b != Studio.brush || Cf.scene?.get()?.toolOut != true) Studio.tapSlot(b, "debug") }; a.getOrNull(2)?.let { sz -> ToolSize.of(sz)?.let { var n = 0; while (Studio.size != it && n++ < 3) Studio.tapSlot(Studio.brush, "debug") } } }
            "size" -> Studio.tapSlot(Studio.brush, "debug")
            "stroke" -> stroke(Brush.of(arg ?: "add") ?: Brush.ADD, a.getOrNull(2) ?: "line", a.drop(3))
            "release" -> s?.dragEnd()
            "poke" -> s?.onTap(Hit.Head, InputSource.UNKNOWN)
            "movetip" -> movetip(a[1].toFloat(), a[2].toFloat(), a.contains("half"))
            "yaw" -> s?.setYaw(Math.toRadians(arg!!.toDouble()).toFloat())
            "turn90" -> Studio.turn90()
            "drag" -> if (arg == "turn") dragTurn(a[2].toFloat())
            "mirror" -> if ((arg == "on") != Studio.mirror) Studio.toggleMirror()
            "undo" -> Studio.undo()
            "glaze" -> { if (Studio.phase == Phase.SCULPT) Studio.enterGlaze(); arg?.let { Glaze.of(it)?.let { g -> s?.onTap(Hit.Jar(g), InputSource.UNKNOWN) } } }
            "jar" -> Glaze.of(arg)?.let { s?.onTap(Hit.Jar(it), InputSource.UNKNOWN) }
            "fire" -> when (arg) {
                "release" -> s?.holdKilnAt(null)
                else -> { if (Studio.phase == Phase.SCULPT) Studio.enterGlaze(); if (Studio.phase == Phase.GLAZE) Studio.fire(); if (arg == "hold") s?.holdKilnAt(a[2].toFloat()) }
            }
            "back" -> Studio.backToSculpt()
            "backclay" -> Studio.backToClay()
            "open" -> when (arg) {
                "sample" -> { Studio.openPage(Page.WORKS, "debug"); Sample.of(a[2])?.let { Studio.openSample(it) } }
                "work" -> { Studio.openPage(Page.WORKS, "debug"); Studio.works.getOrNull(a[2].toInt())?.let { Studio.openWork(it.id) } }
            }
            "new" -> Studio.newLump(StartShape.of(arg ?: "ball"))
            "newlump" -> Studio.openNewLump(if (Studio.page == Page.WORKS) Page.WORKS else Page.FREE)
            "export" -> Studio.export(Studio.prefs.lang != "en")
            "fill" -> fill()
            "corruptSave" -> { Studio.saveNow(); (Studio.currentId ?: Studio.prefs.lastWorkId)?.let { Studio.store.corrupt(it); CfLog.i("CORRUPTED $it (force-stop and relaunch → S19)") } }
            "closepanel" -> Cf.window.close()
            "reopen" -> Cf.window.reopen("debug")
            "recall" -> Studio.recall()
            "lang" -> Studio.setLang(arg ?: "system")
            "mute" -> if ((arg == "on") != Studio.prefs.muted) Studio.toggleMute()
            "volume" -> Studio.setVolume((arg ?: "65").toFloat() / 100f)
            "page" -> Studio.openPage(Page.valueOf(arg!!.uppercase()), "debug")
            "howto" -> { Studio.openPage(Page.SETTINGS, "debug"); Studio.howTo = arg != "off" }
            "detail" -> if (arg == "back") Studio.closeDetail()
            "delete" -> { if (arg == "confirm") Studio.deleteCurrentDetail() else Studio.confirmDelete = true }
            "confirm" -> if (arg == "exit") { Studio.openPage(Page.SETTINGS, "debug"); Studio.confirmExit = true }
            "exit" -> Cf.navigator?.let { n -> Studio.scope.launch { io.embodit.clayforge.ui.exitApp(Cf.appContext, n) } }
            "tipInfo" -> tipInfo()
            "boundsCheck" -> boundsCheck()
            "resetOnboarding" -> Studio.updatePrefs { it.copy(onboarded = false) }
            "dump" -> CfLog.i("DUMP ${s?.dump()} page=${Studio.page} mode=${Studio.mode} lesson=${Studio.lessonStage}/${Studio.lesson.li}/${Studio.lesson.si} score=${Studio.lesson.score} detail=${Studio.detail} work=${Studio.currentId} fired=${Studio.currentFired} glaze=${Studio.glaze.id} prefs=${Studio.prefs}")
            "sample" -> Sample.of(arg!!)?.let { Studio.openSample(it) }
            "lesson" -> lesson(a.drop(1))
            "hand" -> if (arg == "off") s?.injectHand(null, false) else s?.injectHand(V3(a[1].toFloat(), a[2].toFloat(), a[3].toFloat()), a.getOrNull(4) == "1")
            "ctrl" -> if (arg == "off") s?.injectController(null, false) else s?.injectController(V3(a[1].toFloat(), a[2].toFloat(), a[3].toFloat()), a.getOrNull(4) == "1")
            "ctrlmount" -> { s?.injectController(V3(0f, 0.2f, 0.2f), false); delay(100); s?.mountController(1, "debug") }
            "ctrlback" -> s?.unmountController("debug")
            "handstroke" -> absStroke(Brush.of(arg ?: "add") ?: Brush.ADD, hand = true, opts = a.drop(2))
            "ctrlstroke" -> absStroke(Brush.of(arg ?: "add") ?: Brush.ADD, hand = false, opts = a.drop(2))
            "benchmove" -> benchMove(a[1].toFloat(), a[2].toFloat(), a[3].toFloat(), a.contains("hold"))
            "benchend" -> s?.dragEnd()
            "stick" -> { s?.controllers?.injectStick(a[1].toFloat(), a.getOrNull(2)?.toFloat() ?: 0f); delay(120); s?.controllers?.injectStick(0f, 0f) }
        }
    }

    private suspend fun lesson(a: List<String>) {
        val s = Cf.scene?.get() ?: return
        val first = a.getOrNull(0) ?: return
        when (first) {
            "start" -> Studio.startLesson("debug")
            "try" -> { if (Studio.lessonStage != io.embodit.clayforge.LessonStage.WATCH) { Studio.startLesson("debug"); delay(1500) }; a.getOrNull(1)?.toIntOrNull()?.let { si -> Studio.lesson.si = si - 1; Studio.lesson.watchedAll = false }; Studio.tryIt() }
            "inject" -> s.injectDemo(a.getOrNull(1) == "half")
            "transport" -> Studio.transport(a[1].toInt(), "debug")
            "done" -> { for (k in 0 until 4) { if (Studio.lessonStage != io.embodit.clayforge.LessonStage.TRY) break; s.injectDemo(false); delay(1600) } }
            "glaze" -> Studio.lessonToGlaze()
            "next" -> Studio.nextLesson()
            "ghost" -> Studio.toggleGhost()
            "pick" -> Studio.pickLesson(a.getOrNull(1)?.toInt() ?: 1)
            else -> {
                // lesson <n> [step <i> t <u>]: lesson n (1–4); with step, watch paused at fraction u of step i
                val n = first.toIntOrNull() ?: return
                Studio.lesson.li = (n - 1).coerceIn(0, 3)
                Studio.openPage(Page.LEARN, "debug")
                Studio.lessonStage = io.embodit.clayforge.LessonStage.PICK
                Studio.openLearn("debug")
                val si = a.indexOf("step").takeIf { it >= 0 }?.let { a[it + 1].toInt() - 1 }
                if (si != null) {
                    val u = a.indexOf("t").takeIf { it >= 0 }?.let { a[it + 1].toFloat() } ?: 0.5f
                    var waited = 0
                    while (!Studio.lesson.planReady && waited++ < 100) delay(50)
                    Studio.startLesson("debug")
                    delay(300)
                    Studio.lesson.si = si; Studio.lesson.playing = false
                    s.seekPlayer(si, u)
                    Studio.size = Studio.lesson.lesson.steps[si].size
                    s.applyTool(animate = false)
                    Studio.logState("lesson seek")
                }
            }
        }
    }

    private suspend fun state(id: String) {
        when (id) {
            "S05" -> { Studio.openPage(Page.FREE, "debug"); delay(300); Sample.CAT_HEAD.let { Studio.openSample(it) }; Studio.openPage(Page.FREE, "debug"); waitIdle(); if (Studio.mirror) Studio.toggleMirror(); Cf.scene?.get()?.let { it.setYaw(0f); it.sculptor.placeTip(0f, 0.2f) } }
            "S06" -> { state("S05"); delay(1500); stroke(Brush.ADD, "zigzag", listOf("half")) }
            "S07" -> { state("S05"); delay(1500); movetip(0.05f, 0.03f, true) }
            "S08" -> { state("S05"); delay(1500); Studio.turn90() }
            "S09" -> { state("S05"); delay(1500); if (!Studio.mirror) Studio.toggleMirror(); stroke(Brush.ADD, "zigzag", emptyList()) }
            "S10" -> { state("S05"); delay(1500); Studio.enterGlaze() }
            "S11", "S12" -> { state("S10"); delay(1200); Studio.fire() }
            "S13" -> { state("S12"); delay(9000); Studio.export(Studio.prefs.lang != "en") }
            "S14" -> Studio.openPage(Page.WORKS, "debug")
            "S15" -> { Studio.openPage(Page.WORKS, "debug"); Studio.openSample(Sample.MUSHROOM) }
            "S16" -> { Studio.openPage(Page.WORKS, "debug"); Studio.openNewLump(Page.WORKS) }
            "S17" -> Studio.openPage(Page.SETTINGS, "debug")
            "S18" -> Cf.window.close()
            "S20" -> fill()
            "S21" -> Studio.recall()
            "S22" -> benchMove(0.2f, 0f, 0f, hold = true)
            "S23" -> Studio.openPage(Page.LEARN, "debug")
            "S24" -> { Studio.openPage(Page.LEARN, "debug"); delay(800); Studio.startLesson("debug") }
            "S25" -> lesson(listOf("try"))
            "S26" -> { lesson(listOf("try")); delay(1200); lesson(listOf("done")) }
        }
    }

    /** A hand pinch / controller trigger stroke through the scene's real channel (injected tracker data, bench coordinates). */
    private suspend fun absStroke(b: Brush, hand: Boolean, opts: List<String>) {
        val s = Cf.scene?.get() ?: return
        if (Studio.phase != Phase.SCULPT) return
        if (Studio.brush != b || !s.toolOut) { Studio.tapSlot(b, "debug"); delay(300) }
        // the clay front at y 0.2: find it along −z from the bench front
        s.sculptor.placeTip(0f, 0.25f)
        val front = s.sculptor.tip
        val n = s.sculptor.tipNormal()
        val out = opts.contains("out") || b == Brush.PULL
        val pts = if (out) (0..30).map { i -> front + n * (0.04f * i / 30f) } else (0..30).map { i -> front + V3(-0.05f + 0.1f * i / 30f, 0f, 0f) }
        if (!hand) { s.injectController(pts[0] + n * 0.03f, false); delay(150); s.mountController(1, "debug stroke"); delay(150) }
        fun inject(p: V3, on: Boolean) = if (hand) s.injectHand(p, on) else s.injectController(p, on)
        inject(pts[0] + n * 0.002f, false); delay(120)
        inject(pts[0], true); delay(120)
        val half = opts.contains("half")
        val m = if (half) pts.size / 2 else pts.size
        for (k in 0 until m) { inject(pts[k], true); delay(40) }
        if (!half) { inject(pts[m - 1], false); delay(200); if (hand) s.injectHand(null, false) }
        CfLog.i("ABSSTROKE ${if (hand) "hand" else "ctrl"} brush=${b.id} points=$m half=$half out=$out")
    }

    private suspend fun benchMove(dx: Float, dy: Float, dz: Float, hold: Boolean) {
        val s = Cf.scene?.get() ?: return
        if (!s.dragStart(Hit.Bench, InputSource.UNKNOWN)) return
        for (i in 1..12) { s.dragMove(V3(dx * i / 12, dy * i / 12, dz * i / 12)); delay(40) }
        if (!hold) s.dragEnd()
    }

    /** A stroke through the real drag pipeline: press on the head, cumulative movement at 20 Hz, release. */
    private suspend fun stroke(b: Brush, path: String, opts: List<String>) {
        val s = Cf.scene?.get() ?: return
        if (Studio.phase != Phase.SCULPT) return
        if (Studio.brush != b || !s.toolOut) { Studio.tapSlot(b, "debug"); delay(300) }
        opts.firstOrNull { ToolSize.of(it) != null }?.let { sz -> var k = 0; while (Studio.size.id != sz && k++ < 3) Studio.tapSlot(Studio.brush, "debug") }
        val pts = when (path) {
            "zigzag" -> (0..40).map { i -> val t = i / 40f; V3(-0.08f + 0.16f * t, 0.03f * (if ((i / 5) % 2 == 0) (i % 5) / 5f else 1 - (i % 5) / 5f) - 0.015f, 0f) }
            "circle" -> (0..48).map { i -> val a = 2 * PI.toFloat() * i / 48; V3(0.05f * cos(a) - 0.05f, 0.05f * sin(a), 0f) }
            "spiral" -> (0..60).map { i -> val a = 4 * PI.toFloat() * i / 60; val r = 0.01f + 0.05f * i / 60; V3(r * cos(a) - 0.01f, r * sin(a), 0f) }
            else -> (0..30).map { i -> V3(-0.07f + 0.14f * i / 30f, 0f, 0f) }
        }
        val start = pts.first()
        // the tip jumps to the path start with the move knob (real path, no sculpting)
        s.dragStart(Hit.Knob, InputSource.UNKNOWN)
        for (i in 0..10) { s.dragMove(start * (i / 10f)); delay(40) }
        repeat(6) { s.dragMove(start); delay(40) }
        s.dragEnd()
        delay(80)
        s.dragStart(Hit.Head, InputSource.UNKNOWN)
        val half = opts.contains("half")
        val n = if (half) pts.size / 2 else pts.size
        for (i in 0 until n) { s.dragMove(pts[i] - start); delay(50) }
        if (!half) repeat(6) { s.dragMove(pts[n - 1] - start); delay(40) }
        if (!half) s.dragEnd()
        CfLog.i("STROKE injected brush=${b.id} path=$path points=$n half=$half")
    }

    private suspend fun movetip(dx: Float, dy: Float, half: Boolean) {
        val s = Cf.scene?.get() ?: return
        s.dragStart(Hit.Knob, InputSource.UNKNOWN)
        val steps = 12
        for (i in 1..(if (half) steps / 2 else steps)) { s.dragMove(V3(dx * i / steps, dy * i / steps, 0f)); delay(50) }
        if (!half) repeat(6) { s.dragMove(V3(dx, dy, 0f)); delay(40) }
        if (!half) s.dragEnd()
    }

    private suspend fun dragTurn(dx: Float) {
        val s = Cf.scene?.get() ?: return
        s.dragStart(Hit.Ring, InputSource.UNKNOWN)
        for (i in 1..10) { s.dragMove(V3(dx * i / 10, 0f, 0f)); delay(40) }
        s.dragEnd()
    }

    /** S20: grows a gyroid sponge (~59k triangles, domain FillShape) as a new lump, then tries one add poke (blocked). */
    private suspend fun fill() {
        val s = Cf.scene?.get() ?: return
        if (Studio.phase != Phase.SCULPT) return
        Studio.newLump(StartShape.BALL)
        delay(300)
        s.loadShape { f -> f.fromSdf(io.embodit.clayforge.domain.FillShape::sdf) }
        delay(2500)
        Studio.tapSlot(Brush.ADD, "debug")
        delay(400)
        s.onTap(Hit.Head, InputSource.UNKNOWN)
        CfLog.i("FILL done tris=${Studio.triangles} tooMuch=${Studio.tooMuch}")
        Studio.logState("fill")
    }

    private fun tipInfo() {
        val s = Cf.scene?.get() ?: return
        val sc = s.sculptor
        val t = sc.rayDepth(sc.orgX, sc.orgY)
        val front = t?.let { V3(sc.orgX, sc.orgY, 0.30f) + io.embodit.clayforge.domain.Sculptor.DIR * it }
        val err = if (front != null) front.dist(sc.tip) else -1f
        CfLog.i("TIPINFO org=(${"%.3f".format(sc.orgX)}, ${"%.3f".format(sc.orgY)}) tip=${sc.tip} front=$front err=${"%.4f".format(err)}m cell=0.005 phase=${sc.phase} yaw=${s.yawDeg().toInt()}")
    }

    private fun boundsCheck() {
        val s = Cf.scene?.get() ?: return
        val bad = s.boundsCheck()
        CfLog.i("BOUNDS outside=${bad.size} ${bad.take(5)}")
    }
}
