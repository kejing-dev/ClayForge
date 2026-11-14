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
 * state S02..S20 · tool add|carve|smooth|pull [s|m|l] · size · stroke <brush> line|zigzag|circle|spiral [s|m|l] [half] ·
 * release · poke · movetip <dx> <dy> [half] · yaw <deg> · turn90 · drag turn <dx> · mirror on|off · undo · glaze [<id>] ·
 * jar <id> · fire · back · backclay · open sample <id> | open work <index> · new ball|egg|block · newlump · export ·
 * fill · corruptSave · closepanel · reopen · lang zh|en|system · mute on|off · volume <0-100> · page sculpt|works|settings ·
 * detail back · delete [confirm] · confirm exit · exit · tipInfo · boundsCheck · resetOnboarding · guide · dump · probe [s] [n]
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
            "tool" -> { Brush.of(arg!!)?.let { b -> if (b != Studio.brush || Cf.scene?.get()?.toolOut != true) Studio.tapSlot(b) }; a.getOrNull(2)?.let { sz -> ToolSize.of(sz)?.let { while (Studio.size != it) Studio.tapSlot(Studio.brush) } } }
            "size" -> Studio.tapSlot(Studio.brush)
            "stroke" -> stroke(Brush.of(arg ?: "add") ?: Brush.ADD, a.getOrNull(2) ?: "line", a.drop(3))
            "release" -> s?.dragEnd()
            "poke" -> s?.tap(Hit.Head)
            "movetip" -> movetip(a[1].toFloat(), a[2].toFloat(), a.contains("half"))
            "yaw" -> s?.setYaw(Math.toRadians(arg!!.toDouble()).toFloat())
            "turn90" -> Studio.turn90()
            "drag" -> if (arg == "turn") dragTurn(a[2].toFloat())
            "mirror" -> if ((arg == "on") != Studio.mirror) Studio.toggleMirror()
            "undo" -> Studio.undo()
            "glaze" -> { if (Studio.phase == Phase.SCULPT) Studio.enterGlaze(); arg?.let { Glaze.of(it)?.let { g -> s?.tap(Hit.Jar(g)) } } }
            "jar" -> Glaze.of(arg)?.let { s?.tap(Hit.Jar(it)) }
            "fire" -> { if (Studio.phase == Phase.SCULPT) Studio.enterGlaze(); Studio.fire() }
            "back" -> Studio.backToSculpt()
            "backclay" -> Studio.backToClay()
            "open" -> when (arg) {
                "sample" -> { Studio.openPage(Page.WORKS, "debug"); Sample.of(a[2])?.let { Studio.openSample(it) } }
                "work" -> { Studio.openPage(Page.WORKS, "debug"); Studio.works.getOrNull(a[2].toInt())?.let { Studio.openWork(it.id) } }
            }
            "new" -> Studio.newLump(StartShape.of(arg ?: "ball"))
            "newlump" -> Studio.openNewLump(if (Studio.page == Page.WORKS) Page.WORKS else Page.SCULPT)
            "export" -> Studio.export(Studio.prefs.lang != "en")
            "fill" -> fill()
            "corruptSave" -> { Studio.saveNow(); (Studio.currentId ?: Studio.prefs.lastWorkId)?.let { Studio.store.corrupt(it); CfLog.i("CORRUPTED $it (force-stop and relaunch → S19)") } }
            "closepanel" -> Studio.closeRequested = true
            "reopen" -> Studio.reopenRequested = true
            "lang" -> Studio.setLang(arg ?: "system")
            "mute" -> if ((arg == "on") != Studio.prefs.muted) Studio.toggleMute()
            "volume" -> Studio.setVolume((arg ?: "65").toFloat() / 100f)
            "page" -> Studio.openPage(Page.valueOf(arg!!.uppercase()), "debug")
            "detail" -> if (arg == "back") Studio.closeDetail()
            "delete" -> { if (arg == "confirm") Studio.deleteCurrentDetail() else Studio.confirmDelete = true }
            "confirm" -> if (arg == "exit") { Studio.openPage(Page.SETTINGS, "debug"); Studio.confirmExit = true }
            "exit" -> Cf.navigator?.let { n -> Studio.scope.launch { io.embodit.clayforge.ui.exitApp(Cf.appContext, n) } }
            "tipInfo" -> tipInfo()
            "boundsCheck" -> boundsCheck()
            "resetOnboarding" -> Studio.updatePrefs { it.copy(onboarded = false) }
            "guide" -> Studio.replayGuide()
            "dump" -> CfLog.i("DUMP ${s?.dump()} page=${Studio.page} detail=${Studio.detail} newLump=${Studio.newLumpFrom} work=${Studio.currentId} fired=${Studio.currentFired} glaze=${Studio.glaze.id} prefs=${Studio.prefs}")
            "probe" -> s?.probe(arg?.toIntOrNull() ?: 10, a.getOrNull(2)?.toIntOrNull() ?: 6)
            "sample" -> Sample.of(arg!!)?.let { Studio.openSample(it) }
        }
    }

    private suspend fun state(id: String) {
        Studio.openPage(Page.SCULPT, "debug")
        when (id) {
            "S02" -> { Studio.replayGuide(); waitIdle() }
            "S03" -> { state("S02"); delay(800); Studio.tapSlot(Brush.ADD) }
            "S04" -> { state("S03"); delay(600); stroke(Brush.ADD, "line", emptyList()) }
            "S05" -> { if (Studio.guide != 0) { Studio.guide = 0; Studio.updatePrefs { it.copy(onboarded = true) } }; Sample.CAT_HEAD.let { Studio.openSample(it) }; Studio.openPage(Page.SCULPT, "debug"); waitIdle(); if (Studio.mirror) Studio.toggleMirror(); Cf.scene?.get()?.let { it.setYaw(0f); it.sculptor.placeTip(0f, 0.2f) } }
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
            "S18" -> Studio.closeRequested = true
            "S20" -> fill()
        }
    }

    /** A stroke through the real drag pipeline: press on the head, cumulative movement at 20 Hz, release. */
    private suspend fun stroke(b: Brush, path: String, opts: List<String>) {
        val s = Cf.scene?.get() ?: return
        if (Studio.phase != Phase.SCULPT) return
        if (Studio.brush != b || !s.toolOut) { Studio.tapSlot(b); delay(300) }
        opts.firstOrNull { ToolSize.of(it) != null }?.let { sz -> while (Studio.size.id != sz) Studio.tapSlot(Studio.brush) }
        val pts = when (path) {
            "zigzag" -> (0..40).map { i -> val t = i / 40f; V3(-0.08f + 0.16f * t, 0.03f * (if ((i / 5) % 2 == 0) (i % 5) / 5f else 1 - (i % 5) / 5f) - 0.015f, 0f) }
            "circle" -> (0..48).map { i -> val a = 2 * PI.toFloat() * i / 48; V3(0.05f * cos(a) - 0.05f, 0.05f * sin(a), 0f) }
            "spiral" -> (0..60).map { i -> val a = 4 * PI.toFloat() * i / 60; val r = 0.01f + 0.05f * i / 60; V3(r * cos(a) - 0.01f, r * sin(a), 0f) }
            else -> (0..30).map { i -> V3(-0.07f + 0.14f * i / 30f, 0f, 0f) }
        }
        val start = pts.first()
        // the tip jumps to the path start with the move knob (real path, no sculpting)
        s.dragStart(Hit.Knob)
        for (i in 0..10) { s.dragMove(start * (i / 10f)); delay(40) }
        repeat(6) { s.dragMove(start); delay(40) }
        s.dragEnd()
        delay(80)
        s.dragStart(Hit.Head)
        val half = opts.contains("half")
        val n = if (half) pts.size / 2 else pts.size
        for (i in 0 until n) { s.dragMove(pts[i] - start); delay(50) }
        if (!half) repeat(6) { s.dragMove(pts[n - 1] - start); delay(40) }
        if (!half) s.dragEnd()
        CfLog.i("STROKE injected brush=${b.id} path=$path points=$n half=$half")
    }

    private suspend fun movetip(dx: Float, dy: Float, half: Boolean) {
        val s = Cf.scene?.get() ?: return
        s.dragStart(Hit.Knob)
        val steps = 12
        for (i in 1..(if (half) steps / 2 else steps)) { s.dragMove(V3(dx * i / steps, dy * i / steps, 0f)); delay(50) }
        if (!half) repeat(6) { s.dragMove(V3(dx, dy, 0f)); delay(40) }
        if (!half) s.dragEnd()
    }

    private suspend fun dragTurn(dx: Float) {
        val s = Cf.scene?.get() ?: return
        s.dragStart(Hit.Ring)
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
        Studio.tapSlot(Brush.ADD)
        delay(400)
        s.tap(Hit.Head)
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
        val bad = s.boundsCheck() + Layout.panelsOutsideClayCylinder()
        CfLog.i("BOUNDS outside=${bad.size} ${bad.take(5)}")
    }
}
