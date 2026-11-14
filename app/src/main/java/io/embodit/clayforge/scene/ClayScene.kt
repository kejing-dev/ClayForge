package io.embodit.clayforge.scene

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.EnvironmentLightingSettingsComponent
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.PointLightComponent
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import io.embodit.clayforge.LessonStage
import io.embodit.clayforge.Mode
import io.embodit.clayforge.Phase
import io.embodit.clayforge.Studio
import io.embodit.clayforge.domain.Brush
import io.embodit.clayforge.domain.ChunkMesh
import io.embodit.clayforge.domain.ClayField
import io.embodit.clayforge.domain.Glaze
import io.embodit.clayforge.domain.KilnMesh
import io.embodit.clayforge.domain.Layout
import io.embodit.clayforge.domain.LessonPlan
import io.embodit.clayforge.domain.Lessons
import io.embodit.clayforge.domain.OneEuro
import io.embodit.clayforge.domain.PMesh
import io.embodit.clayforge.domain.Proc
import io.embodit.clayforge.domain.RecallSampler
import io.embodit.clayforge.domain.Sculptor
import io.embodit.clayforge.domain.StepPlayer
import io.embodit.clayforge.domain.ToolSize
import io.embodit.clayforge.domain.V3
import io.embodit.clayforge.domain.clampf
import io.embodit.clayforge.platform.CfLog
import io.embodit.clayforge.platform.Controllers
import io.embodit.clayforge.platform.CtrlPose
import io.embodit.clayforge.platform.Hands
import io.embodit.clayforge.platform.HeadPose
import io.embodit.clayforge.platform.HeadTracker
import io.embodit.clayforge.platform.Pinch
import io.embodit.clayforge.platform.Q4
import io.embodit.clayforge.platform.WindowCtl
import io.embodit.clayforge.scene.input.InputSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** What a pointer hit resolves to (entity names, replayed in DomainTest.hitReplay). */
sealed interface Hit {
    data class Slot(val brush: Brush) : Hit
    data class Jar(val glaze: Glaze) : Hit
    data object Head : Hit
    data object Knob : Hit
    data object Clay : Hit
    data object Ring : Hit
    data object Bench : Hit

    companion object {
        fun of(name: String): Hit? = when {
            name.startsWith("slot:") -> Brush.of(name.removePrefix("slot:"))?.let { Slot(it) }
            name.startsWith("jar:") -> Glaze.of(name.removePrefix("jar:"))?.let { Jar(it) }
            name == "head" -> Head
            name == "knob" -> Knob
            name == "clayProxy" -> Clay
            name.startsWith("ring") -> Ring
            name.startsWith("benchEdge") -> Bench
            else -> null
        }
        fun resolvePath(chain: List<String>): Hit? = chain.firstNotNullOfOrNull { of(it) }
    }
}

/** Which channel a stroke came from (design container.inputs.arbitration; log `SRC hand|ctrl|gaze|handray`). */
enum class Src(val label: String) { HAND("hand"), CTRL("ctrl"), GAZE("gaze"), HANDRAY("handray"), POINTER("pointer"), DEBUG("debug") }

class ClayFrameSystem : System() {
    override fun update(context: SceneUpdateContext) { ActiveScene.scene?.onFrame() }
}

internal object ActiveScene { @Volatile var scene: ClayScene? = null }

/** Ids of the Stage attachment panels. */
object PanelIds {
    const val TURN = "chipTurn"; const val MIRROR = "chipMirror"; const val CAPTION = "caption"; const val SCORE = "score"; const val HUD = "hud"
    val TRANSPORT = listOf("t0", "t1", "t2", "t3")
    val all = listOf(TURN, MIRROR, CAPTION, SCORE, HUD) + TRANSPORT
    /** Design size of each panel in dp (its real size = dp × the measured dp→m × the holder scale). */
    fun sizeDp(id: String): Pair<Int, Int> = when (id) { CAPTION -> 600 to 210; SCORE -> 280 to 110; HUD -> 520 to 120; else -> 200 to 120 }
    fun sizeM(id: String): Pair<Float, Float> = when (id) { CAPTION -> Layout.CAPTION_W to Layout.CAPTION_H; SCORE -> Layout.SCORE_W to Layout.SCORE_H; HUD -> 0.36f to 0.083f; else -> Layout.CHIP_W to Layout.CHIP_H }
}

/**
 * The Mixed Stage's 3D content (design `spatialLayout`, `entities3d`, r4/r5). Stage root → bench (W, placed with
 * [Layout.placeUpright]) → turntable carriage → turntable + clay. Everything per frame runs in [onFrame] (one ECS
 * system on the main thread): trackers, the three input channels, mesh updates, animations, the lesson player.
 * Compose only sends discrete events through [Studio].
 */
class ClayScene(private val context: Context, val window: WindowCtl) {
    val head = HeadTracker()
    val controllers = Controllers()
    val hands = Hands()
    private var stage: Entity? = null
    val bench = Entity().named("bench")
    private val carriage = Entity().named("carriage")
    private val turn = Entity().named("turn")
    private val clayNode = Entity().named("clay")
    private val ghostNode = Entity().named("ghost")
    private val guideNode = Entity().named("guide")
    private val owned = Owned()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val physics = PhysicsMaterialResource()
    val field = ClayField()
    val sculptor = Sculptor(field)
    private lateinit var chunks: ClayChunks
    private lateinit var ghost: ClayChunks
    var frames = 0; private set
    var ready = false; private set
    private var startedMs = SystemClock.uptimeMillis()
    var benchPose: Layout.BenchPose? = null; private set
    private var lastHead: HeadPose? = null

    // materials
    private lateinit var clayMat: Material
    private val previewMats = HashMap<Glaze, Material>()
    private val firedMats = HashMap<Glaze, Material>()
    private lateinit var ringMat: UnlitMaterial
    private lateinit var amberMat: Material
    private lateinit var dotMat: UnlitMaterial
    private lateinit var yellowMat: UnlitMaterial
    private lateinit var ghostMat: UnlitMaterial

    // props
    private val slots = HashMap<Brush, Entity>()
    private val toolModels = HashMap<Brush, Entity>()
    private val sizeDots = ArrayList<Entity>()
    private val jars = HashMap<Glaze, Entity>()
    private val hand = Entity().named("toolHold")
    private val head3d = Entity().named("head")
    private val knob = Entity().named("knob")
    private val tipRing = Entity().named("tipRing")
    private val tipRing2 = Entity().named("tipRing2")
    private val mirrorPlane = Entity().named("mirror")
    private val bounds = Entity().named("bounds")
    private val clayProxy = Entity().named("clayProxy")
    private val ringHolder = Entity().named("ring")
    private val snapFrame = Entity().named("snapFrame")
    private var snapDashed: Entity? = null; private var snapSolid: Entity? = null
    private var shadow: Entity? = null
    private var shadowBench: Entity? = null
    private lateinit var tray: Entity
    private val flash = Entity().named("flashDot")

    // kiln
    private val kiln = Entity().named("kiln")
    private val doorPivot = Entity().named("kilnDoor")
    private var peepMat: UnlitMaterial? = null
    private var gapMat: UnlitMaterial? = null
    private var lampMat: UnlitMaterial? = null
    private var elemMat: PhysicallyBasedMaterial? = null
    private var digitsMat: UnlitMaterial? = null
    private var digitsTex: TextureResource? = null
    private var digitsShown = ""
    private var kilnLight: Entity? = null
    private var gapEntity: Entity? = null
    private var peepEntity: Entity? = null

    // attachment holders (bench chips / lesson panels in W; HUD in Stage space)
    private val holders = HashMap<String, Entity>()
    private val bound = HashSet<String>()
    /** Measured metres per dp (N5); the holders scale panels to their design size. */
    var dpM = 0.001f
    val panelShown = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    var hudText = androidx.compose.runtime.mutableStateOf<String?>(null)

    // tool state
    var toolOut = false; private set
    private var heldBrush: Brush? = null
    private var fly: Fly? = null
    private class Fly(val from: V3, val start: Long, val ms: Long)
    /** Controller the tool rides on (0 left, 1 right), and the hand holding it; null = the tool sits on the clay. */
    var ctrlHand by androidx.compose.runtime.mutableStateOf<Int?>(null)
    private var pinchHand: Int? = null

    // turntable
    var yaw = 0f; private set
    private var shownYaw = Float.NaN
    private var turnAnim: Triple<Float, Float, Long>? = null
    private var spinUntil = 0L; private var spinStart = 0L; private var spinFrom = 0f
    private var boundsUntil = 0L
    private var stickHoldSince = 0L; private var stickArmed = true; private var stickYArmed = true

    // system pointer drag
    private enum class DragKind { NONE, HEAD, KNOB, TURN, BENCH }
    private var drag = DragKind.NONE
    private var dragSrc = Src.POINTER
    private var dragTotal = V3.ZERO
    private var dragStartMs = 0L
    private val euroX = OneEuro(1.0f, 20f); private val euroY = OneEuro(1.0f, 20f); private val euroZ = OneEuro(1.0f, 20f)
    private var filtered = V3.ZERO
    private var gain = 1f

    // absolute strokes (hand / controller)
    private var absSrc: Src? = null
    private val pinchState = arrayOfNulls<String>(2)
    private val pinchAz = FloatArray(2)
    private val pinchStart = arrayOfNulls<V3>(2)
    private val tipEuro = arrayOf(OneEuro(1.2f, 15f), OneEuro(1.2f, 15f), OneEuro(1.2f, 15f))

    // bench moving
    private var benchDragStart: Layout.BenchPose? = null
    private var benchAnim: Triple<Layout.BenchPose, Layout.BenchPose, Long>? = null
    private var benchAnimMs = 300L
    private val snap = Layout.BenchSnap()
    private var home: Layout.BenchPose? = null
    var benchMoving by androidx.compose.runtime.mutableStateOf(false); private set

    // recall (S21)
    private val recallSampler = RecallSampler()
    var recalling by androidx.compose.runtime.mutableStateOf(false); private set

    // glaze soak & kiln
    private var soak: Triple<List<Int>, Long, Material>? = null
    private var kilnStart = 0L
    private var kilnDone: (() -> Unit)? = null
    private var kilnSwapped = false
    private var kilnSounds = 0
    var lastCarriage = V3.ZERO; private set
    var lastDoorDeg = 0f; private set
    private var jarHop: Pair<Glaze, Long>? = null

    // lessons
    private val plans = HashMap<String, LessonPlan>()
    var plan: LessonPlan? = null; private set
    private var player: StepPlayer? = null
    private var playT = 0f
    private var stepDoneAt = 0L
    private var lastFrameNs = 0L
    private var scoreAt = 0L
    private var passAt = 0L
    private var guideDots: List<V3> = emptyList()
    private var flashUntil = 0L
    private var pausedYaw = Float.NaN

    // ------------------------------------------------------------------------------------------------ build
    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        val root = Entity().named("stageRoot")
        content.addEntity(root)
        // an entity added to a full-size SpatialView is not at the Stage origin: move the root so its local space = Stage
        val offset = root.convertPositionFrom(Vector3.ZERO, null)
        val rot = root.convertRotationFrom(Quat.identity(), null)
        root.components[TransformComponent::class.java]?.apply { setPosition(offset); setQuaternion(rot) }
        stage = root
        CfLog.i("STAGE root offset=(${offset.x}, ${offset.y}, ${offset.z})")
        root.addChild(bench)
        bench.place(p = V3(0f, -20f, 0f)) // hidden until the head pose is known
        ActiveScene.scene = this
        head.start(); controllers.start(); hands.start()
        for (id in PanelIds.all) {
            val h = Entity().named("holder:$id")
            if (id == PanelIds.HUD) root.addChild(h) else bench.addChild(h)
            h.enabled = false
            holders[id] = h
        }
        bind(attachments)
        runCatching { build() }.onFailure { CfLog.e("SCENE build failed", it) }
    }

    fun bind(attachments: SpatialViewAttachments) {
        for (id in PanelIds.all) {
            if (id in bound) continue
            val h = holders[id] ?: continue
            val e = attachments.entity(id) ?: continue
            h.addChild(e); bound += id
        }
    }

    fun release() { runCatching { head.stop() }; runCatching { controllers.stop() }; runCatching { hands.stop() } }

    fun close() {
        release()
        scope.cancel()
        if (ActiveScene.scene === this) ActiveScene.scene = null
        holders.values.forEach { runCatching { it.removeAllChildren() } }
        runCatching { stage?.destroy(true) }
        owned.close()
    }

    private fun build() {
        bench.addChild(carriage); carriage.addChild(turn); turn.addChild(clayNode); turn.addChild(ghostNode); turn.addChild(guideNode)
        light()
        val wood = owned.keep(TextureResource.create(woodBitmap()))
        val woodMat = owned.keep(PhysicallyBasedMaterial.create(com.pico.spatial.core.ecs.resource.BlendingMode.OPAQUE).apply {
            setBaseColor(Color4(1f, 1f, 1f, 1f)); setBaseColorTexture(wood); setRoughness(0.6f); setMetallic(0f)
            setCullingMode(com.pico.spatial.core.ecs.resource.MaterialCullingMode.NONE)
        })
        val ironMat = Mats.pbr(owned, 0xFF2E2B2C.toInt(), 0.5f, 0.7f, cull = false)
        clayMat = Mats.pbr(owned, Glaze.CLAY_ARGB, 0.85f)
        for (g in Glaze.entries) { previewMats[g] = Mats.pbr(owned, g.argb, 0.45f); firedMats[g] = Mats.pbr(owned, g.argb, 0.25f) }
        ringMat = Mats.unlit(owned, 0xFFFFFFFF.toInt(), 0.7f, depthTest = false)
        amberMat = Mats.unlit(owned, 0xFFE0A030.toInt())
        dotMat = Mats.unlit(owned, 0xFFFFFFFF.toInt(), 0.55f, depthTest = false)
        yellowMat = Mats.unlit(owned, 0xFFFFC83D.toInt(), 0.9f, depthTest = false)
        ghostMat = Mats.unlit(owned, 0xFFFFFFFF.toInt(), 0.16f)

        // the bench: a walnut board (virtual, D24 no plane detection), its grab band (4 invisible edge colliders)
        val benchMesh = Proc.roundedBox(V3((Layout.BENCH_X0 + Layout.BENCH_X1) / 2, -Layout.BENCH_THICK / 2, (Layout.BENCH_Z0 + Layout.BENCH_Z1) / 2),
            V3((Layout.BENCH_X1 - Layout.BENCH_X0) / 2, Layout.BENCH_THICK / 2, (Layout.BENCH_Z1 - Layout.BENCH_Z0) / 2), 0.008f, 4, 1f / 0.12f)
        modelEntity(mesh(benchMesh, "benchBoard"), woodMat, "benchBoard", bench)
        val e = Layout.BENCH_EDGE
        val bx0 = Layout.BENCH_X0; val bx1 = Layout.BENCH_X1; val bz0 = Layout.BENCH_Z0; val bz1 = Layout.BENCH_Z1
        // gate:allow geometry invisible grab band on the bench rim (design bench: 6 cm outer band = move the bench), never rendered
        val edges = listOf(
            V3((bx0 + bx1) / 2, 0f, bz1 - e / 2) to V3(bx1 - bx0, 0.05f, e), V3((bx0 + bx1) / 2, 0f, bz0 + e / 2) to V3(bx1 - bx0, 0.05f, e),
            V3(bx0 + e / 2, 0f, (bz0 + bz1) / 2) to V3(e, 0.05f, bz1 - bz0), V3(bx1 - e / 2, 0f, (bz0 + bz1) / 2) to V3(e, 0.05f, bz1 - bz0),
        )
        for ((i, pair) in edges.withIndex()) {
            val h = Entity().named("benchEdge:$i"); h.place(p = pair.first); bench.addChild(h)
            h.components.set(CollisionComponent(listOf(ShapeResource.createBox(pair.second.sdk())), physics)); h.components.set(InteractableComponent())
        }
        shadowBench = modelEntity(mesh(Proc.disc(0.12f, 0.0005f, 64), "shadowBench"), Mats.unlit(owned, 0xFF2A1D16.toInt()), "shadowBench", carriage)

        // turntable (design: procedural true-round, 96 segments)
        modelEntity(mesh(Proc.cylinder(Layout.TT_R, Layout.TT_TOP_Y - Layout.TT_THICK, Layout.TT_THICK, 96), "ttTop"), woodMat, "ttTop", turn)
        val neck = Proc.lathe(listOf(0.10f to 0f, 0.10f to 0.008f, 0.09f to 0.014f, 0.04f to 0.016f, 0.03f to 0.045f), 64)
        Proc.disc(0.10f, 0f, 64, down = true, m = neck)
        modelEntity(mesh(neck, "ttBase"), ironMat, "ttBase", carriage)
        shadow = modelEntity(mesh(Proc.disc(0.1f, 0f, 64), "shadow"), Mats.unlit(owned, 0xFF1E1410.toInt()), "shadow", turn).also { it.place(p = V3(0f, Layout.TT_TOP_Y + 0.0005f, 0f)) }

        // tray + side rails
        tray = Entity().named("tray").place(p = Layout.TRAY, q = axisAngle(1f, 0f, 0f, Layout.TRAY_TILT_DEG))
        bench.addChild(tray)
        modelEntity(mesh(Proc.tray(0.48f, 0.012f, 0.08f), "tray"), woodMat, "trayBody", tray)

        // clay + the lesson ghost (target of the step, 16 % white, no depth write)
        chunks = ClayChunks(clayNode, owned); chunks.material = clayMat
        ghost = ClayChunks(ghostNode, owned); ghost.material = ghostMat
        ghostNode.enabled = false
        sculptor.triangles = { chunks.triangles }

        // turn ring: invisible tap/drag band r 0.15–0.21, y 0.03–0.08 (12 boxes round the axis), moves with the carriage
        // gate:allow geometry invisible collider band for turning (design entities3d.turnRing), never rendered
        val ringShapes = (0 until 12).map { i ->
            val a = 2 * PI.toFloat() * i / 12
            ShapeResource.createBox(Vector3(0.08f, 0.06f, 0.06f)).offsetByTranslationAndRotation(axisAngle(0f, 1f, 0f, -Math.toDegrees(a.toDouble()).toFloat() + 90f), Vector3(0.18f * cos(a), 0.055f, 0.18f * sin(a)))
        }
        ringHolder.components.set(CollisionComponent(ringShapes, physics)); ringHolder.components.set(InteractableComponent())
        carriage.addChild(ringHolder)
        turn.addChild(clayProxy)

        // tip rings, mirror plane, bounds frame, snap frame, flash dot
        val ringMesh = mesh(Proc.ring(0.85f, 1.0f, 64), "tipRing")
        modelEntity(ringMesh, ringMat, "tipRingModel", tipRing); bench.addChild(tipRing)
        modelEntity(ringMesh, ringMat, "tipRing2Model", tipRing2); bench.addChild(tipRing2)
        buildMirror(); buildBounds(); buildSnapFrame()
        modelEntity(mesh(Proc.sphere(0.006f, 16, 8), "flash"), yellowMat, "flashModel", flash); turn.addChild(flash); flash.enabled = false

        // the tool on the clay: head box and the knob ball (eye-hand / pointer targets)
        bench.addChild(hand); bench.addChild(head3d); bench.addChild(knob)
        knob.components.set(CollisionComponent(listOf(ShapeResource.createSphere(Layout.KNOB_D / 2)), physics)); knob.components.set(InteractableComponent()); knob.components.set(HoverEffectComponent())
        // gate:allow geometry the move-knob marker: a 16 mm wooden bead at the handle end (design activeTool knob), not a prop
        modelEntity(mesh(Proc.sphere(0.008f), "knobBead"), woodMat, "knobBead", knob)
        setHeadCollider(Layout.HEAD_LEN)

        buildKiln()

        scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            loadProps()
            CfLog.i("SCENE props ms=${SystemClock.elapsedRealtime() - t0}")
            Studio.onSceneReadyLoad(this@ClayScene)
            while (field.dirty.any { it }) { chunks.update(field, null, 4); delay(16) }
            Studio.triangles = chunks.triangles
            sculptor.placeTip(0f, 0.2f)
            updateProxy()
            ready = true
            CfLog.i("SCENE_READY tris=${chunks.triangles} chunks=${chunks.chunksShown} ms=${SystemClock.elapsedRealtime() - t0} worstUpload=${"%.0f".format(chunks.worstUploadMs)}")
            Studio.onSceneBuilt(this@ClayScene)
        }
    }

    private fun light() {
        runCatching { stage?.components?.set(EnvironmentLightingSettingsComponent(1.0f)) }.onFailure { CfLog.w("ENV LIGHT unavailable: $it") }
        val l = Entity().named("keyLight")
        // one warm key light from the upper-left front (design theme.lighting), moderate (lux scale, build.md)
        l.components.set(DirectionalLightComponent(Color4(1f, 0.93f, 0.85f, 1f), 1800f, false))
        l.place(q = axisAngle(0f, 1f, 0f, -30f) * axisAngle(1f, 0f, 0f, -40f))
        bench.addChild(l)
    }

    private fun woodBitmap(): Bitmap {
        val n = 256
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val rnd = java.util.Random(7)
        val phase = FloatArray(40) { rnd.nextFloat() * 6.28f }
        for (y in 0 until n) for (x in 0 until n) {
            var g = 0f
            for (k in 0 until 6) g += sin((y * (0.11f + k * 0.07f)) + phase[k] + 2.2f * sin(x * 0.02f + phase[k + 6])) * (0.5f / (k + 1))
            val t = 0.5f + 0.25f * g
            val r = (138 + 30 * t).toInt().coerceIn(0, 255); val gg = (99 + 22 * t).toInt().coerceIn(0, 255); val b = (69 + 14 * t).toInt().coerceIn(0, 255)
            bmp.setPixel(x, y, (0xFF shl 24) or (r shl 16) or (gg shl 8) or b)
        }
        return bmp
    }

    /** Fire bricks 0.115 × 0.064 in running bond, mortar lines (design assets.kiln materials). */
    private fun brickBitmap(): Bitmap {
        val w = 256; val h = 256
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val rnd = java.util.Random(3)
        val rows = 4; val cols = 2
        val bh = h / rows; val bw = w / cols
        val tint = Array(rows * (cols + 1)) { 0.92f + rnd.nextFloat() * 0.12f }
        for (y in 0 until h) for (x in 0 until w) {
            val row = y / bh; val off = if (row % 2 == 0) 0 else bw / 2
            val xx = (x + off) % w; val col = xx / bw
            val mortar = (y % bh) < 4 || (xx % bw) < 4
            val k = if (mortar) 1f else tint[row * (cols + 1) + col] * (0.96f + rnd.nextFloat() * 0.06f)
            val (r, g, b) = if (mortar) Triple(0xA9, 0x96, 0x77) else Triple(0xD8, 0xC7, 0xA6)
            bmp.setPixel(x, y, (0xFF shl 24) or ((r * k).toInt().coerceIn(0, 255) shl 16) or ((g * k).toInt().coerceIn(0, 255) shl 8) or (b * k).toInt().coerceIn(0, 255))
        }
        return bmp
    }

    private fun mesh(m: PMesh, name: String) = owned.keep(Meshes.create(m.pos.toFloatArray(), m.nrm.toFloatArray(), m.idx.toIntArray(), name, m.uvs()))

    private fun buildMirror() {
        // design: 10 % untextured plane + 3 mm round rods (no dashed texture: textured transparent unlit stalls 6.1 emulator)
        turn.addChild(mirrorPlane)
        val face = PMesh()
        val y0 = Layout.CLAY_Y0; val y1 = Layout.CLAY_Y1; val r = Layout.CLAY_R
        val a = face.v(0f, y0, -r, 1f, 0f, 0f, 0f, 0f); val b = face.v(0f, y0, r, 1f, 0f, 0f, 1f, 0f)
        val c = face.v(0f, y1, r, 1f, 0f, 0f, 1f, 1f); val d = face.v(0f, y1, -r, 1f, 0f, 0f, 0f, 1f)
        face.tri(a, b, c); face.tri(a, c, d)
        modelEntity(mesh(face, "mirrorFace"), Mats.unlit(owned, 0xFFFFFFFF.toInt(), 0.10f), "mirrorFace", mirrorPlane)
        val rods = PMesh()
        Proc.rod(V3(0f, y0, -r), V3(0f, y0, r), 0.0015f, m = rods); Proc.rod(V3(0f, y1, -r), V3(0f, y1, r), 0.0015f, m = rods)
        Proc.rod(V3(0f, y0, -r), V3(0f, y1, -r), 0.0015f, m = rods); Proc.rod(V3(0f, y0, r), V3(0f, y1, r), 0.0015f, m = rods)
        modelEntity(mesh(rods, "mirrorRods"), Mats.unlit(owned, 0xFFFFFFFF.toInt(), 0.5f), "mirrorRods", mirrorPlane)
        mirrorPlane.enabled = false
    }

    private fun buildBounds() {
        val m = PMesh()
        Proc.torus(Layout.CLAY_R, 0.0015f, 96, 8, Layout.CLAY_Y0, m); Proc.torus(Layout.CLAY_R, 0.0015f, 96, 8, Layout.CLAY_Y1, m)
        // gate:allow ring-from-boxes four vertical edge rods of the cylinder frame (round 12-sided tubes, the rings are true tori), not a ring of boxes
        for (i in 0 until 4) { val a = PI.toFloat() / 2 * i + PI.toFloat() / 4; Proc.rod(V3(Layout.CLAY_R * cos(a), Layout.CLAY_Y0, Layout.CLAY_R * sin(a)), V3(Layout.CLAY_R * cos(a), Layout.CLAY_Y1, Layout.CLAY_R * sin(a)), 0.0015f, m = m) }
        modelEntity(mesh(m, "bounds"), amberMat, "boundsModel", bounds)
        carriage.addChild(bounds); bounds.enabled = false
    }

    /** The default-position frame shown while moving the bench: dashed, solid when it snaps (design bench / S22). */
    private fun buildSnapFrame() {
        val x0 = Layout.BENCH_X0; val x1 = Layout.BENCH_X1; val z0 = Layout.BENCH_Z0; val z1 = Layout.BENCH_Z1; val y = 0.002f
        val corners = listOf(V3(x0, y, z0), V3(x1, y, z0), V3(x1, y, z1), V3(x0, y, z1))
        val solid = PMesh(); val dashed = PMesh()
        for (i in 0 until 4) {
            val a = corners[i]; val b = corners[(i + 1) % 4]
            Proc.rod(a, b, 0.003f, m = solid)
            val n = (a.dist(b) / 0.05f).toInt()
            for (k in 0 until n step 2) Proc.rod(a.lerp(b, k / n.toFloat()), a.lerp(b, (k + 1) / n.toFloat()), 0.003f, m = dashed)
        }
        val mat = Mats.unlit(owned, 0xFFFFC83D.toInt(), 0.9f)
        snapDashed = modelEntity(mesh(dashed, "snapDashed"), mat, "snapDashed", snapFrame)
        snapSolid = modelEntity(mesh(solid, "snapSolid"), mat, "snapSolid", snapFrame)
        stage?.addChild(snapFrame); snapFrame.enabled = false
    }

    // ------------------------------------------------------------------------------------------------ kiln
    private fun buildKiln() {
        bench.addChild(kiln)
        val paint = Mats.pbr(owned, 0xFFC9CCC8.toInt(), 0.55f, 0.2f)
        val steel = Mats.pbr(owned, 0xFFB8BBBD.toInt(), 0.3f, 0.9f)
        val brickTex = owned.keep(TextureResource.create(brickBitmap()))
        val brick = owned.keep(PhysicallyBasedMaterial.create(com.pico.spatial.core.ecs.resource.BlendingMode.OPAQUE).apply {
            setBaseColor(Color4(1f, 1f, 1f, 1f)); setBaseColorTexture(brickTex); setRoughness(0.95f); setMetallic(0f)
        })
        val panel = Mats.pbr(owned, 0xFF1C1C1E.toInt(), 0.4f)
        for ((i, p) in KilnMesh.shell().withIndex()) modelEntity(mesh(p, "kilnShell$i"), paint, "kiln_shell:$i", kiln)
        for ((i, p) in KilnMesh.bands().withIndex()) modelEntity(mesh(p, "kilnBand$i"), steel, "kiln_band:$i", kiln)
        for ((i, p) in KilnMesh.lining().withIndex()) modelEntity(mesh(p, "kilnLining$i"), brick, "kiln_lining:$i", kiln)
        elemMat = Mats.pbr(owned, 0xFF6F6A64.toInt(), 0.5f, 0.6f).also { it.setEmissiveColor(Color4(0f, 0f, 0f, 1f)) }
        modelEntity(mesh(KilnMesh.elements(), "kilnElements"), elemMat!!, "kiln_elements", kiln)
        gapMat = Mats.unlit(owned, 0xFF000000.toInt())
        gapEntity = Entity().named("kiln_gap_glow").also { g -> kiln.addChild(g); for ((i, p) in KilnMesh.gapGlow().withIndex()) modelEntity(mesh(p, "kilnGap$i"), gapMat!!, "kiln_gap:$i", g) }
        modelEntity(mesh(KilnMesh.controllerBox(), "kilnCtrl"), panel, "kiln_controller", kiln)
        lampMat = Mats.unlit(owned, 0xFF3A2410.toInt())
        modelEntity(mesh(KilnMesh.lamp(), "kilnLamp"), lampMat!!, "kiln_lamp", kiln)
        modelEntity(mesh(KilnMesh.knob(), "kilnKnob"), steel, "kiln_knob", kiln)
        // digits plate on the controller (faces +x)
        digitsMat = owned.keep(UnlitMaterial.create(com.pico.spatial.core.ecs.resource.BlendingMode.OPAQUE)).also { runCatching { it.setApplyToneMapping(false) } }
        setDigits("20")
        val plate = PMesh(); val d = KilnMesh.DIGITS; val pw = 0.07f; val ph = 0.026f
        val a = plate.v(d.x, d.y - ph / 2, d.z + pw / 2, 1f, 0f, 0f, 0f, 0f); val b = plate.v(d.x, d.y - ph / 2, d.z - pw / 2, 1f, 0f, 0f, 1f, 0f)
        val c = plate.v(d.x, d.y + ph / 2, d.z - pw / 2, 1f, 0f, 0f, 1f, 1f); val e = plate.v(d.x, d.y + ph / 2, d.z + pw / 2, 1f, 0f, 0f, 0f, 1f)
        plate.tri(a, b, c); plate.tri(a, c, e)
        modelEntity(mesh(plate, "kilnDigits"), digitsMat!!, "kiln_digits", kiln)
        // the door on its hinge
        kiln.addChild(doorPivot); doorPivot.place(p = Layout.HINGE)
        modelEntity(mesh(KilnMesh.door(), "kilnDoor"), paint, "kiln_door", doorPivot)
        modelEntity(mesh(KilnMesh.doorLining(), "kilnDoorLining"), brick, "kiln_door_lining", doorPivot)
        modelEntity(mesh(KilnMesh.handle(), "kilnHandle"), steel, "kiln_handle", doorPivot)
        modelEntity(mesh(KilnMesh.peepRing(), "kilnPeepRing"), steel, "kiln_peep_ring", doorPivot)
        peepMat = Mats.unlit(owned, 0xFF5A4636.toInt())
        peepEntity = modelEntity(mesh(KilnMesh.peepGlow(), "kilnPeep"), peepMat!!, "kiln_peephole_glow", doorPivot)
        // warm spill light in the chamber (kilnGlow.spill: the SDK has point lights)
        kilnLight = Entity().named("kilnLight").also { l ->
            l.components.set(PointLightComponent(argb4(0xFFFF8A3D.toInt()), 0f, 0.6f))
            l.place(p = V3(Layout.KILN_C.x, 0.22f, Layout.KILN_C.z + 0.05f)); kiln.addChild(l)
        }
        kilnGlow(0f, doorOpen = false)
    }

    private fun setDigits(s: String) {
        if (s == digitsShown) return
        digitsShown = s
        val bmp = Bitmap.createBitmap(256, 96, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp); cv.drawColor(0xFF121214.toInt())
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFA43C.toInt(); textSize = 70f; typeface = android.graphics.Typeface.MONOSPACE; textAlign = android.graphics.Paint.Align.RIGHT; isFakeBoldText = true }
        cv.drawText(s, 240f, 72f, p)
        val old = digitsTex
        val t = TextureResource.create(bmp)
        runCatching { t.toGlobal() }
        digitsMat?.setBaseColorTexture(t)
        digitsTex = t
        runCatching { old?.close() }
    }

    /** kilnGlow.color: temperature ramp 0 → #000 · 0.25 → #7A1E05 · 0.6 → #E8501A · 1 → #FFB25C. */
    private fun ramp(s: Float): Color4 { val c = rampRgb(s); return Color4(c[0], c[1], c[2], 1f) }

    private fun rampRgb(s: Float): FloatArray {
        val stops = listOf(0f to 0x000000, 0.25f to 0x7A1E05, 0.6f to 0xE8501A, 1f to 0xFFB25C)
        fun ch(c: Int, k: Int) = ((c shr (16 - 8 * k)) and 0xFF) / 255f
        val x = s.coerceIn(0f, 1f)
        for (i in 1 until stops.size) if (x <= stops[i].first) {
            val (a, ca) = stops[i - 1]; val (b, cb) = stops[i]; val t = (x - a) / (b - a)
            return FloatArray(3) { k -> ch(ca, k) + (ch(cb, k) - ch(ca, k)) * t }
        }
        return FloatArray(3) { k -> ch(stops.last().second, k) }
    }

    private var lastGlowS = -1f
    private fun kilnGlow(s: Float, doorOpen: Boolean, elements: Float = s, lamp: Boolean = false) {
        val key = s * 7 + elements * 3 + (if (doorOpen) 1 else 0) + (if (lamp) 0.5f else 0f)
        if (key == lastGlowS) return
        lastGlowS = key
        val shut = if (doorOpen) 0f else s
        peepMat?.setBaseColor(if (shut > 0.01f) ramp(shut) else argb4(0xFF5A4636.toInt()))
        gapMat?.setBaseColor(ramp(0.8f * shut))
        gapEntity?.enabled = shut > 0.01f
        lampMat?.setBaseColor(if (lamp) argb4(0xFFFF9A3C.toInt()) else argb4(0xFF3A2410.toInt()))
        val ec = rampRgb(elements)
        elemMat?.setEmissiveColor(Color4(ec[0] * 4f, ec[1] * 4f, ec[2] * 4f, 1f))
        kilnLight?.components?.get(PointLightComponent::class.java)?.intensity = if (doorOpen) 2500f * elements else 600f * s
    }

    private suspend fun loadProps() {
        val names = Brush.entries.map { "tool_${it.id}" } + listOf("jar")
        val loaded = names.map { n -> scope.async { n to runCatching { Entity.loadSuspend("asset://models/$n.glb") }.onFailure { CfLog.e("GLB $n failed", it) }.getOrNull() } }.awaitAll().toMap()
        for ((i, b) in Brush.entries.withIndex()) {
            val slot = Entity().named("slot:${b.id}")
            slot.place(p = V3(Layout.TOOL_X[i], Layout.TOOL_Y, Layout.TOOL_Z))
            slot.components.set(CollisionComponent(listOf(ShapeResource.createBox(Layout.TOOL_BOX.sdk())), physics))
            slot.components.set(InteractableComponent()); slot.components.set(HoverEffectComponent())
            bench.addChild(slot); slots[b] = slot
            val model = loaded["tool_${b.id}"] ?: continue
            model.named("toolModel:${b.id}")
            toolModels[b] = model
            restOnTray(b)
        }
        // gate:allow geometry size indicator dots (design tools: 空位画三个小点 = 当前大小), abstract marker
        val dotMesh = mesh(Proc.sphere(0.0035f, 12, 6), "dot")
        repeat(3) { k -> sizeDots += modelEntity(dotMesh, Mats.unlit(owned, 0xFFFFFFFF.toInt()), "sizeDot$k", bench).also { it.enabled = false } }
        loaded["jar"]?.let { jar ->
            for ((i, g) in Glaze.entries.withIndex()) {
                val holder = Entity().named("jar:${g.id}")
                holder.place(p = V3(Layout.JAR_X[i], Layout.JAR_Y - Layout.JAR_H / 2, Layout.TOOL_Z))
                val model = if (i == 0) jar else jar.clone(Entity.CloneOptions(true, true)) ?: continue
                holder.addChild(model)
                val slurry = Mats.pbr(owned, g.argb, 0.15f, cull = false)
                modelEntity(mesh(Proc.disc(0.027f, Layout.JAR_H + 0.002f, 48), "slurry"), slurry, "slurry:${g.id}", holder)
                holder.components.set(CollisionComponent(listOf(ShapeResource.createBox(Vector3(Layout.JAR_D, 0.065f, Layout.JAR_D)).offsetByTranslation(Vector3(0f, 0.0325f, 0f))), physics))
                holder.components.set(InteractableComponent()); holder.components.set(HoverEffectComponent())
                bench.addChild(holder); holder.enabled = false
                jars[g] = holder
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ tools
    private fun slotPos(b: Brush) = V3(Layout.TOOL_X[b.ordinal], Layout.TOOL_Y, Layout.TOOL_Z)

    private fun restOnTray(b: Brush) {
        val m = toolModels[b] ?: return
        if (m.hasParent()) m.getParent()?.removeChild(m)
        val dir = V3(0.707f, 0f, 0.707f)
        val slot = slots[b]
        val local = V3(-0.04f, -0.013f, -0.03f)
        m.place(p = if (slot != null) local else slotPos(b) + local, q = fromTo(V3(1f, 0f, 0f), dir), s = 1f)
        (slot ?: bench).addChild(m)
    }

    private fun toolScale(s: ToolSize) = when (s) { ToolSize.S -> 0.85f; ToolSize.M -> 1f; ToolSize.L -> 1.2f }

    /** The brush the scene shows in hand (the lesson's own tool while watching). */
    private fun shownBrush(): Brush = if (Studio.mode == Mode.LESSON && Studio.lessonStage == LessonStage.WATCH) Studio.lesson.brush else Studio.brush

    /** Puts the selected tool in hand (flying from the tray) or just refreshes size dots / ring / scale. */
    fun applyTool(animate: Boolean) {
        val b = shownBrush()
        val showTool = Studio.toolShown()
        sculptor.brush = Studio.brush; sculptor.size = Studio.size; sculptor.mirror = Studio.mirror
        if (showTool) {
            val prev = heldBrush
            if (prev != b) {
                if (prev != null) restOnTray(prev)
                heldBrush = b
                toolModels[b]?.let { m -> if (m.hasParent()) m.getParent()?.removeChild(m); hand.addChild(m); m.place(p = V3.ZERO, q = Quat(0f, 0f, 0f, 1f), s = toolScale(Studio.size)) }
                fly = if (animate) Fly(slotPos(b) + V3(0f, 0.03f, 0f), SystemClock.uptimeMillis(), 250) else null
            }
            toolModels[b]?.place(s = toolScale(Studio.size))
            toolOut = true
        } else {
            heldBrush?.let { restOnTray(it) }; heldBrush = null; toolOut = false
            if (ctrlHand != null) unmountController("tool hidden")
        }
        placeTool(); applyDots()
    }

    private fun applyDots() {
        val b = heldBrush
        val show = toolOut && b != null && Studio.phase == Phase.SCULPT
        val n = Studio.size.ordinal + 1
        sizeDots.forEachIndexed { k, e -> e.enabled = show && k < n; if (b != null) e.place(p = slotPos(b) + V3(-0.012f + k * 0.012f, -0.012f, 0.03f)) }
    }

    /** The tool's tip and handle direction this frame (bench coordinates). */
    private fun toolPose(now: Long): Pair<V3, V3> {
        ctrlHand?.let { h ->
            val c = ctrlPoses()[h]
            if (c != null) { val bp = benchPose; if (bp != null) {
                val tip = bp.toLocal(c.position + c.forward * Layout.CTRL_TOOL_LEN)
                return tip to bp.dirToLocal(-c.forward).normalized()
            } }
        }
        pinchHand?.let { k ->
            val p = lastPinch[k]
            val bp = benchPose
            if (p != null && bp != null) return bp.toLocal(p.point) to bp.dirToLocal(-p.indexDir).normalized()
        }
        val tip0 = sculptor.tip
        val hover = player?.takeIf { Studio.lessonStage == LessonStage.WATCH }?.hover ?: 0f
        val tip = if (hover > 0f) tip0 + sculptor.tipNormal() * hover else tip0
        val f = fly
        val at = if (f != null) {
            val t = ((now - f.start) / f.ms.toFloat()).coerceIn(0f, 1f)
            if (t >= 1f) fly = null
            f.from.lerp(tip, 1 - (1 - t).pow(3)) + V3(0f, 0.03f * sin(PI.toFloat() * t), 0f)
        } else tip
        return at to Layout.handleFor(tip.y)
    }

    private fun placeTool(now: Long = SystemClock.uptimeMillis()) {
        val (at, h) = toolPose(now)
        val held = toolOut
        val onClay = held && ctrlHand == null && pinchHand == null
        hand.enabled = held
        hand.place(p = at, q = fromTo(V3(1f, 0f, 0f), h))
        // eye-hand targets only while the tool sits on the clay (hand / controller strokes do not need them)
        val eyeTargets = onClay && Studio.canSculpt
        head3d.enabled = eyeTargets; knob.enabled = eyeTargets
        val tip = sculptor.tip
        head3d.place(p = tip)
        knob.place(p = Layout.knobCentre(tip))
        setHeadCollider(Layout.headLenFor(tip.y))
        val r = Studio.size.r
        val ringOn = held && (Studio.canSculpt || Studio.lessonStage == LessonStage.WATCH)
        if (onClay) {
            val n = sculptor.tipNormal()
            tipRing.enabled = ringOn && sculptor.lastHit
            tipRing.place(p = tip + n * 0.002f, q = fromTo(V3(0f, 0f, 1f), n), s = r)
        } else {
            // hand / controller: the ring on the nearest clay surface (40 % when not pressing), grey when off the surface
            val near = sculptor.nearestSurface(at, 0.06f)
            tipRing.enabled = ringOn && near != null
            if (near != null) { val n = sculptor.toVol(field.normalAt(sculptor.toClay(near))); tipRing.place(p = near + n * 0.002f, q = fromTo(V3(0f, 0f, 1f), n), s = r) }
        }
        ringMat.setBaseColor(when { Studio.tooMuch -> argb4(0xFFFFB020.toInt()); sculptor.offSurface -> argb4(0xFF9A9A9A.toInt()); else -> argb4(0xFFFFFFFF.toInt()) })
        if (Studio.mirror && held && onClay) {
            val m = sculptor.mirrorTip()
            val nm = sculptor.toVol(field.normalAt(sculptor.toClay(m)))
            tipRing2.enabled = sculptor.lastHit
            tipRing2.place(p = m + nm * 0.002f, q = fromTo(V3(0f, 0f, 1f), nm), s = r)
        } else tipRing2.enabled = false
    }

    private var headLen = -1f
    private fun setHeadCollider(len: Float) {
        if (len == headLen) return
        headLen = len
        head3d.components.set(CollisionComponent(listOf(ShapeResource.createBox(Vector3(Layout.HEAD_D, Layout.HEAD_D, len)).offsetByTranslation(Vector3(0f, 0f, len / 2))), physics))
        head3d.components.set(InteractableComponent())
    }

    // ------------------------------------------------------------------------------------------------ phases
    fun applyPhase() {
        val p = Studio.phase
        val sculpt = p == Phase.SCULPT
        applyTool(animate = sculpt)
        val lessonWatch = Studio.mode == Mode.LESSON && Studio.lessonStage != LessonStage.TRY
        Brush.entries.forEach { b -> slots[b]?.enabled = sculpt || p == Phase.LOADING; toolModels[b]?.enabled = sculpt || p == Phase.LOADING }
        jars.values.forEach { j -> j.enabled = p == Phase.GLAZE || p == Phase.FIRING }
        mirrorPlane.enabled = Studio.mirror && sculpt && Studio.mode == Mode.FREE
        when (p) {
            Phase.SCULPT, Phase.LOADING -> { soak = null; chunks.material = clayMat }
            Phase.GLAZE -> applyGlazePreview(null)
            Phase.FIRED -> { soak = null; chunks.material = firedMats.getValue(Studio.currentGlaze ?: Studio.glaze); startSpin() }
            Phase.FIRING -> {}
        }
        if (lessonWatch) { /* slots stay visible but the tool rides the script */ }
        placeTool(); applyDots()
    }

    fun applyGlazePreview(hop: Glaze?) {
        val m = previewMats.getValue(Studio.glaze)
        soak = Triple(chunks.filledBottomUp(), SystemClock.uptimeMillis(), m)
        hop?.let { g -> jarHop = g to SystemClock.uptimeMillis() }
    }

    fun applyMirror() { sculptor.mirror = Studio.mirror; mirrorPlane.enabled = Studio.mirror && Studio.phase == Phase.SCULPT && Studio.mode == Mode.FREE; placeTool() }

    /** S11: door opens → carriage L into the kiln → door shuts → glow → door opens → carriage out → door shuts (5.2 s). */
    fun fire(done: () -> Unit) {
        kilnStart = SystemClock.uptimeMillis(); kilnDone = done; kilnSwapped = false; kilnSounds = 0
        Studio.sound.kilnDoor()
    }

    private fun startSpin() { spinStart = SystemClock.uptimeMillis(); spinUntil = spinStart + 6000; spinFrom = yaw }

    // ------------------------------------------------------------------------------------------------ turning
    fun setYaw(rad: Float) {
        yaw = rad
        sculptor.yaw = rad
        syncTurn()
        sculptor.refreshTip()
        placeTool()
    }

    private fun syncTurn() {
        if (sculptor.yaw != shownYaw) { shownYaw = sculptor.yaw; yaw = sculptor.yaw; turn.place(q = axisAngle(0f, 1f, 0f, Math.toDegrees(yaw.toDouble()).toFloat())) }
    }

    fun turn90() {
        if (!canTurn()) return
        spinUntil = 0
        turnAnim = Triple(yaw, yaw + PI.toFloat() / 2, SystemClock.uptimeMillis())
        Studio.sound.woodTap()
        CfLog.i("TURN90 from=${yawDeg().toInt()}")
    }

    fun yawDeg() = ((Math.toDegrees(yaw.toDouble()) % 360 + 360) % 360).toFloat()

    /** Turning is fine except while firing and while the demo plays (it runs on the turntable). */
    fun canTurn(): Boolean = Studio.phase != Phase.FIRING && !(Studio.lessonStage == LessonStage.WATCH && Studio.lesson.playing)

    private fun turnBy(rad: Float) {
        if (!canTurn()) return
        spinUntil = 0; turnAnim = null
        setYaw(yaw + rad)
        Studio.onTurned(Math.toDegrees(rad.toDouble()).toFloat())
    }

    // ------------------------------------------------------------------------------------------------ placement
    private fun placeBench(p: Layout.BenchPose, why: String) {
        benchPose = p
        bench.place(p = p.origin, q = axisAngle(0f, 1f, 0f, Math.toDegrees(p.yaw.toDouble()).toFloat()))
        CfLog.i("PLACE bench why=$why origin=${p.origin} yawDeg=${"%.1f".format(Math.toDegrees(p.yaw.toDouble()))}")
    }

    private fun animateBench(to: Layout.BenchPose, ms: Long, why: String) {
        val from = benchPose ?: return placeBench(to, why)
        benchAnim = Triple(from, to, SystemClock.uptimeMillis()); benchAnimMs = ms
        CfLog.i("PLACE bench animate why=$why to=${to.origin} yawDeg=${"%.1f".format(Math.toDegrees(to.yaw.toDouble()))} ms=$ms")
    }

    /** Settings [叫回面前] (S21): put the window away, wait for a still head, re-place the bench, reopen the window. */
    fun recall() {
        if (recalling) return
        recalling = true
        window.putAway()
        recallSampler.start(SystemClock.uptimeMillis())
        CfLog.i("RECALL start")
    }

    // ------------------------------------------------------------------------------------------------ system pointer input
    fun onTap(hit: Hit?, source: InputSource) {
        if (source == InputSource.DIRECT || source == InputSource.POKE) { if (hands.supported) { CfLog.i("TAP ignored src=${source.label} (hand channel)"); return } }
        when (hit) {
            is Hit.Slot -> {
                Studio.tapSlot(hit.brush, srcOf(source).label)
                if (source == InputSource.CONTROLLER && Studio.brush == hit.brush && toolOut) mountController(controllers.lastTriggerHand, "tap slot")
            }
            is Hit.Jar -> Studio.pickGlaze(hit.glaze)
            Hit.Head -> Studio.pokeHead()
            else -> {}
        }
    }

    private fun srcOf(s: InputSource) = when (s) {
        InputSource.EYE_HAND -> Src.GAZE; InputSource.HAND_RAY -> Src.HANDRAY; InputSource.CONTROLLER -> Src.CTRL
        InputSource.DIRECT, InputSource.POKE -> Src.HAND; else -> Src.POINTER
    }

    /** The pointer went down on [hit] and moved past the drag start. Returns true when taken. */
    fun dragStart(hit: Hit, source: InputSource): Boolean {
        if ((source == InputSource.DIRECT || source == InputSource.POKE) && hands.supported) return false
        // a tool riding a controller sculpts by its own tip; the pointer on the head is not a second stroke
        if (source == InputSource.CONTROLLER && ctrlHand != null && (hit == Hit.Head || hit == Hit.Knob)) return false
        dragTotal = V3.ZERO; filtered = V3.ZERO; euroX.reset(0f); euroY.reset(0f); euroZ.reset(0f); dragStartMs = SystemClock.uptimeMillis()
        dragSrc = srcOf(source)
        // eye-hand gain g = clamp(1 + 1.5 (d − 0.4), 1, 2.5), d = head → tip (design container.inputs.eyeHand)
        val hp = lastHead; val bp = benchPose
        gain = if (hp != null && bp != null && (dragSrc == Src.GAZE || dragSrc == Src.HANDRAY)) clampf(1f + 1.5f * (hp.position.dist(bp.toWorld(sculptor.tip)) - 0.4f), 1f, 2.5f) else 1f
        sculptor.eye = if (hp != null && bp != null) bp.toLocal(hp.position) else null
        drag = when {
            hit == Hit.Head && Studio.canSculpt && toolOut -> { Studio.strokeBegin(dragSrc.label); DragKind.HEAD }
            hit == Hit.Knob && Studio.canSculpt && toolOut -> { sculptor.pressKnob(); DragKind.KNOB }
            (hit == Hit.Clay || hit == Hit.Ring) && canTurn() -> { spinUntil = 0; turnAnim = null; DragKind.TURN }
            hit == Hit.Bench && Studio.phase != Phase.FIRING -> { beginBenchMove(dragSrc.label); DragKind.BENCH }
            else -> DragKind.NONE
        }
        CfLog.i("DRAG start hit=$hit kind=$drag src=${dragSrc.label} gain=${"%.2f".format(gain)}")
        return drag != DragKind.NONE
    }

    /** [total] = movement since the press in metres (the SDK's dragAmount is cumulative). */
    fun dragMove(total: V3) {
        val now = SystemClock.uptimeMillis()
        val prev = dragTotal
        dragTotal = total
        when (drag) {
            DragKind.HEAD, DragKind.KNOB -> {
                val raw = total * gain
                val f = V3(euroX.filter(raw.x, now), euroY.filter(raw.y, now), euroZ.filter(raw.z, now))
                val d0 = f - filtered
                filtered = f
                // the pointer's axes are the viewer's; the bench may be turned: map x / z into bench axes (y stays)
                val d = benchPose?.let { bp -> lastHead?.let { h -> viewToBench(d0, h, bp) } } ?: d0
                val changed = sculptor.drag(d, now)
                if (changed) Studio.sound.stroke(Studio.brush, pathM = sqrt(d.x * d.x + d.y * d.y))
                if (sculptor.edgeHit) boundsUntil = now + 400
                placeTool(now)
                Studio.onStrokeMoved()
            }
            DragKind.TURN -> {
                val dx = total.x - prev.x
                turnBy(dx / Layout.CLAY_R)
            }
            DragKind.BENCH -> moveBench(total)
            DragKind.NONE -> {}
        }
    }

    /** Viewer-axis delta (x right, y up, z toward the viewer) → bench axes, using only the head's heading. */
    private fun viewToBench(d: V3, h: HeadPose, bp: Layout.BenchPose): V3 {
        val viewYaw = Layout.yawFacing(h.forward)
        return d.rotY(viewYaw - bp.yaw)
    }

    fun dragEnd() {
        when (drag) {
            DragKind.HEAD -> {
                val wasGate = sculptor.phase == Sculptor.Phase.GATE
                Studio.strokeEnd()
                if (wasGate && SystemClock.uptimeMillis() - dragStartMs < 600 && dragTotal.length < 0.01f) Studio.pokeHead()
            }
            DragKind.KNOB -> { sculptor.release(); CfLog.i("MOVETIP tip=${sculptor.tip}") }
            DragKind.TURN -> CfLog.i("TURN yaw=${yawDeg().toInt()}")
            DragKind.BENCH -> endBenchMove()
            DragKind.NONE -> {}
        }
        drag = DragKind.NONE
        sculptor.eye = null
        updateProxy(); placeTool()
    }

    fun dropDrag() {
        if (drag != DragKind.NONE) dragEnd()
        if (absSrc != null) absEnd()
    }

    // ------------------------------------------------------------------------------------------------ bench move (S22)
    private fun beginBenchMove(src: String) {
        benchDragStart = benchPose; benchMoving = true
        lastHead?.let { home = Layout.placeUpright(it.position, it.forward) }
        snapFrame.enabled = home != null
        CfLog.i("BENCH move start src=$src")
        Studio.logState("bench move")
    }

    /** [total] world-space move since the press (system drag in viewer axes ≈ Stage axes; hand / debug give world). */
    fun moveBench(total: V3) {
        val s = benchDragStart ?: return
        var p = Layout.BenchPose(V3(s.origin.x + total.x, clampf(s.origin.y + total.y, 0.4f, 1.3f), s.origin.z + total.z), benchPose?.yaw ?: s.yaw)
        val h = home
        if (h != null) {
            val was = snap.snapped
            if (snap.step(p, h)) { p = h; if (!was) { Studio.sound.woodTap(); CfLog.i("BENCH snap in") } } else if (was) CfLog.i("BENCH snap out")
            snapDashed?.enabled = !snap.snapped; snapSolid?.enabled = snap.snapped
            snapFrame.place(p = h.origin, q = axisAngle(0f, 1f, 0f, Math.toDegrees(h.yaw.toDouble()).toFloat()))
        }
        placeBench(p, "move")
    }

    /** Thumbstick left / right while moving: turn the bench 15° per push. */
    private fun turnBench(deg: Float) {
        val b = benchPose ?: return
        benchDragStart = benchDragStart?.copy(yaw = b.yaw + Math.toRadians(deg.toDouble()).toFloat())
        placeBench(b.copy(yaw = b.yaw + Math.toRadians(deg.toDouble()).toFloat()), "stick $deg")
    }

    private fun endBenchMove() {
        val b = benchPose
        val hd = lastHead
        benchMoving = false; snapFrame.enabled = false
        if (b != null && hd != null && !snap.snapped) {
            // released: stays where it is, turns back to face you (300 ms easeOutCubic)
            val toUser = hd.position - b.origin
            val yaw = kotlin.math.atan2(toUser.x, toUser.z)
            animateBench(b.copy(yaw = yaw), 300, "release face user")
        } else if (b != null && snap.snapped) CfLog.i("BENCH released snapped")
        benchDragStart = null
        CfLog.i("BENCH move end")
        Studio.logState("bench moved")
    }

    // ------------------------------------------------------------------------------------------------ controller channel
    private fun ctrlPoses(): List<CtrlPose?> { val (l, r) = controllers.poses(); return listOf(l, r) }

    fun mountController(h: Int, why: String) {
        if (ctrlPoses()[h] == null) { CfLog.i("CTRL mount skipped hand=$h (no pose) why=$why"); return }
        ctrlHand = h; pinchHand = null
        CfLog.i("CTRL tool mounted hand=$h brush=${Studio.brush.id} why=$why")
        placeTool()
    }

    fun unmountController(why: String) {
        if (ctrlHand == null) return
        if (absSrc == Src.CTRL) absEnd()
        CfLog.i("CTRL tool back to the tray hand=$ctrlHand why=$why")
        ctrlHand = null
        placeTool()
    }

    private fun controllerFrame(now: Long) {
        val poses = ctrlPoses()
        val bp = benchPose ?: return
        val h = ctrlHand
        if (h != null) {
            val c = poses[h]
            if (c == null) { unmountController("lost tracking"); return }
            val tip = bp.toLocal(c.position + c.forward * Layout.CTRL_TOOL_LEN)
            val held = controllers.triggerHeld(h)
            val presses = controllers.takeTriggerPresses(h)
            if (absSrc == null && held && presses > 0) {
                // trigger at a tray slot: another tool = swap, its own slot = put it back
                val slot = slotAt(tip, 0.02f)
                when {
                    slot != null && slot == Studio.brush -> unmountController("own slot")
                    slot != null -> { Studio.tapSlot(slot, "ctrl"); mountController(h, "swap") }
                    Studio.canSculpt && nearClay(tip, Studio.size.r * 1.5f + 0.003f) -> absBegin(tip, Src.CTRL)
                    else -> CfLog.i("CTRL trigger away from the clay tip=$tip")
                }
            } else if (absSrc == Src.CTRL && held) absMove(tip, now)
            else if (absSrc == Src.CTRL && !held) absEnd()
            if (controllers.takeBackPresses() > 0) unmountController("B/Y")
        } else { controllers.takeTriggerPresses(0); controllers.takeTriggerPresses(1); controllers.takeBackPresses() }
        // thumbstick: x = turn (push 90°, hold 60°/s; bench move: 15° per push), y = size up / down
        val x = controllers.stickXv; val y = controllers.stickYv
        if (abs(x) < 0.2f) { stickArmed = true; stickHoldSince = 0L }
        else if (abs(x) > 0.6f) {
            if (benchMoving) { if (stickArmed) { stickArmed = false; turnBench(if (x > 0) -15f else 15f) } }
            else if (stickArmed) { stickArmed = false; stickHoldSince = now; if (x > 0) turn90() else turnMinus90() }
            else if (stickHoldSince > 0 && now - stickHoldSince > 450) turnBy(-(if (x > 0) 1 else -1) * Math.toRadians(60.0 / 60.0).toFloat())
        }
        if (abs(y) < 0.2f) stickYArmed = true
        else if (abs(y) > 0.6f && stickYArmed) { stickYArmed = false; Studio.stepSize(if (y > 0) 1 else -1, "stick") }
    }

    private fun turnMinus90() {
        if (!canTurn()) return
        spinUntil = 0
        turnAnim = Triple(yaw, yaw - PI.toFloat() / 2, SystemClock.uptimeMillis())
        Studio.sound.woodTap()
    }

    private fun slotAt(p: V3, margin: Float): Brush? = Brush.entries.firstOrNull { b ->
        val c = slotPos(b); val hb = Layout.TOOL_BOX * 0.5f
        abs(p.x - c.x) <= hb.x + margin && abs(p.y - c.y) <= hb.y + margin && abs(p.z - c.z) <= hb.z + margin
    }

    private fun nearClay(p: V3, reach: Float): Boolean {
        val c = sculptor.toClay(p)
        if (field.sample(c.x, c.y, c.z) > 0.5f) return true
        return sculptor.nearestSurface(p, reach) != null
    }

    // ------------------------------------------------------------------------------------------------ hand channel
    private val lastPinch = arrayOfNulls<Pinch>(2)

    private fun handFrame(now: Long) {
        val bp = benchPose ?: return
        val (l, r) = hands.pinches()
        for ((k, p) in listOf(l, r).withIndex()) {
            val was = lastPinch[k]?.pinched == true
            lastPinch[k] = p
            if (p == null) { if (pinchState[k] != null) pinchEnd(k); continue }
            val local = bp.toLocal(p.point)
            if (!was && p.pinched) pinchBegin(k, local, p)
            else if (was && p.pinched) pinchHold(k, local, p, now)
            else if (was && !p.pinched) pinchEnd(k)
        }
    }

    private fun pinchBegin(k: Int, local: V3, p: Pinch) {
        val slot = slotAt(local, 0.03f)
        val c = sculptor.toClay(local)
        val rr = sqrt(c.x * c.x + c.z * c.z)
        val onRing = rr in 0.13f..0.23f && local.y in 0.0f..0.10f
        val onEdge = local.x < Layout.BENCH_X0 + Layout.BENCH_EDGE || local.x > Layout.BENCH_X1 - Layout.BENCH_EDGE || local.z < Layout.BENCH_Z0 + Layout.BENCH_EDGE || local.z > Layout.BENCH_Z1 - Layout.BENCH_EDGE
        val state = when {
            slot != null && Studio.phase == Phase.SCULPT -> { Studio.tapSlot(slot, "hand"); if (toolOut) { pinchHand = k; ctrlHand = null }; "slot" }
            toolOut && Studio.canSculpt && nearClay(local, 0.03f) && absSrc == null -> { pinchHand = k; absBegin(local, Src.HAND); "clay" }
            onRing && canTurn() -> { pinchAz[k] = atan2(local.z, local.x); "ring" }
            onEdge && abs(local.y) < 0.08f && Studio.phase != Phase.FIRING -> { pinchStart[k] = p.point; beginBenchMove("hand"); "bench" }
            else -> null
        }
        pinchState[k] = state
        CfLog.i("PINCH begin hand=$k at=$local target=$state")
    }

    private fun pinchHold(k: Int, local: V3, p: Pinch, now: Long) {
        when (pinchState[k]) {
            "clay" -> if (absSrc == Src.HAND) absMove(local, now)
            "ring" -> { val a = atan2(local.z, local.x); var d = a - pinchAz[k]; while (d > PI) d -= 2 * PI.toFloat(); while (d < -PI) d += 2 * PI.toFloat(); pinchAz[k] = a; turnBy(-d) }
            "bench" -> pinchStart[k]?.let { s -> moveBench(p.point - s) }
        }
    }

    private fun pinchEnd(k: Int) {
        when (pinchState[k]) {
            "clay" -> if (absSrc == Src.HAND) absEnd()
            "bench" -> endBenchMove()
        }
        if (pinchState[k] != null) CfLog.i("PINCH end hand=$k target=${pinchState[k]}")
        pinchState[k] = null
        if (pinchHand == k && absSrc == null) { pinchHand = null; placeTool() }
    }

    // ------------------------------------------------------------------------------------------------ absolute strokes
    private fun absBegin(tip: V3, src: Src) {
        absSrc = src
        tipEuro.forEachIndexed { i, e -> e.reset(when (i) { 0 -> tip.x; 1 -> tip.y; else -> tip.z }) }
        Studio.strokeBeginAbs(tip, src.label)
        dragStartMs = SystemClock.uptimeMillis()
    }

    private fun absMove(tip: V3, now: Long) {
        val f = V3(tipEuro[0].filter(tip.x, now), tipEuro[1].filter(tip.y, now), tipEuro[2].filter(tip.z, now))
        val before = sculptor.tip
        val changed = sculptor.dragTo(f, now)
        if (changed) Studio.sound.stroke(Studio.brush, pathM = before.dist(sculptor.tip))
        if (sculptor.edgeHit) boundsUntil = now + 400
        Studio.onStrokeMoved()
    }

    private fun absEnd() {
        val wasGate = sculptor.phase == Sculptor.Phase.GATE
        val short = SystemClock.uptimeMillis() - dragStartMs < 250
        absSrc = null
        Studio.strokeEnd()
        if (wasGate && short) Studio.pokeHead()
        updateProxy()
    }

    /** Debug `hand x y z 0|1` (bench coordinates, right hand) and `ctrl x y z dx dy dz 0|1` (tip ray). */
    fun injectHand(local: V3?, pinched: Boolean) {
        val bp = benchPose ?: return
        hands.injected = local?.let { Pinch(bp.toWorld(it), pinched, bp.dirToWorld(V3(-0.35f, -0.30f, -0.89f).normalized())) }
    }

    fun injectController(tipLocal: V3?, pressed: Boolean) {
        val bp = benchPose ?: return
        if (tipLocal == null) { controllers.injected = null; return }
        // the controller sits 0.10 m behind the tip along the handle direction h (right, up, toward the user)
        val fwdLocal = (-Layout.HANDLE).normalized()
        val grip = bp.toWorld(tipLocal - fwdLocal * Layout.CTRL_TOOL_LEN)
        val fw = bp.dirToWorld(fwdLocal)
        val q = fromTo(V3(0f, 0f, -1f), fw)
        val pose = CtrlPose(grip, Q4(q.x, q.y, q.z, q.w))
        val wasHeld = controllers.injected?.second == true
        controllers.injected = pose to pressed
        if (pressed && !wasHeld) injectPress = true
    }
    private var injectPress = false

    // ------------------------------------------------------------------------------------------------ clay proxy
    fun updateProxy() {
        val shapes = ArrayList<ShapeResource>()
        val bands = listOf(0.07f to 0.15f, 0.15f to 0.23f, 0.23f to 0.31f, 0.31f to 0.39f)
        for ((y0, y1) in bands) {
            var rMin = Float.MAX_VALUE
            var y = y0 + 0.005f
            while (y < y1) {
                var rLayer = Float.MAX_VALUE
                for (k in 0 until 16) {
                    val a = 2 * PI.toFloat() * k / 16
                    var r = 0f
                    while (r < 0.15f && field.sample(r * cos(a), y, r * sin(a)) > 0.5f) r += 0.005f
                    rLayer = minOf(rLayer, r)
                }
                rMin = minOf(rMin, rLayer)
                y += 0.02f
            }
            val r = rMin * 0.9f
            if (r < 0.01f) continue
            val half = r * 0.7071f
            shapes += ShapeResource.createBox(Vector3(half * 2, y1 - y0, half * 2)).offsetByTranslation(Vector3(0f, (y0 + y1) / 2, 0f))
        }
        run {
            var foot = 0f
            for (k in 0 until 32) { val a = 2 * PI.toFloat() * k / 32; var r = 0f; var rr = 0f
                while (rr < 0.15f) { if (field.sample(rr * cos(a), 0.075f, rr * sin(a)) > 0.5f) r = rr; rr += 0.005f }
                foot = maxOf(foot, r) }
            shadow?.enabled = foot > 0.005f
            shadow?.place(s = minOf(1.5f, foot * 1.1f / 0.1f))
        }
        if (shapes.isEmpty()) { clayProxy.components.remove(CollisionComponent::class.java); return }
        clayProxy.components.set(CollisionComponent(shapes, physics))
        clayProxy.components.set(InteractableComponent())
    }

    // ------------------------------------------------------------------------------------------------ clay
    fun loadShape(fill: (ClayField) -> Unit) {
        fill(field)
        field.markAllDirty()
        scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            while (field.dirty.any { it }) { chunks.update(field, null, 8); delay(16) }
            Studio.triangles = chunks.triangles
            sculptor.updateBudget(); Studio.tooMuch = sculptor.tooMuch
            sculptor.refreshTip(); updateProxy(); placeTool()
            if (Studio.phase == Phase.FIRED) chunks.material = firedMats.getValue(Studio.currentGlaze ?: Studio.glaze)
            CfLog.i("CLAY loaded tris=${chunks.triangles} ms=${SystemClock.elapsedRealtime() - t0}")
        }
    }

    fun flushMesh() {
        scope.launch {
            while (field.dirty.any { it }) chunks.update(field, sculptor.toClay(sculptor.tip), 0)
            Studio.triangles = chunks.triangles
            sculptor.updateBudget(); Studio.tooMuch = sculptor.tooMuch
            updateProxy(); placeTool()
        }
    }

    fun chunkMeshes(): List<ChunkMesh> = chunks.meshes()
    val triangles get() = chunks.triangles

    // ------------------------------------------------------------------------------------------------ lessons
    /** Builds (or reuses) the offline plan of a lesson on a worker; [then] runs on the main thread. */
    fun withPlan(id: String, then: (LessonPlan) -> Unit) {
        plans[id]?.let { plan = it; then(it); return }
        scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val p = withContext(Dispatchers.Default) { LessonPlan.build(Lessons.of(id)!!) }
            plans.clear(); plans[id] = p   // only the current lesson's snapshots stay (design ≤ 15 snapshots)
            plan = p
            CfLog.i("LESSON plan $id steps=${p.lesson.steps.size} ms=${SystemClock.elapsedRealtime() - t0}")
            then(p)
        }
    }

    /** Field = a step's start (or the lesson's start shape when [si] < 0), yaw to match. */
    fun loadLessonField(si: Int) {
        val p = plan ?: return
        stopPlayer()
        if (si < 0) loadShape { f -> f.fromSdf(p.lesson.start::sdf) } else loadShape { f -> f.copyFrom(p.starts[si]) }
        setYaw(if (si < 0) 0f else Math.toRadians(p.yaws[si].toDouble()).toFloat())
        sculptor.clearUndo()
    }

    fun loadLessonEnd(si: Int) {
        val p = plan ?: return
        stopPlayer()
        loadShape { f -> f.copyFrom(p.ends[si]) }
        setYaw(Math.toRadians((if (si + 1 < p.yaws.size) p.yaws[si + 1] else p.endYaw).toDouble()).toFloat())
    }

    fun startPlayer(si: Int) {
        val p = plan ?: return
        player = StepPlayer(sculptor, p.lesson.steps[si], p.lesson.brush)
        playT = 0f; stepDoneAt = 0L
        pausedYaw = Float.NaN
        CfLog.i("LESSON play ${p.lesson.id} step ${si + 1} dur=${"%.1f".format(player!!.duration)}s")
    }

    fun stopPlayer() { player = null; stepDoneAt = 0L }

    /** Debug `lesson n step i t u`: jump into a step at fraction [u] of its time (paused there). */
    fun seekPlayer(si: Int, u: Float) {
        val p = plan ?: return
        loadLessonField(si)
        val pl = StepPlayer(sculptor, p.lesson.steps[si], p.lesson.brush)
        player = pl
        playT = pl.duration * u
        pl.advance(playT)
        field.markAllDirty()
        syncTurn()
    }

    fun onPause(paused: Boolean) { if (paused) pausedYaw = yaw else if (!pausedYaw.isNaN()) { setYaw(pausedYaw); pausedYaw = Float.NaN } }

    private fun lessonFrame(now: Long, dt: Float) {
        val st = Studio.lessonStage
        if (Studio.mode != Mode.LESSON) return
        if (st == LessonStage.WATCH) {
            val pl = player ?: return
            if (Studio.lesson.playing && !pl.finished) {
                playT += dt
                if (pl.advance(playT)) Studio.sound.stroke(Studio.lesson.brush, pathM = 0.004f, demo = true)
                syncTurn()
            }
            if (pl.finished && stepDoneAt == 0L) { stepDoneAt = now; Studio.sound.strokeEnd(); Studio.onDemoStepDone() }
            if (pl.finished && stepDoneAt > 0 && Studio.lesson.playing && now - stepDoneAt >= (Lessons.PAUSE_S * 1000).toLong()) Studio.onDemoPauseOver()
        }
        if (st == LessonStage.TRY) {
            val p = plan ?: return
            if (now - scoreAt >= 200) {
                scoreAt = now
                val s = p.score(Studio.lesson.si, field)
                Studio.onScore(s)
            }
            flash.enabled = now < flashUntil && ((flashUntil - now) / 200) % 2 == 0L
        } else flash.enabled = false
    }

    /** Try-it visuals for step [si]: white dots, yellow start ring, pull arrows, target ghost. null = hide. */
    fun showGuide(si: Int?) {
        guideNode.removeAllChildren()
        ghostNode.enabled = false
        guideDots = emptyList()
        val p = plan ?: return
        if (si == null) return
        val step = p.lesson.steps[si]
        val g = StepPlayer.guide(step, p.lesson.brush, p.starts[si], p.yaws[si])
        guideDots = g.dots
        val dm = PMesh(); for (d in g.dots + g.arrows) Proc.sphere(0.0028f, 10, 6, d, dm)
        if (dm.vertexCount > 0) modelEntity(mesh(dm, "guideDots"), dotMat, "guideDots", guideNode)
        g.ring?.let { r ->
            val n = p.starts[si].normalAt(r)
            val e = modelEntity(mesh(Proc.ring(0.8f, 1f, 48), "guideRing"), yellowMat, "guideRing", guideNode)
            e.place(p = r + n * 0.003f, q = fromTo(V3(0f, 0f, 1f), n), s = step.size.r)
        }
        // the ghost: this step's target, 16 % white, meshed once
        val target = p.ends[si].copy(); target.markAllDirty()
        scope.launch { while (target.dirty.any { it }) ghost.update(target, null, 16); ghostNode.enabled = Studio.lessonStage == LessonStage.TRY || Studio.lesson.ghostOn }
        CfLog.i("LESSON guide step ${si + 1} dots=${g.dots.size} arrows=${g.arrows.size} ring=${g.ring}")
    }

    /** S26 [虚影]: the last step's target ghost over the finished piece. */
    fun showEndGhost(on: Boolean) {
        val p = plan ?: return
        guideNode.removeAllChildren()
        if (!on) { ghostNode.enabled = false; return }
        val target = p.ends.last().copy(); target.markAllDirty()
        scope.launch { while (target.dirty.any { it }) ghost.update(target, null, 16); ghostNode.enabled = true }
    }

    fun flashWorst() {
        val p = plan ?: return
        val k = p.worstDot(Studio.lesson.si, field, guideDots)
        if (k < 0) return
        flash.place(p = guideDots[k]); flashUntil = SystemClock.uptimeMillis() + 800
        CfLog.i("LESSON whereOff dot=$k at=${guideDots[k]}")
    }

    /** Debug `lesson inject [half]`: the demo's moves fed to your clay (same path as a stroke of yours), then scored. */
    fun injectDemo(half: Boolean) {
        val p = plan ?: return
        val si = Studio.lesson.si
        val pl = StepPlayer(sculptor, p.lesson.steps[si], p.lesson.brush)
        val y0 = yaw
        pl.advance(if (half) pl.sculptMid() else pl.duration + 1f)
        field.markAllDirty(); syncTurn(); flushMesh()
        if (half) setYaw(y0)
        scoreAt = 0L
        Studio.onUserStroke()
        CfLog.i("LESSON inject half=$half score=${"%.2f".format(p.score(si, field))}")
    }

    // ------------------------------------------------------------------------------------------------ frame
    fun onFrame() {
        val nowNs = java.lang.System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0f else ((nowNs - lastFrameNs) / 1e9f).coerceIn(0f, 0.1f)
        lastFrameNs = nowNs
        frames++
        val now = SystemClock.uptimeMillis()
        val pose = head.pose()
        if (pose != null) lastHead = pose
        window.stepFrame(pose?.pitchDeg)
        // first placement: head steady, or 2 s without a head → (0, 1.2, 0) looking −z
        if (benchPose == null) {
            if (pose != null && head.steady) { placeBench(Layout.placeUpright(pose.position, pose.forward), "start"); window.request("start") }
            else if (now - startedMs > 2000) { placeBench(Layout.placeUpright(V3(0f, 1.2f, 0f), V3(0f, 0f, -1f)), "start/nohead"); window.request("start") }
        }
        recallFrame(now, pose)
        benchAnim?.let { (a, b, t0) ->
            val t = ((now - t0) / benchAnimMs.toFloat()).coerceIn(0f, 1f)
            val e = if (benchAnimMs >= 400) Lessons.easeInOutCubic(t) else Lessons.easeOutCubic(t)
            val yawD = Math.toRadians(Layout.angDiffDeg(b.yaw, a.yaw).toDouble()).toFloat()
            placeBench(Layout.BenchPose(a.origin.lerp(b.origin, e), a.yaw + yawD * e), "anim")
            if (t >= 1f) benchAnim = null
        }
        if (!ready) { hudFrame(pose); return }
        controllerFrame(now)
        if (injectPress) { injectPress = false }
        handFrame(now)
        // dynamic mesh: ≤ 20 Hz, ≤ 6 chunks nearest the tip (design strokeRules.rate)
        if (now - lastMesh >= 50 && field.dirty.any { it } && !chunks.busy) {
            lastMesh = now
            scope.launch {
                chunks.update(field, sculptor.toClay(sculptor.tip), ClayChunks.PER_UPDATE)
                Studio.triangles = chunks.triangles
                val was = Studio.tooMuch
                sculptor.updateBudget(); Studio.tooMuch = sculptor.tooMuch
                if (was != Studio.tooMuch) Studio.logState("budget")
            }
        }
        lessonFrame(now, dt)
        // turn 90 (easeInOutSine 300 ms)
        turnAnim?.let { (a, b, t0) ->
            val t = ((now - t0) / 300f).coerceIn(0f, 1f)
            setYaw(a + (b - a) * (0.5f - 0.5f * cos(PI.toFloat() * t)))
            if (t >= 1f) { turnAnim = null; Studio.sound.woodTap(); Studio.onTurned(90f); CfLog.i("TURN yaw=${yawDeg().toInt()}") }
        }
        if (spinUntil > now && drag == DragKind.NONE) setYaw(spinFrom + 2 * PI.toFloat() * ((now - spinStart) / 6000f))
        else if (spinUntil in 1..now) { spinUntil = 0; setYaw(spinFrom + 2 * PI.toFloat()) }
        soak?.let { (list, t0, m) ->
            val n = ((now - t0) / 40).toInt() + 1
            for (i in 0 until minOf(n, list.size)) chunks.setChunkMaterial(list[i], m)
            if (n >= list.size) { chunks.material = m; soak = null }
        }
        jarHop?.let { (g, t0) ->
            val t = ((now - t0) / 350f).coerceIn(0f, 1f)
            jars[g]?.place(p = V3(Layout.JAR_X[g.ordinal], Layout.JAR_Y - Layout.JAR_H / 2 + 0.01f * sin(PI.toFloat() * t), Layout.TOOL_Z))
            if (t >= 1f) jarHop = null
        }
        kilnTick(now)
        bounds.enabled = now < boundsUntil
        placeTool(now)
        chipsFrame()
        hudFrame(pose)
        logDiag(now)
        if (now - lastLog >= 5000) {
            CfLog.i("FRAME loops=${(frames - f0) * 1000 / maxOf(1, now - lastLog)}/s tris=${chunks.triangles} state=${Studio.stateId()}")
            f0 = frames; lastLog = now
        }
    }
    private var lastMesh = 0L
    private var lastLog = 0L
    private var f0 = 0

    private fun recallFrame(now: Long, pose: HeadPose?) {
        if (!recalling) return
        val p = pose ?: return
        if (recallSampler.step(now, p.yawDeg, p.pitchDeg)) {
            recalling = false
            val to = Layout.placeUpright(p.position, p.forward)
            animateBench(to, 400, "recall")
            window.request("recall")
            Studio.logState("recalled")
        }
    }

    private val diagLogged = BooleanArray(3)
    private fun logDiag(now: Long) {
        val t = now - startedMs
        for ((i, s) in listOf(1000L, 3000L, 5000L).withIndex()) if (!diagLogged[i] && t >= s) {
            diagLogged[i] = true
            val h = lastHead; val b = benchPose
            val clayW = b?.toWorld(V3(0f, 0.2f, 0f))
            val dist = if (h != null && clayW != null) h.position.dist(clayW) else null
            val az = if (h != null && clayW != null) { val d = clayW - h.position; Layout.angDiffDeg(kotlin.math.atan2(-d.x, -d.z), kotlin.math.atan2(-h.forward.x, -h.forward.z)) } else null
            CfLog.i("DIAG t=${s / 1000}s head=${h?.position} bench=${b?.origin} clay=$clayW dist=${dist?.let { "%.2f".format(it) }} azFromGaze=${az?.let { "%.0f".format(it) }} panel=${window.panelState} ready=$ready state=${Studio.stateId()}")
        }
    }

    // ------------------------------------------------------------------------------------------------ chips & hud
    private fun panel(id: String, on: Boolean, p: V3? = null, q: Quat? = null) {
        val h = holders[id] ?: return
        if (h.enabled != on) h.enabled = on
        if (panelShown[id] != on) panelShown[id] = on
        if (!on || p == null) return
        val (wdp, _) = PanelIds.sizeDp(id); val (wm, _) = PanelIds.sizeM(id)
        val s = wm / (wdp * dpM)
        h.place(p = p, q = q, s = s)
    }

    private val chipQ get() = axisAngle(1f, 0f, 0f, -Layout.CHIP_TILT_DEG)

    private fun chipsFrame() {
        val ph = Studio.phase
        val lessonOn = Studio.mode == Mode.LESSON && ph == Phase.SCULPT && Studio.lessonStage in setOf(LessonStage.WATCH, LessonStage.TRY, LessonStage.DONE)
        val panelClosed = !window.open && window.everOpened && !recalling && !window.exiting
        val turnOn = ready && ph != Phase.FIRING && ph != Phase.LOADING && !(panelClosed && ph == Phase.GLAZE) && !window.exiting
        panel(PanelIds.TURN, turnOn, Layout.CHIP_TURN, chipQ)
        val mirrorOn = ready && !window.exiting && (panelClosed || (ph == Phase.SCULPT && Studio.mode == Mode.FREE))
        panel(PanelIds.MIRROR, mirrorOn, Layout.CHIP_MIRROR, chipQ)
        val capQ = axisAngle(0f, 1f, 0f, Layout.CAPTION_YAW_DEG)
        val stage = Studio.lessonStage
        panel(PanelIds.CAPTION, lessonOn && stage != LessonStage.DONE, Layout.CAPTION, capQ)
        for ((i, id) in PanelIds.TRANSPORT.withIndex()) panel(id, lessonOn && stage != LessonStage.DONE, Layout.TRANSPORT[i], capQ)
        panel(PanelIds.SCORE, lessonOn && stage == LessonStage.TRY, Layout.SCORE, axisAngle(0f, 1f, 0f, 14f))
    }

    private fun hudFrame(pose: HeadPose?) {
        val text = when {
            window.exiting -> null
            recalling -> "recall"
            !ready -> "loading"
            window.levelHint -> "level"
            else -> null
        }
        if (hudText.value != text) hudText.value = text
        val on = text != null && pose != null
        if (!on) { panel(PanelIds.HUD, false); return }
        val p = pose!!
        val f = V3(p.forward.x, 0f, p.forward.z).normalized()
        val at = p.position + f * 0.8f + V3(0f, -0.8f * 0.268f, 0f)
        val toEye = (p.position - at).let { V3(it.x, 0f, it.z).normalized() }
        holders[PanelIds.HUD]?.let { h -> if (!h.enabled) h.enabled = true }
        val (wdp, _) = PanelIds.sizeDp(PanelIds.HUD); val (wm, _) = PanelIds.sizeM(PanelIds.HUD)
        holders[PanelIds.HUD]?.place(p = at, q = fromTo(V3(0f, 0f, 1f), toEye), s = wm / (wdp * dpM))
        if (panelShown[PanelIds.HUD] != true) panelShown[PanelIds.HUD] = true
    }

    // ------------------------------------------------------------------------------------------------ kiln timeline
    private fun kilnTick(now: Long) {
        if (kilnStart == 0L) return
        val t = (now - kilnStart) / 1000f
        fun seg(a: Float, b: Float) = ((t - a) / (b - a)).coerceIn(0f, 1f)
        // door (degrees open)
        val door = when {
            t < 0.5f -> 110f * Lessons.easeOutCubic(seg(0f, 0.5f))
            t < 1.7f -> 110f
            t < 2.1f -> 110f * (1 - Lessons.easeInOutSine(seg(1.7f, 2.1f)))
            t < 3.5f -> 0f
            t < 3.9f -> 110f * Lessons.easeOutCubic(seg(3.5f, 3.9f))
            t < 4.8f -> 110f
            else -> 110f * (1 - Lessons.easeInOutSine(seg(4.8f, 5.2f)))
        }
        // carriage along the L path
        val u = when {
            t < 0.5f -> 0f
            t < 1.1f -> 0.5f * Lessons.easeInOutCubic(seg(0.5f, 1.1f))
            t < 1.7f -> 0.5f + 0.5f * Lessons.easeInOutCubic(seg(1.1f, 1.7f))
            t < 3.9f -> 1f
            t < 4.5f -> 1f - 0.5f * Lessons.easeInOutCubic(seg(3.9f, 4.5f))
            t < 5.1f -> 0.5f - 0.5f * Lessons.easeInOutCubic(seg(4.5f, 5.1f))
            else -> 0f
        }
        // glow s(t)
        var s = 0f
        if (t in 2.1f..2.5f) s = seg(2.1f, 2.5f).let { it * it }
        else if (t in 2.5f..3.5f) s = (1f + 0.05f * sin(2 * PI.toFloat() * 3.1f * t) + 0.03f * sin(2 * PI.toFloat() * 5.3f * t)).coerceIn(0f, 1.08f)
        // elements: glow with s while shut; after the door opens they fade 1 → 0 (easeOutQuad, 1.2 s) back down the ramp
        val elements = if (t < 3.5f) s else if (t < 4.7f) { val x = seg(3.5f, 4.7f); 1f - (1f - (1f - x) * (1f - x)) } else 0f
        val doorOpen = door > 1f
        kilnGlow(if (doorOpen) 0f else s, doorOpen, elements = elements, lamp = t in 2.1f..4.7f)
        setDigits(when { t < 2.1f -> "20"; t < 3.5f -> "${(20 + 1220 * seg(2.1f, 3.3f)).toInt()}"; else -> "1240" })
        if (door > 0.5f && kilnSounds == 0) { kilnSounds = 1 }
        if (t >= 2.1f && kilnSounds == 1) { kilnSounds = 2; Studio.sound.kilnLatch(); Studio.sound.kilnRoar() }
        if (t >= 2.6f && !kilnSwapped) { kilnSwapped = true; chunks.material = firedMats.getValue(Studio.glaze) }
        if (t >= 3.5f && kilnSounds == 2) { kilnSounds = 3; Studio.sound.kilnDoor() }
        if (t >= 3.9f && kilnSounds == 3) { kilnSounds = 4; Studio.sound.kilnTing() }
        if (t >= 5.2f && kilnSounds == 4) { kilnSounds = 5; Studio.sound.kilnLatch() }
        Studio.firingProgress = (t / 5.2f).coerceIn(0f, 1f)
        poseKiln(door, Layout.carriageAt(u))
        if (t >= 5.2f) {
            kilnGlow(0f, doorOpen = false)
            poseKiln(0f, V3.ZERO)
            kilnStart = 0L
            val d = kilnDone; kilnDone = null; d?.invoke()
        }
    }

    private fun poseKiln(doorDeg: Float, c: V3) {
        doorPivot.place(q = axisAngle(0f, 1f, 0f, -doorDeg))
        carriage.place(p = c)
        lastDoorDeg = doorDeg; lastCarriage = c
    }

    /** Debug `fire` at a fixed time (QA S11 captures): jump the timeline to [sec] and hold. */
    fun holdKilnAt(sec: Float) { kilnStart = SystemClock.uptimeMillis() - (sec * 1000).toLong() }

    /** Focus lost while firing: jump to the result (turntable home, door shut). */
    fun finishKilnNow() { if (kilnStart != 0L) kilnStart = SystemClock.uptimeMillis() - 5300 }

    /** Debug `boundsCheck` (M7): everything inside the cylinder / clear of the kiln right now. */
    fun boundsCheck(): List<String> {
        val bad = ArrayList<String>()
        if (toolOut && ctrlHand == null && pinchHand == null) bad += Layout.toolProblems(sculptor.tip)
        for (m in chunks.meshes()) for (v in 0 until m.vertexCount) {
            val x = m.pos[v * 3]; val y = m.pos[v * 3 + 1]; val z = m.pos[v * 3 + 2]
            if (sqrt(x * x + z * z) > Layout.CLAY_R + 1e-4f || y < Layout.CLAY_Y0 - 0.003f || y > Layout.CLAY_Y1) { bad += "clay vertex ($x,$y,$z)"; break }
        }
        bad += Layout.cylinderHitsKiln(lastCarriage)
        bad += Layout.kilnProblems()
        bad += Layout.panelOverlaps()
        return bad
    }

    fun dump() = "state=${Studio.stateId()} yaw=${yawDeg().toInt()} tip=${sculptor.tip} org=(${"%.3f".format(sculptor.orgX)}, ${"%.3f".format(sculptor.orgY)}) hit=${sculptor.lastHit} " +
        "brush=${Studio.brush.id}/${Studio.size.id} mirror=${Studio.mirror} tris=${chunks.triangles} chunks=${chunks.chunksShown} undo=${sculptor.undo.size} frames=$frames drag=$drag " +
        "bench=${benchPose?.origin} ctrl=$ctrlHand pinch=$pinchHand abs=$absSrc carriage=$lastCarriage door=${lastDoorDeg.toInt()} head=${lastHead?.position}"

    fun headWorld(): V3? = lastHead?.position
}
