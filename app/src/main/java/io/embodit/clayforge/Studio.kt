package io.embodit.clayforge

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.embodit.clayforge.domain.Brush
import io.embodit.clayforge.domain.ClayField
import io.embodit.clayforge.domain.Exporter
import io.embodit.clayforge.domain.Glaze
import io.embodit.clayforge.domain.MergedMesh
import io.embodit.clayforge.domain.Prefs
import io.embodit.clayforge.domain.Sample
import io.embodit.clayforge.domain.StartShape
import io.embodit.clayforge.domain.Thumbnail
import io.embodit.clayforge.domain.ToolSize
import io.embodit.clayforge.domain.WorkMeta
import io.embodit.clayforge.domain.WorkStore
import io.embodit.clayforge.platform.CfLog
import io.embodit.clayforge.scene.ClayScene
import io.embodit.clayforge.scene.Sounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where the volume is in the main loop (design states S05–S12). */
enum class Phase { LOADING, SCULPT, GLAZE, FIRING, FIRED }

/** Panel pages; Settings is the template's fixed last page. */
enum class Page { SCULPT, WORKS, SETTINGS }

/**
 * The app's one controller (design `states[]`): every real input, panel button and debug command goes through these
 * functions, so the QA broadcast walks the same paths as the user (build.md 2026-10-29).
 */
object Studio {
    // ---------------------------------------------------------------------------------- observable state
    var phase by mutableStateOf(Phase.LOADING)
    var page by mutableStateOf(Page.SCULPT)
    /** 0 = no guide; 2 / 3 / 4 = S02 / S03 / S04. */
    var guide by mutableIntStateOf(0)
    var brush by mutableStateOf(Brush.ADD)
    var size by mutableStateOf(ToolSize.M)
    var mirror by mutableStateOf(false)
    var glaze by mutableStateOf(Glaze.CELADON)
    var undoCount by mutableIntStateOf(0)
    var tooMuch by mutableStateOf(false)
    var loadError by mutableStateOf(false)
    var triangles by mutableIntStateOf(0)
    var prefs by mutableStateOf(Prefs())
    /** Export line (S13): text + error flag; cleared on page change / new lump. */
    var exportNote by mutableStateOf<String?>(null)
    var exportFailed by mutableStateOf(false)
    var panelState by mutableStateOf("never")
    var confirmExit by mutableStateOf(false)
    var confirmDelete by mutableStateOf(false)
    /** Works page: the card opened (detail), "w:<id>" / "s:<sampleId>". */
    var detail by mutableStateOf<String?>(null)
    /** New-lump chooser open, and where to go back to on [算了]. */
    var newLumpFrom by mutableStateOf<Page?>(null)
    var works by mutableStateOf<List<WorkMeta>>(emptyList())
    var worksRev by mutableIntStateOf(0)
    var firingProgress by mutableStateOf(0f)
    var reopenRequested by mutableStateOf(false)
    var closeRequested by mutableStateOf(false)

    // ---------------------------------------------------------------------------------- current work
    var currentId: String? = null; private set
    var currentBase = "ball"; private set
    var currentFired by mutableStateOf(false); private set
    var currentGlaze: Glaze? = null; private set
    var currentCreated = 0L; private set
    var currentName by mutableStateOf(""); private set

    lateinit var store: WorkStore
    lateinit var sound: Sounds
    private lateinit var ctx: Context
    private val prefsFile get() = File(ctx.filesDir, "prefs.json")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var saveJob: Job? = null
    private var lastDragEnd = 0L
    var turnedSinceGuide = 0f

    val scene: ClayScene? get() = Cf.scene?.get()

    fun init(context: Context) {
        ctx = context.applicationContext
        store = WorkStore(ctx.filesDir)
        prefs = loadPrefs()
        brush = Brush.of(prefs.tool) ?: Brush.ADD
        size = ToolSize.of(prefs.size) ?: ToolSize.M
        glaze = Glaze.of(prefs.lastGlaze) ?: Glaze.CELADON
        sound = Sounds(ctx)
        refreshWorks()
    }

    // ---------------------------------------------------------------------------------- prefs
    private fun loadPrefs(): Prefs = runCatching {
        val o = org.json.JSONObject(prefsFile.readText())
        Prefs(o.optString("lang", "system"), o.optDouble("volume", 0.65).toFloat(), o.optBoolean("muted"), o.optBoolean("onboarded"),
            o.optString("lastWorkId").takeIf { it.isNotEmpty() }, o.optString("lastGlaze", Glaze.CELADON.id), o.optString("tool", "add"), o.optString("size", "m"))
    }.getOrDefault(Prefs())

    fun updatePrefs(f: (Prefs) -> Prefs) {
        prefs = f(prefs)
        runCatching {
            val p = prefs
            prefsFile.writeText(org.json.JSONObject().put("lang", p.lang).put("volume", p.volume.toDouble()).put("muted", p.muted).put("onboarded", p.onboarded)
                .put("lastWorkId", p.lastWorkId ?: "").put("lastGlaze", p.lastGlaze).put("tool", p.tool).put("size", p.size).toString())
        }
    }

    // ---------------------------------------------------------------------------------- start
    /** S01: the scene asks what to grow — the last work, or a new ball (S19 when the save is broken). */
    fun onSceneReadyLoad(s: ClayScene) {
        val last = prefs.lastWorkId
        var opened = false
        if (last != null) {
            opened = runCatching { val m = store.load(last, s.field); setCurrent(m); true }.getOrElse { e -> CfLog.w("LOAD $last failed: $e"); loadError = true; updatePrefs { it.copy(lastWorkId = null) }; false }
        }
        if (!opened) { s.field.fromSdf(StartShape.BALL::sdf); setNew("ball") }
    }

    /** The clay stands: tools, phase, guide (S01 → S02 / S05 / S12 / S19). */
    fun onSceneBuilt(s: ClayScene) {
        s.sculptor.clearUndo(); undoCount = 0
        phase = if (currentFired) Phase.FIRED else Phase.SCULPT
        if (!prefs.onboarded && !loadError && !currentFired) guide = 2
        s.applyTool(animate = false)
        s.applyPhase()
        CfLog.i("STATE ${stateId()} ready work=$currentId base=$currentBase fired=$currentFired")
    }

    private fun setCurrent(m: WorkMeta) {
        currentId = m.id; currentBase = m.base; currentFired = m.fired; currentGlaze = Glaze.of(m.glaze); currentCreated = m.createdAt; currentName = m.name
        if (m.fired) currentGlaze?.let { glaze = it }
    }

    private fun setNew(base: String, fired: Boolean = false, g: Glaze? = null, name: String = "") {
        currentId = null; currentBase = base; currentFired = fired; currentGlaze = g; currentCreated = System.currentTimeMillis(); currentName = name
    }

    /** design states ids for the log / dump. */
    fun stateId(): String {
        val main = when {
            phase == Phase.LOADING -> "S01"
            guide in 2..4 -> "S0$guide"
            phase == Phase.GLAZE -> "S10"
            phase == Phase.FIRING -> "S11"
            phase == Phase.FIRED -> "S12"
            scene?.sculptor?.phase == io.embodit.clayforge.domain.Sculptor.Phase.SCULPT -> "S06"
            scene?.sculptor?.phase == io.embodit.clayforge.domain.Sculptor.Phase.MOVE -> "S07"
            else -> "S05"
        }
        val tags = buildList {
            if (mirror) add("S09"); if (exportNote != null) add("S13"); if (page == Page.WORKS) add(if (detail != null) "S15" else "S14")
            if (newLumpFrom != null) add("S16"); if (page == Page.SETTINGS) add("S17"); if (panelState == "ON_DESTROY") add("S18")
            if (loadError) add("S19"); if (tooMuch) add("S20")
        }
        return (listOf(main) + tags).joinToString("+")
    }

    fun logState(why: String) = CfLog.i("STATE ${stateId()} why=$why")

    // ---------------------------------------------------------------------------------- tools
    val canSculpt get() = phase == Phase.SCULPT

    /** Tap on a tray slot: another tool = pick it (I1); the active tool's empty slot = next size (I2). */
    fun tapSlot(b: Brush) {
        if (!canSculpt) return
        if (guide == 2) { guide = 3; logState("guide pick") }
        clearTransient()
        if (b == brush && scene?.toolOut == true) {
            size = size.next(); sound.woodTap(); scene?.applyTool(animate = false)
            CfLog.i("SIZE ${size.id}")
        } else {
            brush = b; sound.woodTap(); scene?.applyTool(animate = true)
            CfLog.i("TOOL ${b.id} ${size.id}")
        }
        updatePrefs { it.copy(tool = brush.id, size = size.id) }
    }

    /** Short press on the tool head = poke (I4b). */
    fun pokeHead() {
        if (!canSculpt || guide == 2) return
        if (SystemClock.uptimeMillis() - lastDragEnd < 250) { CfLog.i("TAP ignored (just after a drag)"); return }
        val s = scene ?: return
        clearTransient()
        val step = s.sculptor.poke()
        if (step != null) { sound.stroke(brush, poke = true); afterStroke("poke") } else CfLog.i("POKE no change brush=${brush.id}")
    }

    fun strokeBegin() { clearTransient(); scene?.sculptor?.pressHead(SystemClock.uptimeMillis()); logState("press head") }

    fun strokeEnd() {
        val s = scene ?: return
        val sculpted = s.sculptor.phase == io.embodit.clayforge.domain.Sculptor.Phase.SCULPT
        val step = s.sculptor.release()
        // only a real stroke blocks the tap that follows it (a press that never passed the gate may become a poke)
        if (sculpted) lastDragEnd = SystemClock.uptimeMillis()
        sound.strokeEnd()
        if (step != null) afterStroke("stroke") else logState("release")
    }

    private fun afterStroke(why: String) {
        val s = scene ?: return
        undoCount = s.sculptor.undo.size
        loadError = false
        if (guide == 3) guide = 4
        s.flushMesh()
        scheduleSave()
        CfLog.i("STROKE done why=$why brush=${brush.id} size=${size.id} mirror=$mirror tip=${s.sculptor.tip} tris=$triangles undo=$undoCount")
        logState(why)
    }

    fun onTurned(deg: Float) {
        turnedSinceGuide += kotlin.math.abs(deg)
        if (guide == 4 && turnedSinceGuide >= 30f) finishGuide("turned ${turnedSinceGuide.toInt()}°")
    }

    fun turn90() {
        if (phase == Phase.FIRING) return
        scene?.turn90()
        if (guide == 4) finishGuide("turn90")
    }

    private fun finishGuide(why: String) { guide = 0; updatePrefs { it.copy(onboarded = true) }; logState("guide done $why") }

    fun toggleMirror() { if (!canSculpt) return; mirror = !mirror; scene?.sculptor?.mirror = mirror; scene?.applyMirror(); logState("mirror") }

    fun undo() {
        val s = scene ?: return
        if (!canSculpt) return
        if (s.sculptor.undoOne()) { undoCount = s.sculptor.undo.size; s.flushMesh(); scheduleSave(); logState("undo") }
    }

    fun toggleMute() { updatePrefs { it.copy(muted = !it.muted) }; CfLog.i("MUTE ${prefs.muted}") }

    fun setVolume(v: Float) { updatePrefs { it.copy(volume = v.coerceIn(0f, 1f)) } }

    fun setLang(l: String) { updatePrefs { it.copy(lang = l) }; CfLog.i("LANG $l") }

    private fun clearTransient() { if (loadError) loadError = false; exportNote = null }

    // ---------------------------------------------------------------------------------- glaze & kiln
    fun enterGlaze() {
        if (phase != Phase.SCULPT) return
        guide = 0
        clearTransient()
        phase = Phase.GLAZE
        scene?.applyPhase()
        logState("glaze")
    }

    fun pickGlaze(g: Glaze) {
        if (phase != Phase.GLAZE) return
        glaze = g; sound.jar(); scene?.applyGlazePreview(hop = g)
        updatePrefs { it.copy(lastGlaze = g.id) }
        logState("glaze ${g.id}")
    }

    fun backToSculpt() { if (phase != Phase.GLAZE) return; phase = Phase.SCULPT; scene?.applyPhase(); logState("back to sculpt") }

    fun fire() {
        if (phase != Phase.GLAZE) return
        phase = Phase.FIRING
        scene?.fire { onFired() }
        logState("fire")
    }

    private fun onFired() {
        currentFired = true; currentGlaze = glaze
        if (currentId == null) currentId = store.newId()
        phase = Phase.FIRED
        scene?.applyPhase()
        saveNow()
        logState("fired")
    }

    /** S12 [回泥接着捏]: the fired piece stays on the shelf, a new unfired copy becomes current. */
    fun backToClay() {
        if (phase != Phase.FIRED) return
        saveNow()
        setNew(currentBase)
        phase = Phase.SCULPT
        scene?.sculptor?.clearUndo(); undoCount = 0
        scene?.applyPhase()
        logState("back to clay")
    }

    // ---------------------------------------------------------------------------------- works
    fun refreshWorks() { works = runCatching { store.list() }.getOrDefault(emptyList()); worksRev++ }

    fun openPage(p: Page, why: String) {
        if (phase == Phase.FIRING) { CfLog.i("PAGE ignored while firing"); return }
        page = p; exportNote = null; confirmExit = false; confirmDelete = false
        if (p != Page.WORKS) detail = null
        if (p == Page.WORKS) refreshWorks()
        if (newLumpFrom != null && p != newLumpFrom) newLumpFrom = null
        CfLog.i("PAGE ${p.name.lowercase()} why=$why"); logState("page")
    }

    fun openNewLump(from: Page) { if (phase == Phase.FIRING) return; newLumpFrom = from; exportNote = null; logState("new lump") }

    fun cancelNewLump() { newLumpFrom = null; logState("new lump cancel") }

    /** S16 card: save the current piece, grow a new lump (written as a work after its first stroke). */
    fun newLump(shape: StartShape) {
        saveNow()
        val s = scene ?: return
        newLumpFrom = null; detail = null; page = Page.SCULPT
        setNew(shape.id)
        phase = Phase.SCULPT
        s.loadShape { f -> f.fromSdf(shape::sdf) }
        s.sculptor.clearUndo(); undoCount = 0
        s.applyPhase()
        loadError = false
        logState("new ${shape.id}")
    }

    /** Works card → this piece becomes current (S15). */
    fun openWork(id: String) {
        saveNow()
        val s = scene ?: return
        val m = runCatching { store.meta(id) }.getOrNull() ?: return
        var ok = true
        s.loadShape { f -> runCatching { store.load(id, f) }.onFailure { ok = false; f.fromSdf(StartShape.BALL::sdf) } }
        if (!ok) { loadError = true; setNew("ball") } else setCurrent(m)
        afterOpen("w:$id")
    }

    fun openSample(sm: Sample) {
        saveNow()
        val s = scene ?: return
        s.loadShape { f -> f.fromSdf(sm::sdf) }
        setNew("sample:${sm.id}", fired = sm.glaze != null, g = sm.glaze, name = sm.id)
        sm.glaze?.let { glaze = it }
        afterOpen("s:${sm.id}")
    }

    private fun afterOpen(key: String) {
        val s = scene ?: return
        s.sculptor.clearUndo(); undoCount = 0
        phase = if (currentFired) Phase.FIRED else Phase.SCULPT
        s.applyPhase()
        detail = key
        page = Page.WORKS
        updatePrefs { it.copy(lastWorkId = currentId ?: it.lastWorkId) }
        logState("open $key")
    }

    fun closeDetail() { detail = null; confirmDelete = false; logState("detail back") }

    fun deleteCurrentDetail() {
        val key = detail ?: return
        if (!key.startsWith("w:")) return
        val id = key.removePrefix("w:")
        store.delete(id)
        if (prefs.lastWorkId == id) updatePrefs { it.copy(lastWorkId = null) }
        confirmDelete = false; detail = null
        CfLog.i("DELETE $id")
        if (currentId == id) {
            // the open piece is gone: a fresh ball grows (written after its first stroke)
            currentId = null
            val s = scene
            if (s != null) { s.loadShape { f -> f.fromSdf(StartShape.BALL::sdf) }; setNew("ball"); phase = Phase.SCULPT; s.sculptor.clearUndo(); undoCount = 0; s.applyPhase() }
        }
        refreshWorks()
        logState("deleted")
    }

    /** design `dataModel.work.name`: glaze · date, or the sample's name. */
    fun displayName(zh: Boolean, base: String = currentBase, g: Glaze? = currentGlaze, fired: Boolean = currentFired, created: Long = currentCreated): String {
        Sample.of(base.removePrefix("sample:"))?.takeIf { base.startsWith("sample:") }?.let { return if (zh) it.zh else it.en }
        val date = SimpleDateFormat(if (zh) "M月d日" else "MMM d", if (zh) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH).format(Date(created))
        val what = if (fired && g != null) (if (zh) g.zh else g.en) else (if (zh) "陶泥" else "Clay")
        return if (zh) "$what · $date" else "$what · $date"
    }

    // ---------------------------------------------------------------------------------- saving
    fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch { delay(1000); saveNow() }
    }

    /** Writes the current piece if it is a work (unsaved lumps / samples are written after their first stroke). */
    fun saveNow() {
        saveJob?.cancel(); saveJob = null
        val s = scene ?: return
        if (phase == Phase.LOADING) return
        val untouched = currentId == null && s.sculptor.strokes == 0 && !currentFired
        if (untouched) return
        if (currentId == null) { currentId = store.newId(); currentCreated = System.currentTimeMillis() }
        val id = currentId!!
        val meta = WorkMeta(id, currentBase, currentFired, currentGlaze?.id, currentName, currentCreated, System.currentTimeMillis())
        runCatching { store.save(meta, s.field) }.onFailure { CfLog.e("SAVE failed", it) }
        updatePrefs { it.copy(lastWorkId = id) }
        val snapshot = s.field.copy()
        val argb = if (currentFired) (currentGlaze ?: glaze).argb else Glaze.CLAY_ARGB
        val glossy = currentFired
        scope.launch {
            withContext(Dispatchers.Default) {
                val px = Thumbnail.render(snapshot, argb, glossy)
                val bmp = Bitmap.createBitmap(px, 256, 256, Bitmap.Config.ARGB_8888)
                runCatching { store.thumbFile(id).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            }
            refreshWorks()
        }
        CfLog.i("SAVE $id fired=$currentFired glaze=${currentGlaze?.id}")
    }

    /** Focus lost / background: a drag in progress ends as if released, save now, silence. */
    fun onFocus(focused: Boolean) {
        if (focused) { sound.resume(); return }
        scene?.dropDrag()
        saveNow()
        sound.stopAll()
    }

    // ---------------------------------------------------------------------------------- export (S13)
    fun export(zh: Boolean) {
        val s = scene ?: return
        val mesh = MergedMesh.of(s.chunkMeshes())
        val stamp = SimpleDateFormat("MMdd-HHmm", Locale.US).format(Date())
        val base = (if (currentFired) (currentGlaze ?: glaze).id else Sample.of(currentBase.removePrefix("sample:"))?.id ?: "clay") + "-$stamp"
        val g = if (currentFired) (currentGlaze ?: glaze) else null
        try {
            val glb = Exporter.glb(mesh, g?.argb ?: Glaze.CLAY_ARGB, if (g != null) 0.25f else 0.85f, base)
            val stl = Exporter.stl(mesh, base)
            val where = writeExport("$base.glb", "model/gltf-binary", glb)
            writeExport("$base.stl", "model/stl", stl)
            exportNote = where.removeSuffix(".glb"); exportFailed = false
            sound.woodTap()
            CfLog.i("EXPORT ok $where(.stl) tris=${mesh.triangles} glb=${glb.size}B stl=${stl.size}B")
        } catch (e: Exception) {
            exportFailed = true; exportNote = ""
            CfLog.e("EXPORT failed", e)
        }
        logState("export")
    }

    /** N1: MediaStore Download/ClayForge/ first; when that is refused, the app's external files folder. */
    private fun writeExport(name: String, mime: String, bytes: ByteArray): String {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ClayForge")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore insert null")
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            "Download/ClayForge/$name"
        } catch (e: Exception) {
            CfLog.w("EXPORT MediaStore refused ($e), falling back to app files")
            val dir = File(ctx.getExternalFilesDir(null), "export").apply { mkdirs() }
            File(dir, name).writeBytes(bytes)
            "Android/data/${ctx.packageName}/files/export/$name"
        }
    }

    // ---------------------------------------------------------------------------------- guide again (S17)
    fun replayGuide() {
        saveNow()
        guide = 2; turnedSinceGuide = 0f
        newLump(StartShape.BALL)
        scene?.applyTool(animate = false)
        logState("guide again")
    }

    fun field(): ClayField? = scene?.field
}
