package io.embodit.clayforge.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialDragGesture
import com.pico.spatial.ui.foundation.gesture.detectSpatialTapGesture
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.clayforge.Cf
import io.embodit.clayforge.PANEL_ID
import io.embodit.clayforge.PANEL_TAG
import io.embodit.clayforge.Phase
import io.embodit.clayforge.R
import io.embodit.clayforge.Studio
import io.embodit.clayforge.domain.Layout
import io.embodit.clayforge.domain.V3
import io.embodit.clayforge.platform.CfLog
import io.embodit.clayforge.scene.ClayScene
import io.embodit.clayforge.scene.Hit
import kotlinx.coroutines.delay
import java.lang.ref.WeakReference

/** Walks from the hit entity up its parents until a name maps to a [Hit] (GLB meshes report the node, not the holder). */
fun resolveHit(e: Entity?): Pair<Hit?, String> {
    var p = e; var depth = 0
    val path = StringBuilder()
    while (p != null && depth < 10) {
        val n = runCatching { p.getName() }.getOrDefault("?")
        if (path.isNotEmpty()) path.append(" < ")
        path.append(n)
        Hit.of(n)?.let { return it to path.toString() }
        p = if (p.hasParent()) p.getParent() else null
        depth++
    }
    return null to path.toString()
}


/** design N5: the chips' target size is 0.08 × 0.065 m for a 120 × 98 dp panel. */
private const val CHIP_W_DP = 120
private const val CHIP_H_DP = 98

@Composable
fun StudioVolume() {
    val context = LocalContext.current
    val navigator = LocalSpatialNavigator.current
    Cf.navigator = navigator
    val scene = remember { ClayScene(context).also { Cf.scene = WeakReference(it) } }
    val density = LocalDensity.current
    val converter = LocalPhysicalLengthConverter.current
    fun meter(px: Float) = with(density) { converter.dpToLength(px.toDp(), LengthUnit.Meters) }
    val res = localizedResources(context, Studio.prefs.lang)
    var turnChip by remember { mutableStateOf<Entity?>(null) }
    var openChip by remember { mutableStateOf<Entity?>(null) }
    // N5: one dp in metres on this volume, measured through the SDK converter (logged, the chip scale follows it)
    val dpM = remember { converter.dpToLength(with(density) { 1.dp }, LengthUnit.Meters) }
    val chipScale = remember { Layout.CHIP_W / (CHIP_W_DP * dpM) }

    fun placeChips() {
        val panelClosed = Studio.panelState == "ON_DESTROY"
        turnChip?.let { e ->
            e.components.get<com.pico.spatial.core.ecs.TransformComponent>()?.setPosition(Vector3(Layout.CHIP_TURN.x, Layout.CHIP_TURN.y - Layout.VOL_H / 2, Layout.CHIP_TURN.z))
            // S18: with the panel closed in the glaze step the turn chip hides (≤ 7 volume controls); never while firing
            e.enabled = Studio.phase != Phase.FIRING && Studio.phase != Phase.LOADING && !(panelClosed && Studio.phase == Phase.GLAZE)
        }
        openChip?.let { e ->
            e.components.get<com.pico.spatial.core.ecs.TransformComponent>()?.setPosition(Vector3(Layout.CHIP_OPEN.x, Layout.CHIP_OPEN.y - Layout.VOL_H / 2, Layout.CHIP_OPEN.z))
            e.enabled = panelClosed
        }
    }
    Cf.onChips = { placeChips() }
    scene.onChipsChanged = { placeChips() }
    scene.onReady = { Studio.onSceneBuilt(scene); placeChips() }

    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused) { Studio.onFocus(focused) }
    LaunchedEffect(Unit) {
        CfLog.i("N5 dp=${"%.6f".format(dpM)} m chip=${CHIP_W_DP}x${CHIP_H_DP}dp scale=${"%.3f".format(chipScale)} → ${"%.3f".format(CHIP_W_DP * dpM * chipScale)} m")
        CfLog.i("WINDOW open panel why=start"); navigator.openWindowContainer(PANEL_ID, PANEL_TAG)
    }
    LaunchedEffect(Studio.panelState, Studio.phase, turnChip, openChip) { placeChips() }
    // reopen = close, wait for ON_DESTROY, open (native-window-move §3.5); comes back right of the volume (no promise of "in front", D44)
    LaunchedEffect(Studio.reopenRequested, Studio.panelState) {
        if (!Studio.reopenRequested) return@LaunchedEffect
        when (Studio.panelState) {
            "ON_DESTROY", "never" -> { Studio.reopenRequested = false; CfLog.i("WINDOW open panel why=reopen"); navigator.openWindowContainer(PANEL_ID, PANEL_TAG) }
            else -> { CfLog.i("WINDOW close panel why=reopen"); navigator.closeWindowContainer(PANEL_ID, PANEL_TAG) }
        }
    }
    LaunchedEffect(Studio.closeRequested) {
        if (!Studio.closeRequested) return@LaunchedEffect
        Studio.closeRequested = false
        if (Studio.panelState != "ON_DESTROY") { CfLog.i("WINDOW close panel why=close"); navigator.closeWindowContainer(PANEL_ID, PANEL_TAG) }
    }
    // cold-start diagnostics at 1 / 3 / 5 s
    LaunchedEffect(Unit) {
        var t = 0L
        for (at in listOf(1000L, 3000L, 5000L)) { delay(at - t); t = at
            CfLog.i("COLD t=${at / 1000}s ready=${scene.ready} state=${Studio.stateId()} panel=${Studio.panelState} tris=${scene.triangles}") }
    }
    // the debug receiver is declared in the debug manifest (debug builds only)
    DisposableEffect(Unit) { onDispose { CfLog.i("VOLUME dispose"); scene.close() } }

    CompositionLocalProvider(LocalCfResources provides res) {
        PicoTheme {
            SpatialView(
                modifier = Modifier.fillMaxSize()
                    // 1. system taps
                    .pointerInput(Unit) {
                        detectSpatialTapGesture(context, TargetEntity.any()) { v ->
                            val (hit, path) = resolveHit(v.targetEntity)
                            CfLog.i("TAP hit=$hit path=$path kind=${v.interactionKind}")
                            lastTap = SystemClock.uptimeMillis()
                            Cf.scene?.get()?.tap(hit)
                        }
                    }
                    // 2. drags; dragAmount = movement since the press (px, viewer axes) → metres, y up
                    .pointerInput(Unit) {
                        var hit: Hit? = null; var taken = false; var maxLen = 0f; var t0 = 0L; var n = 0
                        detectSpatialDragGesture(context, targetedToEntity = TargetEntity.any(),
                            onDragStart = { _, v ->
                                val (h, path) = resolveHit(v.targetEntity)
                                hit = h; taken = false; maxLen = 0f; t0 = SystemClock.uptimeMillis(); n = 0
                                CfLog.i("DRAG press hit=$h path=$path kind=${v.interactionKind}")
                            },
                            onDragEnd = {
                                val s = Cf.scene?.get()
                                if (taken) s?.dragEnd()
                                else if (SystemClock.uptimeMillis() - lastTap > 250 && hit != null) { CfLog.i("DRAG->TAP hit=$hit travel=${"%.3f".format(maxLen)}"); s?.tap(hit) }
                                CfLog.i("DRAG end events=$n taken=$taken travel=${"%.3f".format(maxLen)} ms=${SystemClock.uptimeMillis() - t0}")
                                taken = false
                            },
                            onDragCancel = { if (taken) Cf.scene?.get()?.dragEnd(); taken = false; CfLog.i("DRAG cancel") },
                        ) { v ->
                            n++
                            val d = v.dragAmount
                            val m = V3(meter(d.x), -meter(d.y), meter(d.z))
                            if (n <= 3 || n % 20 == 0) CfLog.i("DRAG ev=$n amount=$m")
                            maxLen = maxOf(maxLen, m.length)
                            val s = Cf.scene?.get() ?: return@detectSpatialDragGesture
                            val h = hit
                            if (!taken && h != null) {
                                // the head and the knob are taken at once (the sculptor has its own start gate, its tip follows inside it)
                                val need = if (h == Hit.Head || h == Hit.Knob) 0f else 0.01f
                                if (m.length > need) taken = s.dragStart(h)
                            }
                            if (taken) s.dragMove(m)
                        }
                    },
                initial = { content, attachments ->
                    content.addEntity(scene.root)
                    attachments.entity("turnChip")?.let { content.addEntity(it); it.components.get<com.pico.spatial.core.ecs.TransformComponent>()?.setScaleVector(Vector3(chipScale, chipScale, chipScale)); turnChip = it }
                    attachments.entity("openChip")?.let { content.addEntity(it); it.components.get<com.pico.spatial.core.ecs.TransformComponent>()?.setScaleVector(Vector3(chipScale, chipScale, chipScale)); openChip = it }
                    runCatching { scene.build() }.onFailure { CfLog.e("SCENE build failed", it) }
                },
                attachments = {
                    AttachmentPanel(id = "turnChip") { CompositionLocalProvider(LocalCfResources provides res) { PicoTheme { Chip(rs(R.string.chip_turn), R.drawable.ic_rotate_right, "turn90") { Studio.turn90() } } } }
                    AttachmentPanel(id = "openChip") { CompositionLocalProvider(LocalCfResources provides res) { PicoTheme { Chip(rs(R.string.chip_open), R.drawable.ic_open_in_new, "open_panel") { Studio.reopenRequested = true } } } }
                },
            )
        }
    }
}

private var lastTap = 0L

/** A 120 × 98 dp chip: icon over a word (design panels.turn90Chip / openChip). */
@Composable
private fun Chip(text: String, icon: Int, name: String, onClick: () -> Unit) {
    val c = PicoTheme.colorScheme
    Button(
        onClick = { CfLog.i("TAP chip $name"); onClick() }, modifier = Modifier.size(CHIP_W_DP.dp, CHIP_H_DP.dp),
        size = ButtonDefaults.Regular.copy(height = CHIP_H_DP.dp, minWidth = CHIP_W_DP.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue, contentColor = androidx.compose.ui.graphics.Color.White),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(28.dp))
            Text(text = text, style = PicoTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 2, onTextLayout = fits("chip/$name"))
        }
    }
}
