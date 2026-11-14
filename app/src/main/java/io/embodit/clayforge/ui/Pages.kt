package io.embodit.clayforge.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.clayforge.BuildConfig
import io.embodit.clayforge.Cf
import io.embodit.clayforge.LessonStage
import io.embodit.clayforge.Mode
import io.embodit.clayforge.domain.Lessons
import io.embodit.clayforge.Page
import io.embodit.clayforge.Phase
import io.embodit.clayforge.R
import io.embodit.clayforge.Studio
import io.embodit.clayforge.domain.Brush
import io.embodit.clayforge.domain.ClayField
import io.embodit.clayforge.domain.Glaze
import io.embodit.clayforge.domain.Sample
import io.embodit.clayforge.domain.StartShape
import io.embodit.clayforge.domain.Thumbnail
import io.embodit.clayforge.domain.ToolSize
import io.embodit.clayforge.domain.WorkMeta
import io.embodit.clayforge.platform.CfLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Thumbnails for cards: saved PNGs, and samples / start shapes rendered once on a worker (design `field.thumbnail`). */
object Thumbs {
    val cache = mutableStateMapOf<String, ImageBitmap>()
    private val pending = HashSet<String>()

    fun get(key: String): ImageBitmap? {
        cache[key]?.let { return it }
        if (!pending.add(key)) return null
        Studio.scope.launch {
            val bmp = withContext(Dispatchers.Default) { runCatching { make(key) }.onFailure { CfLog.w("THUMB $key $it") }.getOrNull() }
            if (bmp != null) cache[key] = bmp.asImageBitmap()
            pending.remove(key)
        }
        return null
    }

    fun forget(prefix: String) { cache.keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) } }

    private fun make(key: String): Bitmap? = when {
        key.startsWith("w:") -> { val id = key.removePrefix("w:").substringBefore('@'); BitmapFactory.decodeFile(Studio.store.thumbFile(id).path) }
        key.startsWith("s:") -> Sample.of(key.removePrefix("s:"))?.let { s -> render({ f -> f.fromSdf(s::sdf) }, s.glaze?.argb ?: Glaze.CLAY_ARGB, s.glaze != null) }
        key.startsWith("shape:") -> StartShape.of(key.removePrefix("shape:")).let { s -> render({ f -> f.fromSdf(s::sdf) }, Glaze.CLAY_ARGB, false) }
        else -> null
    }

    private fun render(fill: (ClayField) -> Unit, argb: Int, glossy: Boolean): Bitmap {
        val f = ClayField().also(fill)
        return Bitmap.createBitmap(Thumbnail.render(f, argb, glossy), 256, 256, Bitmap.Config.ARGB_8888)
    }
}

private fun zh(): Boolean = Studio.prefs.lang == "zh" || (Studio.prefs.lang != "en" && java.util.Locale.getDefault().language == "zh")

@Composable
private fun brushName(b: Brush) = rs(when (b) { Brush.ADD -> R.string.brush_add; Brush.CARVE -> R.string.brush_carve; Brush.SMOOTH -> R.string.brush_smooth; Brush.PULL -> R.string.brush_pull })

@Composable
private fun sizeName(s: ToolSize) = rs(when (s) { ToolSize.S -> R.string.size_s; ToolSize.M -> R.string.size_m; ToolSize.L -> R.string.size_l })

/** Page header: title + one read-only line, the always-visible mute toggle on the right (B31 exception, D15). */
@Composable
private fun Header(title: String, sub: String?, name: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Label(title, "$name/title", style = PicoTheme.typography.titleLarge)
            if (sub != null) Label(sub, "$name/sub", secondary = true)
        }
        val m = Studio.prefs.muted
        ToolButton(if (m) rs(R.string.act_unmute) else rs(R.string.act_mute), if (m) R.drawable.ic_volume_off else R.drawable.ic_volume_up, "mute", Modifier.width(104.dp), on = m) { Studio.toggleMute() }
    }
}

/** Short pages sit centred in the window (blank ≤ 25 %, D44); long ones simply fill it. */
@Composable
private fun ColumnScope.Centred(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val hPx = androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
        val key = Studio.stateId() + "/" + Studio.prefs.lang + "/" + Studio.confirmExit + Studio.confirmDelete
        androidx.compose.runtime.LaunchedEffect(key, hPx.floatValue) {
            kotlinx.coroutines.delay(400)
            // D44 blank (content outer extents, top + bottom) in the window; logged per state for ui-audit
            val win = with(density) { io.embodit.clayforge.PANEL_H_DP.dp.toPx() }
            val h = hPx.floatValue
            if (h > 0f) CfLog.i("BLANK state=${Studio.stateId()} lang=${Studio.prefs.lang} confirm=${Studio.confirmExit || Studio.confirmDelete} content=${"%.0f".format(with(density) { h.toDp().value })}dp window=${io.embodit.clayforge.PANEL_H_DP}dp blank=${"%.0f".format((win - h) / win * 100)}%")
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).onGloballyPositioned { c -> hPx.floatValue = c.size.height.toFloat()
        }, verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable
private fun Hint(text: String, sub: String? = null, name: String) {
    Column(Modifier.fillMaxWidth().heightIn(min = 120.dp).clip(RoundedCornerShape(20.dp)).background(PicoTheme.colorScheme.fillTertiary).padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
        Label(text, "$name/hint", style = PicoTheme.typography.bodyLarge)
        if (sub != null) Label(sub, "$name/hint_sub", secondary = true)
    }
}

// ------------------------------------------------------------------------------------------------- 捏
@Composable
fun ColumnScope.SculptPage() {
    if (Studio.newLumpFrom == Page.FREE) { NewLump(); return }
    when (Studio.phase) {
        Phase.LOADING -> SculptIdle(loading = true)
        Phase.SCULPT -> SculptIdle()
        Phase.GLAZE -> GlazePage()
        Phase.FIRING -> FiringPage()
        Phase.FIRED -> FiredPage()
    }
}

@Composable
private fun ColumnScope.SculptIdle(loading: Boolean = false) {
    val (hint, sub) = when {
        loading -> rs(R.string.hint_loading) to null
        Studio.loadError -> rs(R.string.hint_s19) to null
        Studio.tooMuch -> rs(R.string.hint_s20) to null
        Cf.scene?.get()?.benchMoving == true -> rs(R.string.hint_bench) to rs(R.string.hint_bench_sub)
        Studio.brush == Brush.PULL -> rs(R.string.hint_pull) to rs(R.string.hint_idle_sub)
        else -> rs(R.string.hint_idle) to rs(R.string.hint_idle_sub)
    }
    Centred {
        Header(rs(R.string.sculpt_title), rs(R.string.tool_line, brushName(Studio.brush), sizeName(Studio.size)), "sculpt")
        Hint(hint, sub, "sculpt")
        KButton(rs(R.string.act_undo), "undo", Modifier.fillMaxWidth(), enabled = !loading && Studio.undoCount > 0, icon = R.drawable.ic_undo) { Studio.undo() }
        KButton(rs(R.string.act_glaze), "glaze", Modifier.fillMaxWidth(), enabled = !loading, primary = true) { Studio.enterGlaze() }
    }
}

@Composable
private fun ColumnScope.GlazePage() {
    val g = Studio.glaze
    val z = zh()
    Centred {
        Header(rs(R.string.glaze_title), null, "glaze")
        Column(Modifier.fillMaxWidth().heightIn(min = 140.dp).clip(RoundedCornerShape(20.dp)).background(PicoTheme.colorScheme.fillTertiary).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
            // gate:allow button-gap a colour dot beside the glaze name (read-only), no buttons in this row
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(androidx.compose.ui.graphics.Color(g.argb)).border(1.dp, PicoTheme.colorScheme.dividerLine, RoundedCornerShape(22.dp)))
                Label(if (z) g.zh else g.en, "glaze/name", style = PicoTheme.typography.titleMedium)
            }
            Label(if (z) g.tagZh else g.tagEn, "glaze/tag", secondary = true)
            Label(rs(R.string.glaze_hint), "glaze/hint", secondary = true)
        }
        KButton(rs(R.string.act_back_sculpt), "back_sculpt", Modifier.fillMaxWidth()) { Studio.backToSculpt() }
        KButton(rs(R.string.act_fire), "fire", Modifier.fillMaxWidth(), primary = true, icon = R.drawable.ic_local_fire_department) { Studio.fire() }
    }
}

@Composable
private fun ColumnScope.FiringPage() {
    Centred {
        Header(rs(R.string.firing_title), null, "firing")
        Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
            com.pico.spatial.ui.design.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_local_fire_department), contentDescription = null, modifier = Modifier.size(112.dp), tint = androidx.compose.ui.graphics.Color(0xFFC0561A))
        }
        Hint(rs(R.string.firing_hint), name = "firing")
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(PicoTheme.colorScheme.fillTertiary)) {
            Box(Modifier.fillMaxWidth(Studio.firingProgress.coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(PicoTheme.colorScheme.interaction))
        }
    }
}

@Composable
private fun ExportLine() {
    val n = Studio.exportNote ?: return
    if (Studio.exportFailed) {
        Line {
            Label(rs(R.string.export_failed), "export/failed", Modifier.weight(1f), color = PicoTheme.colorScheme.error)
            KButton(rs(R.string.act_retry), "export_retry") { Studio.export(zh()) }
        }
    } else Label(rs(R.string.exported, n), "export/done", secondary = true)
}

@Composable
private fun ColumnScope.FiredPage() {
    val z = zh()
    Centred {
        Header(rs(R.string.fired_title), Studio.displayName(z), "fired")
        Hint(rs(R.string.fired_shelf), name = "fired")
        Line {
            KButton(rs(R.string.act_export), "export", Modifier.weight(1f), icon = R.drawable.ic_download) { Studio.export(z) }
            KButton(rs(R.string.act_back_clay), "back_clay", Modifier.weight(1f)) { Studio.backToClay() }
        }
        ExportLine()
        KButton(rs(R.string.act_another), "another", Modifier.fillMaxWidth(), primary = true, icon = R.drawable.ic_add) { Studio.openNewLump(Page.FREE) }
    }
}

// ------------------------------------------------------------------------------------------------- 新捏一团
@Composable
private fun ColumnScope.NewLump() {
    Centred {
        Header(rs(R.string.new_title), rs(R.string.new_saved), "new")
        Line {
            for ((s, label) in listOf(StartShape.BALL to R.string.shape_ball, StartShape.EGG to R.string.shape_egg, StartShape.BLOCK to R.string.shape_block)) {
                Card(Thumbs.get("shape:${s.id}"), rs(label), null, "new/${s.id}", Modifier.weight(1f)) { Studio.newLump(s) }
            }
        }
        KButton(rs(R.string.act_cancel), "new_cancel", Modifier.fillMaxWidth()) { Studio.cancelNewLump() }
    }
}

@Composable
private fun Card(img: ImageBitmap?, title: String, tag: String?, name: String, modifier: Modifier = Modifier, dashed: Boolean = false, onClick: () -> Unit) {
    val c = PicoTheme.colorScheme
    Column(
        modifier.height(216.dp).clip(RoundedCornerShape(18.dp)).background(c.fillTertiary)
            .then(if (dashed) Modifier.border(2.dp, c.labelSecondary, RoundedCornerShape(18.dp)) else Modifier)
            .clickable { CfLog.i("TAP card $name"); onClick() }
            .semantics { contentDescription = title; role = Role.Button }
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
            if (img != null) Image(img, contentDescription = null, modifier = Modifier.size(112.dp))
            else if (dashed) Label("+", "$name/plus", style = PicoTheme.typography.titleLarge, secondary = true)
        }
        Label(title, "$name/title", align = TextAlign.Center, style = PicoTheme.typography.labelLarge)
        if (tag != null) Label(tag, "$name/tag", secondary = true, style = PicoTheme.typography.labelMedium)
    }
}

// ------------------------------------------------------------------------------------------------- 作品
private data class CardItem(val key: String, val title: String, val tag: String, val thumbKey: String, val meta: WorkMeta? = null, val sample: Sample? = null)

@Composable
fun ColumnScope.WorksPage() {
    if (Studio.newLumpFrom == Page.WORKS) { NewLump(); return }
    if (Studio.detail != null) { Detail(); return }
    val z = zh()
    val unfired = rs(R.string.tag_unfired); val fired = rs(R.string.tag_fired); val sampleTag = rs(R.string.tag_sample)
    val items = Studio.works.map { m ->
        CardItem("w:${m.id}", Studio.displayName(z, m.base, Glaze.of(m.glaze), m.fired, m.createdAt), if (m.fired) fired else unfired, "w:${m.id}@${m.updatedAt}", meta = m)
    } + Sample.entries.map { s -> CardItem("s:${s.id}", if (z) s.zh else s.en, sampleTag, "s:${s.id}", sample = s) }
    Centred {
        Header(rs(R.string.works_title), null, "works")
        KButton(rs(R.string.act_new_lump), "new_lump", Modifier.fillMaxWidth(), primary = true, icon = R.drawable.ic_add) { Studio.openNewLump(Page.WORKS) }
        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (Studio.works.isEmpty()) item { Card(null, rs(R.string.works_empty), null, "works/empty", Modifier.width(144.dp), dashed = true) { Studio.openNewLump(Page.WORKS) } }
            items(items, key = { it.key }) { it2 ->
                Card(Thumbs.get(it2.thumbKey), it2.title, it2.tag, "works/${it2.key}", Modifier.width(144.dp)) {
                    if (it2.meta != null) Studio.openWork(it2.meta.id) else it2.sample?.let { s -> Studio.openSample(s) }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.Detail() {
    val key = Studio.detail ?: return
    val z = zh()
    val sample = if (key.startsWith("s:")) Sample.of(key.removePrefix("s:")) else null
    val meta = if (key.startsWith("w:")) Studio.works.firstOrNull { it.id == key.removePrefix("w:") } else null
    val title = sample?.let { if (z) it.zh else it.en } ?: Studio.displayName(z)
    val thumbKey = sample?.let { "s:${it.id}" } ?: meta?.let { "w:${it.id}@${it.updatedAt}" } ?: "s:none"
    Centred {
        Header(title, sample?.let { if (z) it.tagZh else it.tagEn }, "detail")
        // gate:allow button-gap the thumbnail beside a read-only hint, no buttons in this row
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(150.dp).clip(RoundedCornerShape(18.dp)).background(PicoTheme.colorScheme.fillTertiary), contentAlignment = Alignment.Center) {
                Thumbs.get(thumbKey)?.let { Image(it, contentDescription = title, modifier = Modifier.size(150.dp)) }
            }
            Label(rs(R.string.detail_open_hint), "detail/hint", Modifier.weight(1f), secondary = true)
        }
        if (Studio.confirmDelete) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(PicoTheme.colorScheme.fillTertiary).padding(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Label(rs(R.string.delete_confirm, title), "confirm/delete", style = PicoTheme.typography.bodyLarge)
                Line {
                    KButton(rs(R.string.act_cancel), "delete_cancel", Modifier.weight(1f)) { Studio.confirmDelete = false }
                    KButton(rs(R.string.act_delete), "delete_confirm", Modifier.weight(1f), primary = true, icon = R.drawable.ic_delete) { Studio.deleteCurrentDetail(); Thumbs.forget("w:") }
                }
            }
        } else {
            Line {
                KButton(rs(R.string.act_export), "detail_export", Modifier.weight(1f), icon = R.drawable.ic_download) { Studio.export(z) }
                if (meta != null) KButton(rs(R.string.act_delete), "detail_delete", Modifier.weight(1f), icon = R.drawable.ic_delete) { Studio.confirmDelete = true }
            }
            ExportLine()
            KButton(rs(R.string.act_back), "detail_back", Modifier.fillMaxWidth()) { Studio.closeDetail() }
        }
    }
}

// ------------------------------------------------------------------------------------------------- 设置
@Composable
fun ColumnScope.SettingsPage() {
    if (Studio.howTo) { HowToPage(); return }
    val context = LocalContext.current
    val navigator = LocalSpatialNavigator.current
    val p = Studio.prefs
    Centred {
        // D44: [叫回面前] [退出应用] on the first screen, top row (design panels.settings)
        if (!Studio.confirmExit) Line {
            KButton(rs(R.string.recall), "recall", Modifier.weight(1f), icon = R.drawable.ic_my_location) { Studio.recall() }
            KButton(rs(R.string.exit_app), "exit", Modifier.weight(1f), icon = R.drawable.ic_logout) { Studio.confirmExit = true; CfLog.i("TAP exit") }
        } else Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(PicoTheme.colorScheme.fillTertiary).padding(14.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Label(rs(R.string.exit_confirm), "confirm/exit", style = PicoTheme.typography.bodyLarge)
            Line {
                KButton(rs(R.string.act_cancel), "exit_cancel", Modifier.weight(1f)) { Studio.confirmExit = false }
                KButton(rs(R.string.exit_do), "exit_confirm", Modifier.weight(1f), primary = true, icon = R.drawable.ic_logout) { Studio.scope.launch { exitApp(context.applicationContext, navigator) } } // process scope: closing the window must not cancel the exit
            }
        }
        Line {
            Label(rs(R.string.language), "settings/lang", Modifier.width(92.dp))
            val cur = if (zh()) "zh" else "en"
            Seg(listOf("zh" to rs(R.string.lang_zh), "en" to rs(R.string.lang_en)), cur, "lang", Modifier.weight(1f)) { Studio.setLang(it) }
        }
        VolumeRow(p.volume)
        Line {
            KButton(rs(R.string.restart_lessons), "restart_lessons", Modifier.weight(1f), icon = R.drawable.ic_school) { Studio.restartLessons() }
            KButton(rs(R.string.how_to), "how_to", Modifier.weight(1f), icon = R.drawable.ic_help) { Studio.howTo = true; CfLog.i("TAP how-to") }
        }
        if (!Studio.confirmExit) Label(rs(R.string.about_text, BuildConfig.VERSION_NAME) + (if (Cf.scene?.get()?.hands?.supported == false) "\n" + rs(R.string.about_no_hands) else ""), "settings/about", secondary = true, style = PicoTheme.typography.labelMedium)
    }
}

/**
 * Volume (B20 / slider-quantized): three detents 35 / 65 / 100 %; the slider snaps on its own (steps = 1 over the 0…2
 * index), so a tap anywhere and a small drag both land on a detent.
 */
@Composable
private fun VolumeRow(volume: Float) {
    val detents = listOf(0.35f, 0.65f, 1f)
    // a local Float follows the finger (tap or drag); on release it snaps to the nearest detent (B20 slider-quantized)
    val local = androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(volume * 100f) }
    androidx.compose.runtime.LaunchedEffect(volume) { local.floatValue = volume * 100f }
    fun snap(v: Float) = detents.minByOrNull { kotlin.math.abs(it * 100f - v) }!!
    Row(Modifier.fillMaxWidth().height(RowH), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Label(rs(R.string.volume), "slider/volume", Modifier.width(92.dp))
        com.pico.spatial.ui.design.Slider(
            local.floatValue, { v -> local.floatValue = v },
            Modifier.weight(1f).height(48.dp).semantics { contentDescription = "volume" }, 0f..100f,
            { val d = snap(local.floatValue); local.floatValue = d * 100f; Studio.setVolume(d); CfLog.i("VOLUME ${(d * 100).toInt()} (snapped)") },
        )
        Label(rs(R.string.volume_pct, (snap(local.floatValue) * 100).toInt()), "slider/volume/value", Modifier.width(56.dp), align = TextAlign.End)
    }
}

/** 「怎么操作」(B19): one row per main action: what it does, the hand way, the controller way. */
@Composable
private fun ColumnScope.HowToPage() {
    val rows = listOf(
        Triple(R.string.how_to_pick, R.string.how_to_pick_hand, R.string.how_to_pick_ctrl),
        Triple(R.string.how_to_add, R.string.how_to_add_hand, R.string.how_to_add_ctrl),
        Triple(R.string.how_to_carve, R.string.how_to_carve_hand, R.string.how_to_carve_ctrl),
        Triple(R.string.how_to_smooth, R.string.how_to_smooth_hand, R.string.how_to_smooth_ctrl),
        Triple(R.string.how_to_pull, R.string.how_to_pull_hand, R.string.how_to_pull_ctrl),
        Triple(R.string.how_to_turn, R.string.how_to_turn_hand, R.string.how_to_turn_ctrl),
        Triple(R.string.how_to_bench, R.string.how_to_bench_hand, R.string.how_to_bench_ctrl),
    )
    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Header(rs(R.string.how_to), rs(R.string.how_to_sub), "howto")
        for ((t, h, c) in rows) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PicoTheme.colorScheme.fillTertiary).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label(rs(t), "howto/title", style = PicoTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.pico.spatial.ui.design.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_pan_tool), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Label(rs(h), "howto/hand", Modifier.weight(1f), secondary = true)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.pico.spatial.ui.design.Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_gamepad), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Label(rs(c), "howto/ctrl", Modifier.weight(1f), secondary = true)
                }
            }
        }
    }
    KButton(rs(R.string.act_back), "howto_back", Modifier.fillMaxWidth()) { Studio.howTo = false }
}

// ------------------------------------------------------------------------------------------------- 课
@Composable
fun ColumnScope.LearnPage() {
    if (Studio.mode != Mode.LESSON) { Centred { Hint(rs(R.string.hint_loading), name = "learn") }; return }
    when (Studio.lessonStage) {
        LessonStage.PICK, LessonStage.NONE -> LearnPick()
        LessonStage.WATCH, LessonStage.TRY -> LearnIn()
        LessonStage.DONE -> LearnResult()
    }
}

@Composable
private fun ColumnScope.LearnPick() {
    val l = Studio.lesson.lesson
    val z = zh()
    val best = Studio.prefs.lessonBest[l.id]
    val dur = Lessons.all.indexOf(l).let { (l.steps.size * 10) }
    Centred {
        Header((if (z) l.titleZh else l.titleEn), rs(R.string.lesson_pick_line, if (z) l.pieceZh else l.pieceEn, l.steps.size), "pick")
        Line {
            KButton(rs(R.string.lesson_prev), "lesson_prev", Modifier.weight(1f), icon = R.drawable.ic_chevron_left) { Studio.pickLesson(-1) }
            KButton(rs(R.string.lesson_next), "lesson_next", Modifier.weight(1f), icon = R.drawable.ic_chevron_right) { Studio.pickLesson(1) }
        }
        Label(Lessons.all.joinToString("   ") { les -> val b = Studio.prefs.lessonBest[les.id]; (Lessons.all.indexOf(les) + 1).toString() + " " + (if (z) les.pieceZh else les.pieceEn) + (if (b != null) " ${(b * 100).toInt()}% ✓" else "") }, "pick/progress", secondary = true, style = PicoTheme.typography.labelMedium)
        if (best != null) Label(rs(R.string.lesson_best, (best * 100).toInt()), "pick/best", secondary = true)
        KButton(rs(R.string.lesson_start), "lesson_start", Modifier.fillMaxWidth().height(72.dp), primary = true, enabled = Studio.lesson.planReady, icon = R.drawable.ic_play_arrow) { Studio.startLesson() }
        @Suppress("UNUSED_EXPRESSION") dur
    }
}

@Composable
private fun ColumnScope.LearnIn() {
    val ls = Studio.lesson
    val l = ls.lesson
    val z = zh()
    val step = l.steps[ls.si]
    val trying = Studio.lessonStage == LessonStage.TRY
    val sentence = when {
        trying && ls.hint != null -> ls.hint!!
        !trying && ls.watchedAll -> rs(R.string.lesson_watched)
        else -> if (z) step.zh else step.en
    }
    Centred {
        Header(rs(R.string.lesson_in_title, if (z) l.titleZh else l.titleEn, ls.si + 1, ls.steps), if (trying) rs(R.string.lesson_score, (ls.score * 100).toInt()) else rs(R.string.lesson_watch_sub), "learnin")
        Hint(sentence, name = "learnin")
        KButton(rs(R.string.lesson_exit), "lesson_exit", Modifier.fillMaxWidth(), icon = R.drawable.ic_arrow_back) { Studio.exitLesson() }
        if (trying) KButton(rs(R.string.lesson_back_watch), "lesson_back_watch", Modifier.fillMaxWidth().height(72.dp), primary = true, icon = R.drawable.ic_visibility) { Studio.backToWatch() }
        else KButton(rs(R.string.lesson_try), "lesson_try", Modifier.fillMaxWidth().height(72.dp), primary = true, icon = R.drawable.ic_pan_tool) { Studio.tryIt() }
    }
}

@Composable
private fun ColumnScope.LearnResult() {
    val ls = Studio.lesson
    val l = ls.lesson
    val z = zh()
    val all = Studio.prefs.lessonDone.size >= Lessons.all.size
    val weak = l.steps[ls.weakest]
    Centred {
        Header(rs(R.string.lesson_done_title, if (z) l.pieceZh else l.pieceEn), rs(R.string.lesson_done_score, (ls.total * 100).toInt()), "result")
        Hint(if (ls.stepScores.take(ls.steps).all { it >= 0.6f }) rs(R.string.lesson_done_good) else (if (z) l.hintZh else l.hintEn) + "（" + rs(R.string.lesson_step, ls.weakest + 1, ls.steps) + "）", if (all) rs(R.string.lesson_all_four) else null, "result")
        Line {
            KButton(rs(R.string.lesson_again), "lesson_again", Modifier.weight(1f), icon = R.drawable.ic_replay) { Studio.againLesson() }
            KButton(rs(R.string.lesson_next_one), "lesson_next_one", Modifier.weight(1f), icon = R.drawable.ic_skip_next) { Studio.nextLesson() }
        }
        KButton(rs(R.string.lesson_glaze), "lesson_glaze", Modifier.fillMaxWidth(), primary = true, icon = R.drawable.ic_local_fire_department) { Studio.lessonToGlaze() }
        @Suppress("UNUSED_EXPRESSION") weak
    }
}
