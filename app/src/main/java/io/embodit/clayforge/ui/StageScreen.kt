package io.embodit.clayforge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.CircularProgressIndicator
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.clayforge.Cf
import io.embodit.clayforge.LessonStage
import io.embodit.clayforge.Mode
import io.embodit.clayforge.PANEL_ID
import io.embodit.clayforge.PANEL_TAG
import io.embodit.clayforge.R
import io.embodit.clayforge.Studio
import io.embodit.clayforge.domain.V3
import io.embodit.clayforge.platform.CfLog
import io.embodit.clayforge.scene.ClayFrameSystem
import io.embodit.clayforge.scene.ClayScene
import io.embodit.clayforge.scene.Hit
import io.embodit.clayforge.scene.PanelIds
import io.embodit.clayforge.scene.input.BoardInputListener
import io.embodit.clayforge.scene.input.InputSource
import io.embodit.clayforge.scene.input.Offset3M
import io.embodit.clayforge.scene.input.TargetResolver
import io.embodit.clayforge.scene.input.boardInput
import java.lang.ref.WeakReference

/**
 * The Mixed Stage: one full-size SpatialView with the bench (board-input template for the system pointer: eye-hand,
 * hand ray, controller ray, the emulator mouse), the bench chips and lesson panels as attachments. Hands and
 * controllers are read every frame by the scene ([ClayScene.onFrame]).
 */
@Composable
fun StageScreen() {
    val context = LocalContext.current
    val navigator = LocalSpatialNavigator.current
    Cf.navigator = navigator
    val window = Cf.window
    val scene = remember { ClayScene(context.applicationContext, window).also { Cf.scene = WeakReference(it) } }
    val converter = LocalPhysicalLengthConverter.current
    val density = LocalDensity.current
    // N5: one dp in metres in this Stage, measured through the SDK converter; the chip holders scale to design size
    scene.dpM = remember { converter.dpToLength(with(density) { 1.dp }, LengthUnit.Meters) }
    // D41 更正: no app ray; while a tool rides a controller hide the Stage upper-limb / controller rendering (the only
    // SDK knob; whether it also hides the system ray is 待真机核), Default when the tool goes back and when the Stage goes
    val limb = com.pico.spatial.ui.platform.LocalStageUpperLimbRenderModelManager.current
    val res = localizedResources(context, Studio.prefs.lang)

    DisposableEffect(scene) {
        registerSystem<ClayFrameSystem>()
        window.attach(
            open = {
                if (window.panelState == "ON_STOP") runCatching { navigator.restoreWindowContainer(PANEL_ID, PANEL_TAG) }.onFailure { CfLog.w("WINDOW restore: $it") }
                navigator.openWindowContainer(PANEL_ID, PANEL_TAG, android.os.Bundle())
            },
            close = { navigator.closeWindowContainer(PANEL_ID, PANEL_TAG) },
        )
        Cf.closeStage = { runCatching { navigator.closeStage() }.onFailure { CfLog.w("EXIT closeStage: $it") } }
        CfLog.i("STAGE enter N5 dp=${"%.6f".format(scene.dpM)} m")
        onDispose {
            unregisterSystem<ClayFrameSystem>()
            window.detach()
            runCatching { limb.setUpperLimbRenderMode(com.pico.spatial.ui.platform.ability.UpperLimbRenderMode.Default) }
            CfLog.i("LIMB mode=Default why=stage dispose")
            scene.close()
            CfLog.i("STAGE disposed")
        }
    }

    val toolOnCtrl = scene.ctrlHand != null
    LaunchedEffect(toolOnCtrl) {
        val mode = if (toolOnCtrl) com.pico.spatial.ui.platform.ability.UpperLimbRenderMode.Hidden else com.pico.spatial.ui.platform.ability.UpperLimbRenderMode.Default
        val r = runCatching { limb.setUpperLimbRenderMode(mode) }
        CfLog.i("LIMB ${if (toolOnCtrl) "Hidden" else "Default"} why=${if (toolOnCtrl) "tool on controller" else "tool back"} ok=${r.isSuccess}")
    }

    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { Studio.onFocus(focused) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_STOP -> { CfLog.i("LIFECYCLE background"); Studio.onFocus(false); scene.release() }
                Lifecycle.Event.ON_START -> { CfLog.i("LIFECYCLE foreground"); scene.head.start(); scene.controllers.start(); scene.hands.start() }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    CompositionLocalProvider(LocalCfResources provides res) {
        PicoTheme {
            SpatialView(
                modifier = Modifier.fillMaxSize().boardInput(
                    TargetResolver.byName(Hit::of),
                    object : BoardInputListener<Hit> {
                        override fun onTap(target: Hit?, source: InputSource, position: com.pico.spatial.ui.geometry.Offset3D?) = scene.onTap(target, source)
                        override fun onDragStart(target: Hit, source: InputSource): Boolean = scene.dragStart(target, source)
                        override fun onDragMove(totalM: Offset3M) = scene.dragMove(V3(totalM.x, totalM.y, totalM.z))
                        override fun onDragEnd() = scene.dragEnd()
                        override fun onDragCancel() = scene.dragEnd()
                        override fun onHover(target: Hit?, source: InputSource) = Unit
                    },
                ),
                attachments = { PanelIds.all.forEach { id -> AttachmentPanel(id) { CompositionLocalProvider(LocalCfResources provides localizedResources(context, Studio.prefs.lang)) { PicoTheme { StagePanel(id, scene) } } } } },
                initial = { content, attachments -> scene.attach(content, attachments) },
                update = { _, attachments -> scene.bind(attachments) },
            )
        }
    }
}

/** One Stage attachment panel's content (bench chips, lesson caption / transport / score, the HUD line). */
@Composable
private fun StagePanel(id: String, scene: ClayScene) {
    val shown = scene.panelShown[id] == true
    val (w, h) = PanelIds.sizeDp(id)
    if (!shown) { Box(Modifier.size(8.dp)); return }
    when (id) {
        PanelIds.TURN -> Chip(rs(R.string.chip_turn), R.drawable.ic_rotate_right, "turn90", w, h) { CfLog.i("TAP chip turn90"); Studio.turn90() }
        PanelIds.MIRROR -> {
            val closed = !Cf.window.open && Cf.window.everOpened
            if (closed) Chip(rs(R.string.chip_open), R.drawable.ic_open_in_new, "open_panel", w, h) { CfLog.i("TAP chip open panel"); Cf.window.reopen("chip") }
            else Chip(if (Studio.mirror) rs(R.string.act_mirror_on) else rs(R.string.act_mirror_off), R.drawable.ic_flip, "mirror", w, h, on = Studio.mirror) { CfLog.i("TAP chip mirror"); Studio.toggleMirror() }
        }
        PanelIds.CAPTION -> Caption(w, h)
        PanelIds.SCORE -> ScoreCard(w, h)
        PanelIds.HUD -> Hud(scene, w, h)
        else -> {
            val k = PanelIds.TRANSPORT.indexOf(id)
            val trying = Studio.lessonStage == LessonStage.TRY
            val (label, icon) = when (k) {
                0 -> rs(R.string.tr_prev) to R.drawable.ic_skip_previous
                1 -> if (trying) rs(R.string.tr_watch) to R.drawable.ic_visibility else if (Studio.lesson.playing) rs(R.string.tr_pause) to R.drawable.ic_pause else rs(R.string.tr_play) to R.drawable.ic_play_arrow
                2 -> rs(R.string.tr_next) to R.drawable.ic_skip_next
                else -> if (trying) rs(R.string.tr_retry) to R.drawable.ic_restart_alt else rs(R.string.tr_replay) to R.drawable.ic_replay
            }
            val enabled = when (k) { 0 -> Studio.lesson.si > 0; 2 -> trying || Studio.lesson.si < Studio.lesson.steps - 1 || !Studio.lesson.watchedAll; else -> true }
            Chip(label, icon, "transport$k", w, h, enabled = enabled) { Studio.transport(k) }
        }
    }
}

/** A 200 × 120 dp chip: icon over a word (design turn90Chip / openChip / mirrorChip / lessonTransport). */
@Composable
private fun Chip(text: String, icon: Int, name: String, w: Int, h: Int, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = Modifier.size(w.dp, h.dp), enabled = enabled,
        size = ButtonDefaults.Regular.copy(height = h.dp, minWidth = w.dp), shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (on) OnGreen else PrimaryBlue, contentColor = Color.White),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(40.dp))
            Text(text = text, style = PicoTheme.typography.titleMedium, color = Color.White, textAlign = TextAlign.Center, maxLines = 2, onTextLayout = fits("chip/$name"))
        }
    }
}

/** Lesson caption (design lessons.watch.caption): title + step, one sentence ≤ 2 lines. */
@Composable
private fun Caption(w: Int, h: Int) {
    val l = Studio.lesson
    val zh = Studio.zh()
    val step = l.lesson.steps[l.si]
    val body = when {
        Studio.lessonStage == LessonStage.TRY && l.hint != null -> l.hint!!
        Studio.lessonStage == LessonStage.WATCH && l.watchedAll -> rs(R.string.lesson_watched)
        Studio.lessonStage == LessonStage.TRY && l.passed -> rs(R.string.lesson_like)
        else -> if (zh) step.zh else step.en
    }
    Column(Modifier.size(w.dp, h.dp).clip(RoundedCornerShape(28.dp)).background(CardBg).padding(horizontal = 26.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = (if (zh) l.lesson.titleZh else l.lesson.titleEn) + " · " + (if (zh) l.lesson.pieceZh else l.lesson.pieceEn), style = PicoTheme.typography.titleMedium, color = Ink, modifier = Modifier.weight(1f), maxLines = 1, onTextLayout = fits("caption/title"))
            Text(text = rs(R.string.lesson_step, l.si + 1, l.steps), style = PicoTheme.typography.titleMedium, color = InkSoft, maxLines = 1, onTextLayout = fits("caption/step"))
        }
        Text(text = body, style = PicoTheme.typography.titleMedium, color = Ink, maxLines = 3, onTextLayout = fits("caption/body"))
    }
}

/** 完成度小环 (design tryIt.score): percentage + a bar; green「像了！」when passed. */
@Composable
private fun ScoreCard(w: Int, h: Int) {
    val l = Studio.lesson
    val pct = (l.score * 100).toInt()
    val c = if (l.passed) PassGreen else PrimaryBlue
    Column(Modifier.size(w.dp, h.dp).clip(RoundedCornerShape(26.dp)).background(CardBg).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = if (l.passed) rs(R.string.lesson_like_short, pct) else rs(R.string.lesson_score, pct), style = PicoTheme.typography.titleMedium, color = Ink, maxLines = 1, onTextLayout = fits("score/text"))
        Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFFDDD6CC))) {
            Box(Modifier.fillMaxWidth(l.score.coerceIn(0.03f, 1f)).height(14.dp).clip(RoundedCornerShape(7.dp)).background(c))
        }
    }
}

/** Loading /「平视前方」/ recall「看正前方，别动」(S01 / level gate / S21). */
@Composable
private fun Hud(scene: ClayScene, w: Int, h: Int) {
    val t = scene.hudText.value ?: return
    Box(Modifier.size(w.dp, h.dp).clip(RoundedCornerShape(28.dp)).background(CardBg), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (t == "loading") { CircularProgressIndicator(modifier = Modifier.size(32.dp)); Box(Modifier.width(14.dp)) }
            Text(text = when (t) { "loading" -> rs(R.string.hint_loading); "recall" -> rs(R.string.recall_hold); else -> rs(R.string.level_hint) }, style = PicoTheme.typography.titleMedium, color = Ink, maxLines = 2, onTextLayout = fits("hud/$t"))
        }
    }
}

private val CardBg = Color(0xF2FBF8F2)
private val Ink = Color(0xFF2A211B)
private val InkSoft = Color(0xFF5A4E44)
private val PassGreen = Color(0xFF2E7D32)
private val OnGreen = Color(0xFF1F6B3A)
@Suppress("unused") private val unusedMode = Mode.FREE
