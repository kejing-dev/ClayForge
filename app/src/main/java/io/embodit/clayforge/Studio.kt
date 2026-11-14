package io.embodit.clayforge

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.embodit.clayforge.domain.Brush
import io.embodit.clayforge.domain.ClayField
import io.embodit.clayforge.domain.Exporter
import io.embodit.clayforge.domain.Glaze
import io.embodit.clayforge.domain.Lesson
import io.embodit.clayforge.domain.Lessons
import io.embodit.clayforge.domain.MergedMesh
import io.embodit.clayforge.domain.Prefs
import io.embodit.clayforge.domain.Sample
import io.embodit.clayforge.domain.Sculptor
import io.embodit.clayforge.domain.StartShape
import io.embodit.clayforge.domain.Thumbnail
import io.embodit.clayforge.domain.ToolSize
import io.embodit.clayforge.domain.V3
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

/** Where the clay is in the main loop (design states S05–S12). */
enum class Phase { LOADING, SCULPT, GLAZE, FIRING, FIRED }

/** Control window pages (native TabBar, D3): 课 / 自由捏 / 作品 / 设置. */
enum class Page { LEARN, FREE, WORKS, SETTINGS }

/** Which clay is on the turntable: a lesson's or your own piece. */
enum class Mode { FREE, LESSON }

/** Lesson states S23–S26 (design D1). */
enum class LessonStage { NONE, PICK, WATCH, TRY, DONE }

/** Observable lesson state for the window, the 3D caption / transport / score panels. */
class LessonUi {
    var li by mutableIntStateOf(0)
    var si by mutableIntStateOf(0)
    var playing by mutableStateOf(false)
    /** The demo's last step has played: the caption says「看完了，换你试？」. */
    var watchedAll by mutableStateOf(false)
    var score by mutableFloatStateOf(0f)
    var passed by mutableStateOf(false)
    var stepScores by mutableStateOf(FloatArray(4))
    var misses by mutableIntStateOf(0)
    var hint by mutableStateOf<String?>(null)
    var ghostOn by mutableStateOf(false)
    var planReady by mutableStateOf(false)
    val lesson: Lesson get() = Lessons.all[li]
    val brush: Brush get() = lesson.brush
    val steps get() = lesson.steps.size
    /** S26: steps averaged, rounded to 5 %. */
    val total: Float get() { val n = steps; var s = 0f; for (i in 0 until n) s += stepScores[i]; return Math.round(s / n * 20f) / 20f }
    /** Index of the weakest step (its hint is the result's sentence). */
    val weakest: Int get() = (0 until steps).minByOrNull { stepScores[it] } ?: 0
}

/**
 * The app's one controller (design `states[]`, mainLine): every real input (hand, controller, eye-hand, pointer),
 * window button and debug command goes through these functions, so the QA broadcast walks the same paths as the user.
 */
object Studio {
    // ---------------------------------------------------------------------------------- observable state
    var phase by mutableStateOf(Phase.LOADING)
    var page by mutableStateOf(Page.LEARN)
    var mode by mutableStateOf(Mode.LESSON)
    var lessonStage by mutableStateOf(LessonStage.NONE)
    val lesson = LessonUi()
    var brush by mutableStateOf(Brush.ADD)
    var size by mutableStateOf(ToolSize.M)
    var mirror by mutableStateOf(false)
    var glaze by mutableStateOf(Glaze.CELADON)
    var undoCount by mutableIntStateOf(0)
    var tooMuch by mutableStateOf(false)
    var loadError by mutableStateOf(false)
    var triangles by mutableIntStateOf(0)
    var prefs by mutableStateOf(Prefs())
    var exportNote by mutableStateOf<String?>(null)
    var exportFailed by mutableStateOf(false)
    var confirmExit by mutableStateOf(false)
    var confirmDelete by mutableStateOf(false)
    /** Settings sub page「怎么操作」(B19 how-to). */
    var howTo by mutableStateOf(false)
    var detail by mutableStateOf<String?>(null)
    var newLumpFrom by mutableStateOf<Page?>(null)
    var works by mutableStateOf<List<WorkMeta>>(emptyList())
    var worksRev by mutableIntStateOf(0)
    var firingProgress by mutableStateOf(0f)

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
    private var passJob: Job? = null

    val scene: ClayScene? get() = Cf.scene?.get()

    fun init(context: Context) {
        ctx = context.applicationContext
        store = WorkStore(ctx.filesDir)
        prefs = loadPrefs()
        brush = Brush.of(prefs.tool) ?: Brush.ADD
        size = ToolSize.of(prefs.size) ?: ToolSize.M
        glaze = Glaze.of(prefs.lastGlaze) ?: Glaze.CELADON
        lesson.li = prefs.lastLesson.coerceIn(0, Lessons.all.size - 1)
        sound = Sounds(ctx)
        refreshWorks()
    }

    // ---------------------------------------------------------------------------------- prefs
    private fun loadPrefs(): Prefs = runCatching {
        val o = org.json.JSONObject(prefsFile.readText())
        val best = HashMap<String, Float>(); o.optJSONObject("lessonBest")?.let { b -> b.keys().forEach { k -> best[k] = b.optDouble(k, 0.0).toFloat() } }
        val done = HashSet<String>(); o.optJSONArray("lessonDone")?.let { a -> for (i in 0 until a.length()) done += a.optString(i) }
        Prefs(o.optString("lang", "system"), o.optDouble("volume", 0.65).toFloat(), o.optBoolean("muted"), o.optBoolean("onboarded"),
            o.optString("lastWorkId").takeIf { it.isNotEmpty() }, o.optString("lastGlaze", Glaze.CELADON.id), o.optString("tool", "add"), o.optString("size", "m"),
            o.optString("lastTab", "learn"), best, done, o.optInt("lastLesson", 0))
    }.getOrDefault(Prefs())

    fun updatePrefs(f: (Prefs) -> Prefs) {
        prefs = f(prefs)
        runCatching {
            val p = prefs
            val best = org.json.JSONObject(); p.lessonBest.forEach { (k, v) -> best.put(k, v.toDouble()) }
            prefsFile.writeText(org.json.JSONObject().put("lang", p.lang).put("volume", p.volume.toDouble()).put("muted", p.muted).put("onboarded", p.onboarded)
                .put("lastWorkId", p.lastWorkId ?: "").put("lastGlaze", p.lastGlaze).put("tool", p.tool).put("size", p.size)
                .put("lastTab", p.lastTab).put("lessonBest", best).put("lessonDone", org.json.JSONArray(p.lessonDone.toList())).put("lastLesson", p.lastLesson).toString())
        }
    }

    // ---------------------------------------------------------------------------------- start
    /** S01: the first launch goes straight into lesson 1 (L6); later launches reopen the last tab. */
    fun onSceneReadyLoad(s: ClayScene) {
        if (!prefs.onboarded || prefs.lastTab == "learn") {
            mode = Mode.LESSON; page = Page.LEARN; lessonStage = LessonStage.PICK
            if (!prefs.onboarded) lesson.li = 0
            s.field.fromSdf(lesson.lesson.start::sdf)
            setNew(lesson.lesson.start.id)
        } else {
            mode = Mode.FREE; page = Page.FREE
            loadLastWork(s, sync = true)
        }
    }

    private fun loadLastWork(s: ClayScene, sync: Boolean) {
        val last = prefs.lastWorkId
        var opened = false
        val fill: (ClayField) -> Unit = { f ->
            if (last != null) opened = runCatching { val m = store.load(last, f); setCurrent(m); true }.getOrElse { e -> CfLog.w("LOAD $last failed: $e"); loadError = true; updatePrefs { it.copy(lastWorkId = null) }; false }
            if (!opened) { f.fromSdf(StartShape.BALL::sdf); setNew("ball") }
        }
        if (sync) fill(s.field) else s.loadShape(fill)
    }

    /** The clay stands (S01 → S24 first time / S23 / S05 / S12 / S19). */
    fun onSceneBuilt(s: ClayScene) {
        s.sculptor.clearUndo(); undoCount = 0
        phase = if (currentFired && mode == Mode.FREE) Phase.FIRED else Phase.SCULPT
        if (mode == Mode.LESSON) {
            s.withPlan(lesson.lesson.id) { lesson.planReady = true; if (!prefs.onboarded) { startLesson("first launch"); updatePrefs { it.copy(onboarded = true) } } }
            brush = lesson.brush; size = lesson.lesson.steps[0].size
        }
        s.applyTool(animate = false)
        s.applyPhase()
        CfLog.i("STATE ${stateId()} ready work=$currentId base=$currentBase fired=$currentFired mode=$mode")
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
        val sc = scene
        val main = when {
            phase == Phase.LOADING -> "S01"
            phase == Phase.GLAZE -> "S10"
            phase == Phase.FIRING -> "S11"
            phase == Phase.FIRED -> "S12"
            mode == Mode.LESSON && lessonStage == LessonStage.PICK -> "S23"
            mode == Mode.LESSON && lessonStage == LessonStage.WATCH -> "S24"
            mode == Mode.LESSON && lessonStage == LessonStage.TRY -> "S25"
            mode == Mode.LESSON && lessonStage == LessonStage.DONE -> "S26"
            sc?.sculptor?.phase == Sculptor.Phase.SCULPT -> "S06"
            sc?.sculptor?.phase == Sculptor.Phase.MOVE -> "S07"
            else -> "S05"
        }
        val tags = buildList {
            if (mirror && mode == Mode.FREE) add("S09"); if (exportNote != null) add("S13"); if (page == Page.WORKS) add(if (detail != null) "S15" else "S14")
            if (newLumpFrom != null) add("S16"); if (page == Page.SETTINGS) add("S17"); if (Cf.window.everOpened && !Cf.window.open) add("S18")
            if (loadError) add("S19"); if (tooMuch) add("S20"); if (sc?.recalling == true) add("S21"); if (sc?.benchMoving == true) add("S22")
        }
        return (listOf(main) + tags).joinToString("+")
    }

    fun logState(why: String) = CfLog.i("STATE ${stateId()} why=$why")

    // ---------------------------------------------------------------------------------- tools
    /** Sculpting allowed: free play, or「换你试」in a lesson. */
    val canSculpt get() = phase == Phase.SCULPT && (mode == Mode.FREE || lessonStage == LessonStage.TRY)

    /** A tool is shown on the clay / in hand: free play, and the lesson while watching or trying. */
    fun toolShown() = phase == Phase.SCULPT && (mode == Mode.FREE || lessonStage == LessonStage.WATCH || lessonStage == LessonStage.TRY)

    /** Tap on a tray slot (I1 / I2); in a lesson only this step's tool, size locked (L4). */
    fun tapSlot(b: Brush, src: String = "pointer") {
        if (!canSculpt) { CfLog.i("TOOL ignored phase=$phase stage=$lessonStage"); return }
        clearTransient()
        if (mode == Mode.LESSON) {
            if (b != lesson.brush) { CfLog.i("LESSON tool locked tapped=${b.id} step tool=${lesson.brush.id}"); return }
            if (b == brush && scene?.toolOut == true) { CfLog.i("LESSON size locked ${size.id}"); return }
        }
        if (b == brush && scene?.toolOut == true) {
            size = size.next(); sound.woodTap(); scene?.applyTool(animate = false)
            CfLog.i("SIZE ${size.id} src=$src")
        } else {
            brush = b; sound.woodTap(); scene?.applyTool(animate = true)
            CfLog.i("TOOL ${b.id} ${size.id} src=$src")
        }
        if (mode == Mode.FREE) updatePrefs { it.copy(tool = brush.id, size = size.id) }
    }

    /** Thumbstick up / down (I2): one size up / down. */
    fun stepSize(dir: Int, src: String) {
        if (!canSculpt || mode == Mode.LESSON) return
        val e = ToolSize.entries; size = e[(size.ordinal + dir).coerceIn(0, e.size - 1)]
        sound.woodTap(); scene?.applyTool(animate = false)
        CfLog.i("SIZE ${size.id} src=$src")
        updatePrefs { it.copy(size = size.id) }
    }

    /** Short press on the tool head (or a short pinch / trigger on the clay) = poke (I4b). */
    fun pokeHead() {
        if (!canSculpt) return
        if (SystemClock.uptimeMillis() - lastDragEnd < 250) { CfLog.i("TAP ignored (just after a drag)"); return }
        val s = scene ?: return
        clearTransient()
        val step = s.sculptor.poke()
        if (step != null) { sound.stroke(brush, poke = true); afterStroke("poke") } else CfLog.i("POKE no change brush=${brush.id}")
    }

    fun strokeBegin(src: String) { clearTransient(); scene?.sculptor?.pressHead(SystemClock.uptimeMillis()); CfLog.i("SRC $src target=head brush=${brush.id}"); logState("press head") }

    fun strokeBeginAbs(tip: V3, src: String) { clearTransient(); scene?.sculptor?.pressAt(tip, SystemClock.uptimeMillis()); CfLog.i("SRC $src target=clay brush=${brush.id} tip=$tip"); logState("press $src") }

    fun onStrokeMoved() {}

    fun strokeEnd() {
        val s = scene ?: return
        val sculpted = s.sculptor.phase == Sculptor.Phase.SCULPT
        val step = s.sculptor.release()
        if (sculpted) lastDragEnd = SystemClock.uptimeMillis()
        sound.strokeEnd()
        if (step != null) afterStroke("stroke") else logState("release")
    }

    private fun afterStroke(why: String) {
        val s = scene ?: return
        undoCount = s.sculptor.undo.size
        loadError = false
        s.flushMesh()
        if (mode == Mode.FREE) scheduleSave()
        CfLog.i("STROKE done why=$why brush=${brush.id} size=${size.id} mirror=$mirror tip=${s.sculptor.tip} tris=$triangles undo=$undoCount")
        logState(why)
        if (mode == Mode.LESSON && lessonStage == LessonStage.TRY) onUserStroke()
    }

    fun onTurned(@Suppress("UNUSED_PARAMETER") deg: Float) {}

    fun turn90() { scene?.turn90() }

    fun toggleMirror() { if (!canSculpt || mode != Mode.FREE) return; mirror = !mirror; scene?.sculptor?.mirror = mirror; scene?.applyMirror(); logState("mirror") }

    fun undo() {
        val s = scene ?: return
        if (!canSculpt) return
        if (s.sculptor.undoOne()) { undoCount = s.sculptor.undo.size; s.flushMesh(); if (mode == Mode.FREE) scheduleSave(); logState("undo") }
    }

    fun toggleMute() { updatePrefs { it.copy(muted = !it.muted) }; CfLog.i("MUTE ${prefs.muted}") }
    fun setVolume(v: Float) { updatePrefs { it.copy(volume = v.coerceIn(0f, 1f)) } }
    fun setLang(l: String) { updatePrefs { it.copy(lang = l) }; CfLog.i("LANG $l") }
    private fun clearTransient() { if (loadError) loadError = false; exportNote = null }

    // ---------------------------------------------------------------------------------- lessons (D1)
    fun zh(): Boolean = prefs.lang == "zh" || (prefs.lang != "en" && Locale.getDefault().language == "zh")

    /** TabBar「课」(S23): your piece is saved, the lesson's start clay grows. */
    fun openLearn(why: String) {
        val s = scene ?: return
        if (phase == Phase.FIRING) return
        if (mode == Mode.FREE) saveNow()
        s.dropDrag()
        mode = Mode.LESSON; lessonStage = LessonStage.PICK; phase = Phase.SCULPT
        mirror = false; s.sculptor.mirror = false
        lesson.playing = false; lesson.watchedAll = false
        lesson.planReady = false
        s.showGuide(null)
        brush = lesson.brush; size = lesson.lesson.steps[0].size
        s.withPlan(lesson.lesson.id) { lesson.planReady = true; s.loadLessonField(-1) }
        updatePrefs { it.copy(lastTab = "learn", lastLesson = lesson.li) }
        s.applyPhase()
        logState("learn $why")
    }

    /** [◀ 上一节] / [下一节 ▶] on the pick page (design learnPick). */
    fun pickLesson(delta: Int) {
        if (lessonStage != LessonStage.PICK && lessonStage != LessonStage.DONE) return
        lesson.li = (lesson.li + delta + Lessons.all.size) % Lessons.all.size
        lessonStage = LessonStage.PICK
        openLearn("pick")
    }

    /** [▶ 开始这节] (S24): the demo plays from step 1. */
    fun startLesson(why: String = "start") {
        val s = scene ?: return
        mode = Mode.LESSON; page = Page.LEARN
        lessonStage = LessonStage.WATCH
        lesson.si = 0; lesson.watchedAll = false; lesson.stepScores = FloatArray(4); lesson.hint = null; lesson.ghostOn = false
        brush = lesson.brush
        s.withPlan(lesson.lesson.id) { lesson.planReady = true; playStep(0) }
        updatePrefs { it.copy(lastTab = "learn", lastLesson = lesson.li) }
        logState("lesson $why")
    }

    private fun playStep(si: Int) {
        val s = scene ?: return
        lesson.si = si; size = lesson.lesson.steps[si].size
        s.showGuide(null)
        s.loadLessonField(si)
        s.startPlayer(si)
        lesson.playing = true
        s.applyTool(animate = true); s.applyPhase()
        CfLog.i("LESSON watch ${lesson.lesson.id} step ${si + 1}/${lesson.steps}")
        logState("watch")
    }

    /** The demo of a step ended: the last one stops with「看完了，换你试？」. */
    fun onDemoStepDone() {
        if (lesson.si >= lesson.steps - 1) { lesson.watchedAll = true; lesson.playing = false; CfLog.i("LESSON watched all"); logState("watched") }
    }

    /** 2 s after a step: the next one plays (the clay is already that step's start, L1). */
    fun onDemoPauseOver() {
        val s = scene ?: return
        if (lesson.si >= lesson.steps - 1) return
        lesson.si++
        size = lesson.lesson.steps[lesson.si].size
        s.startPlayer(lesson.si)
        s.applyTool(animate = false)
        CfLog.i("LESSON watch ${lesson.lesson.id} step ${lesson.si + 1}/${lesson.steps} (auto)")
        logState("watch next")
    }

    /** 3D transport while watching: 0 上一步 · 1 暂停/播放 · 2 下一步 · 3 重放; while trying: 0 上一步 · 1 看它做 · 2 下一步 · 3 重来. */
    fun transport(k: Int, src: String = "chip") {
        CfLog.i("TAP transport $k stage=$lessonStage src=$src")
        val s = scene ?: return
        when (lessonStage) {
            LessonStage.WATCH -> when (k) {
                0 -> { lesson.watchedAll = false; playStep(maxOf(0, lesson.si - 1)) }
                1 -> { lesson.playing = !lesson.playing; s.onPause(!lesson.playing); CfLog.i("LESSON ${if (lesson.playing) "play" else "pause"}") }
                2 -> if (lesson.si < lesson.steps - 1) { lesson.watchedAll = false; playStep(lesson.si + 1) } else { lesson.watchedAll = true; s.loadLessonEnd(lesson.si); lesson.playing = false }
                3 -> { lesson.watchedAll = false; playStep(lesson.si) }
            }
            LessonStage.TRY -> when (k) {
                0 -> enterTry(maxOf(0, lesson.si - 1))
                1 -> backToWatch()
                2 -> { lesson.stepScores = lesson.stepScores.copyOf().also { it[lesson.si] = lesson.score }; nextTry("skip") }
                3 -> enterTry(lesson.si)
            }
            else -> {}
        }
        logState("transport $k")
    }

    /** [换你试] (S25): this step's start (the first when the demo has finished). */
    fun tryIt() {
        if (lessonStage != LessonStage.WATCH) return
        enterTry(if (lesson.watchedAll) 0 else lesson.si)
    }

    fun backToWatch() { if (lessonStage != LessonStage.TRY) return; lessonStage = LessonStage.WATCH; lesson.watchedAll = false; playStep(lesson.si) }

    private fun enterTry(si: Int) {
        val s = scene ?: return
        passJob?.cancel()
        lessonStage = LessonStage.TRY
        lesson.si = si; lesson.score = 0f; lesson.passed = false; lesson.misses = 0; lesson.hint = null; lesson.playing = false
        s.stopPlayer()
        s.loadLessonField(si)
        brush = lesson.brush; size = lesson.lesson.steps[si].size
        s.sculptor.brush = brush; s.sculptor.size = size; s.sculptor.mirror = false
        s.showGuide(si)
        s.applyTool(animate = true); s.applyPhase()
        CfLog.i("LESSON try ${lesson.lesson.id} step ${si + 1}/${lesson.steps}")
        logState("try")
    }

    /** Score refresh (every 0.2 s, design tryIt.score): ≥ 60 % → 0.8 s later the next step (not mid-stroke). */
    fun onScore(v: Float) {
        if (lessonStage != LessonStage.TRY) return
        lesson.score = v
        val idle = scene?.sculptor?.phase == Sculptor.Phase.IDLE
        if (v >= 0.6f && !lesson.passed && idle) {
            lesson.passed = true
            lesson.stepScores = lesson.stepScores.copyOf().also { it[lesson.si] = v }
            CfLog.i("LESSON pass step ${lesson.si + 1} score=${"%.2f".format(v)}")
            sound.jar()
            passJob = scope.launch { delay(800); if (lessonStage == LessonStage.TRY && lesson.passed) nextTry("pass") }
        }
    }

    /** After each of your strokes: two below 60 % → the worst stretch of dots flashes, the caption says where (whereOff). */
    fun onUserStroke() {
        val s = scene ?: return
        val p = s.plan ?: return
        val v = p.score(lesson.si, s.field)
        lesson.score = v
        if (v < 0.6f) {
            lesson.misses++
            if (lesson.misses >= 2) { lesson.hint = if (zh()) lesson.lesson.hintZh else lesson.lesson.hintEn; s.flashWorst() }
        }
        CfLog.i("LESSON stroke score=${"%.2f".format(v)} misses=${lesson.misses}")
    }

    private fun nextTry(why: String) {
        if (lesson.si < lesson.steps - 1) enterTry(lesson.si + 1) else finishLesson(why)
    }

    /** S26: the result card; the piece turns once. */
    private fun finishLesson(why: String) {
        val s = scene ?: return
        lessonStage = LessonStage.DONE
        s.showGuide(null)
        val id = lesson.lesson.id
        val total = lesson.total
        updatePrefs { it.copy(lessonBest = it.lessonBest + (id to maxOf(total, it.lessonBest[id] ?: 0f)), lessonDone = it.lessonDone + id) }
        s.applyTool(animate = false); s.applyPhase()
        s.turn90()
        CfLog.i("LESSON done $id total=${"%.2f".format(total)} why=$why scores=${lesson.stepScores.take(lesson.steps)}")
        logState("lesson done")
    }

    fun toggleGhost() { lesson.ghostOn = !lesson.ghostOn; scene?.showEndGhost(lesson.ghostOn) }

    fun againLesson() = startLesson("again")

    fun nextLesson() { lesson.li = (lesson.li + 1) % Lessons.all.size; lessonStage = LessonStage.PICK; openLearn("next") }

    fun exitLesson() { lessonStage = LessonStage.PICK; scene?.stopPlayer(); openLearn("exit lesson") }

    /** S26 [上釉进窑]: the piece becomes a work named after the lesson, straight into glazing (S10). */
    fun lessonToGlaze() {
        val s = scene ?: return
        if (lessonStage != LessonStage.DONE) return
        s.showEndGhost(false); lesson.ghostOn = false
        mode = Mode.FREE; lessonStage = LessonStage.NONE; page = Page.FREE
        setNew("lesson:${lesson.lesson.id}", name = if (zh()) lesson.lesson.pieceZh else lesson.lesson.pieceEn)
        s.sculptor.clearUndo(); undoCount = 0
        updatePrefs { it.copy(lastTab = "free") }
        phase = Phase.SCULPT
        enterGlaze()
    }

    // ---------------------------------------------------------------------------------- glaze & kiln
    fun enterGlaze() {
        if (phase != Phase.SCULPT || mode != Mode.FREE) return
        clearTransient()
        scene?.dropDrag(); scene?.unmountController("glaze")
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

    fun backToClay() {
        if (phase != Phase.FIRED) return
        saveNow()
        setNew(currentBase)
        phase = Phase.SCULPT
        scene?.sculptor?.clearUndo(); undoCount = 0
        scene?.applyPhase()
        logState("back to clay")
    }

    // ---------------------------------------------------------------------------------- pages / works
    fun refreshWorks() { works = runCatching { store.list() }.getOrDefault(emptyList()); worksRev++ }

    fun openPage(p: Page, why: String) {
        if (phase == Phase.FIRING) { CfLog.i("PAGE ignored while firing"); return }
        val from = page
        page = p; exportNote = null; confirmExit = false; confirmDelete = false; howTo = false
        if (p != Page.WORKS) detail = null
        if (p == Page.WORKS) refreshWorks()
        if (newLumpFrom != null && p != newLumpFrom) newLumpFrom = null
        CfLog.i("PAGE ${p.name.lowercase()} why=$why")
        when {
            p == Page.LEARN && mode != Mode.LESSON -> openLearn("tab")
            p == Page.FREE && mode == Mode.LESSON -> leaveLesson("tab")
            else -> logState("page from=${from.name.lowercase()}")
        }
    }

    /** Back to your own piece (自由捏): the lesson clay goes, the last work comes back. */
    private fun leaveLesson(why: String) {
        val s = scene ?: return
        s.dropDrag(); s.stopPlayer(); s.showGuide(null); s.showEndGhost(false)
        mode = Mode.FREE; lessonStage = LessonStage.NONE; lesson.playing = false
        brush = Brush.of(prefs.tool) ?: Brush.ADD; size = ToolSize.of(prefs.size) ?: ToolSize.M
        loadLastWork(s, sync = false)
        s.setYaw(0f)
        s.sculptor.clearUndo(); undoCount = 0
        phase = if (currentFired) Phase.FIRED else Phase.SCULPT
        updatePrefs { it.copy(lastTab = "free") }
        s.applyTool(animate = true); s.applyPhase()
        logState("free $why")
    }

    fun openNewLump(from: Page) { if (phase == Phase.FIRING) return; newLumpFrom = from; exportNote = null; logState("new lump") }
    fun cancelNewLump() { newLumpFrom = null; logState("new lump cancel") }

    private fun toFreeMode() {
        if (mode == Mode.LESSON) { scene?.stopPlayer(); scene?.showGuide(null); scene?.showEndGhost(false); mode = Mode.FREE; lessonStage = LessonStage.NONE; updatePrefs { it.copy(lastTab = "free") } }
    }

    fun newLump(shape: StartShape) {
        if (mode == Mode.FREE) saveNow()
        val s = scene ?: return
        toFreeMode()
        newLumpFrom = null; detail = null; page = Page.FREE
        setNew(shape.id)
        phase = Phase.SCULPT
        s.loadShape { f -> f.fromSdf(shape::sdf) }
        s.setYaw(0f)
        s.sculptor.clearUndo(); undoCount = 0
        s.applyPhase()
        loadError = false
        logState("new ${shape.id}")
    }

    fun openWork(id: String) {
        if (mode == Mode.FREE) saveNow()
        val s = scene ?: return
        val m = runCatching { store.meta(id) }.getOrNull() ?: return
        toFreeMode()
        var ok = true
        s.loadShape { f -> runCatching { store.load(id, f) }.onFailure { ok = false; f.fromSdf(StartShape.BALL::sdf) } }
        if (!ok) { loadError = true; setNew("ball") } else setCurrent(m)
        afterOpen("w:$id")
    }

    fun openSample(sm: Sample) {
        if (mode == Mode.FREE) saveNow()
        val s = scene ?: return
        toFreeMode()
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
        if (currentId == id && mode == Mode.FREE) {
            currentId = null
            val s = scene
            if (s != null) { s.loadShape { f -> f.fromSdf(StartShape.BALL::sdf) }; setNew("ball"); phase = Phase.SCULPT; s.sculptor.clearUndo(); undoCount = 0; s.applyPhase() }
        }
        refreshWorks()
        logState("deleted")
    }

    fun displayName(zh: Boolean, base: String = currentBase, g: Glaze? = currentGlaze, fired: Boolean = currentFired, created: Long = currentCreated, name: String = currentName): String {
        Sample.of(base.removePrefix("sample:"))?.takeIf { base.startsWith("sample:") }?.let { return if (zh) it.zh else it.en }
        if (base.startsWith("lesson:")) Lessons.of(base.removePrefix("lesson:"))?.let { return if (zh) it.pieceZh else it.pieceEn }
        val date = SimpleDateFormat(if (zh) "M月d日" else "MMM d", if (zh) Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH).format(Date(created))
        val what = if (fired && g != null) (if (zh) g.zh else g.en) else (if (zh) "陶泥" else "Clay")
        return "$what · $date"
    }

    // ---------------------------------------------------------------------------------- saving
    fun scheduleSave() { saveJob?.cancel(); saveJob = scope.launch { delay(1000); saveNow() } }

    /** Writes the current piece if it is a work (free play only; the lesson clay is not a work until [上釉进窑]). */
    fun saveNow() {
        saveJob?.cancel(); saveJob = null
        val s = scene ?: return
        if (phase == Phase.LOADING || mode != Mode.FREE) return
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

    /** Focus lost / background: a drag in progress ends as if released, save now, silence; firing jumps to the result. */
    fun onFocus(focused: Boolean) {
        if (focused) { sound.resume(); return }
        scene?.dropDrag()
        scene?.finishKilnNow()
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
            CfLog.i("EXPORT ok $where(.stl) tris=${mesh.triangles} glb=${glb.size}B stl=${stl.size}B zh=$zh")
        } catch (e: Exception) {
            exportFailed = true; exportNote = ""
            CfLog.e("EXPORT failed", e)
        }
        logState("export")
    }

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

    /** Settings [从第 1 课重来]: your piece is saved, lesson 1 plays (S24). */
    fun restartLessons() {
        if (mode == Mode.FREE) saveNow()
        lesson.li = 0
        openLearn("restart")
        startLesson("restart")
        page = Page.LEARN
    }

    /** Settings [叫回面前] (S21). */
    fun recall() { CfLog.i("TAP recall"); scene?.recall(); logState("recall") }

    fun field(): ClayField? = scene?.field
}
