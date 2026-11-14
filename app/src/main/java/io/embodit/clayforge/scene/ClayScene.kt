package io.embodit.clayforge.scene

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.HoverEffectComponent
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import io.embodit.clayforge.Phase
import io.embodit.clayforge.Studio
import io.embodit.clayforge.domain.Brush
import io.embodit.clayforge.domain.ChunkMesh
import io.embodit.clayforge.domain.ClayField
import io.embodit.clayforge.domain.Glaze
import io.embodit.clayforge.domain.Layout
import io.embodit.clayforge.domain.OneEuro
import io.embodit.clayforge.domain.PMesh
import io.embodit.clayforge.domain.Proc
import io.embodit.clayforge.domain.Sculptor
import io.embodit.clayforge.domain.ToolSize
import io.embodit.clayforge.domain.V3
import io.embodit.clayforge.platform.CfLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** What a pointer hit resolves to (entity names). */
sealed interface Hit {
    data class Slot(val brush: Brush) : Hit
    data class Jar(val glaze: Glaze) : Hit
    data object Head : Hit
    data object Knob : Hit
    data object Clay : Hit
    data object Ring : Hit

    companion object {
        fun of(name: String): Hit? = when {
            name.startsWith("slot:") -> Brush.of(name.removePrefix("slot:"))?.let { Slot(it) }
            name.startsWith("jar:") -> Glaze.of(name.removePrefix("jar:"))?.let { Jar(it) }
            name == "head" -> Head
            name == "knob" -> Knob
            name == "clayProxy" -> Clay
            name.startsWith("ring") -> Ring
            else -> null
        }

        /** Pure twin of the parent walk for replay tests: [chain] = hit name first, then its parents (log `path=`). */
        fun resolvePath(chain: List<String>): Hit? = chain.firstNotNullOfOrNull { of(it) }
    }
}

/**
 * The studio volume's 3D content (design `spatialLayout`, `entities3d`). Volume frame: origin on the floor centre, +y up,
 * +z toward the user (the SpatialView origin is the volume centre → root at y −0.275). All animation runs in one frame
 * loop here; Compose only sends discrete events through [Studio].
 */
class ClayScene(private val context: Context) {
    val root = Entity().named("studioRoot")
    private val turn = Entity().named("turn")
    private val clayNode = Entity().named("clay")
    private val owned = Owned()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val physics = PhysicsMaterialResource()
    val field = ClayField()
    val sculptor = Sculptor(field)
    private lateinit var chunks: ClayChunks
    var frames = 0; private set
    var ready = false; private set

    // materials
    private lateinit var clayMat: Material
    private val previewMats = HashMap<Glaze, Material>()
    private val firedMats = HashMap<Glaze, Material>()
    private lateinit var ringMat: Material
    private lateinit var amberMat: Material
    private lateinit var pulseMat: Material

    // props
    private val slots = HashMap<Brush, Entity>()
    private val toolModels = HashMap<Brush, Entity>()
    private val sizeDots = ArrayList<Entity>()
    private val jars = HashMap<Glaze, Entity>()
    private val hand = Entity().named("hand")
    private val head = Entity().named("head")
    private val knob = Entity().named("knob")
    private val tipRing = Entity().named("tipRing")
    private val tipRing2 = Entity().named("tipRing2")
    private val mirrorPlane = Entity().named("mirror")
    private val bounds = Entity().named("bounds")
    private val pulse = Entity().named("pulse")
    private val ringArc = Entity().named("ringArc")
    private val clayProxy = Entity().named("clayProxy")
    private val ringHolder = Entity().named("ring")
    private val kiln = Entity().named("kiln")
    private val kilnBody = Entity().named("kilnBody")
    private val kilnLid = Entity().named("kilnLid")
    private var ventGlow: PhysicallyBasedMaterial? = null
    private var shadow: Entity? = null
    private lateinit var tray: Entity

    // tool state
    var toolOut = false; private set
    private var heldBrush: Brush? = null
    private var fly: Fly? = null
    private class Fly(val brush: Brush, val from: V3, val to: V3, val start: Long, val ms: Long, val toHand: Boolean)

    // turntable
    var yaw = 0f; private set
    private var turnAnim: Triple<Float, Float, Long>? = null
    private var spinUntil = 0L; private var spinStart = 0L; private var spinFrom = 0f
    private var boundsUntil = 0L

    // drag
    private enum class DragKind { NONE, HEAD, KNOB, TURN }
    private var drag = DragKind.NONE
    private var dragTotal = V3.ZERO
    private var dragStartMs = 0L
    private val euroX = OneEuro(1.0f, 20f); private val euroY = OneEuro(1.0f, 20f)
    private var filtered = V3.ZERO

    // glaze soak & kiln
    private var soak: Triple<List<Int>, Long, Material>? = null
    private var kilnStart = 0L
    private var kilnDone: (() -> Unit)? = null
    private var kilnSwapped = false
    private var kilnSounds = 0

    var onReady: (() -> Unit)? = null
    var onChipsChanged: (() -> Unit)? = null

    // ------------------------------------------------------------------------------------------------ build
    fun build() {
        root.place(p = V3(0f, -Layout.VOL_H / 2f, 0f))
        root.addChild(turn); turn.addChild(clayNode)
        light()
        val wood = woodTexture()
        val woodMat = owned.keep(PhysicallyBasedMaterial.create(com.pico.spatial.core.ecs.resource.BlendingMode.OPAQUE).apply {
            setBaseColor(Color4(1f, 1f, 1f, 1f)); setBaseColorTexture(wood); setRoughness(0.6f); setMetallic(0f)
            setCullingMode(com.pico.spatial.core.ecs.resource.MaterialCullingMode.NONE)
        })
        val ironMat = Mats.pbr(owned, 0xFF2E2B2C.toInt(), 0.5f, 0.7f, cull = false)
        clayMat = Mats.pbr(owned, Glaze.CLAY_ARGB, 0.85f)
        for (g in Glaze.entries) {
            previewMats[g] = Mats.pbr(owned, g.argb, 0.45f)
            firedMats[g] = Mats.pbr(owned, g.argb, 0.22f)
        }
        ringMat = Mats.unlit(owned, 0xFFFFFFFF.toInt(), 0.7f, depthTest = false)
        amberMat = Mats.unlit(owned, 0xFFE0A030.toInt())
        pulseMat = Mats.unlit(owned, 0xFFE0A27D.toInt(), 0.85f)

        // turntable (design: procedural true-round, 96 segments; Tripo turntable rejected for its proportions)
        val top = Proc.cylinder(Layout.TT_R, Layout.TT_TOP_Y - Layout.TT_THICK, Layout.TT_THICK, 96)
        modelEntity(mesh(top, "ttTop"), woodMat, "ttTop", turn)
        val neck = Proc.lathe(listOf(0.10f to 0f, 0.10f to 0.008f, 0.09f to 0.014f, 0.04f to 0.016f, 0.03f to 0.045f), 64)
        Proc.disc(0.10f, 0f, 64, down = true, m = neck)
        modelEntity(mesh(neck, "ttBase"), ironMat, "ttBase", root)
        val sh = Proc.disc(0.1f, 0f, 64)
        shadow = modelEntity(mesh(sh, "shadow"), Mats.unlit(owned, 0xFF1E1410.toInt()), "shadow", turn).also { it.place(p = V3(0f, Layout.TT_TOP_Y + 0.0005f, 0f)) }

        // tray (design: procedural rounded slab + wood grain, it must hold the four tools exactly) + side rails
        tray = Entity().named("tray").place(p = Layout.TRAY, q = axisAngle(1f, 0f, 0f, Layout.TRAY_TILT_DEG))
        root.addChild(tray)
        modelEntity(mesh(Proc.tray(0.48f, 0.012f, 0.08f), "tray"), woodMat, "trayBody", tray)

        // clay
        chunks = ClayChunks(clayNode, owned)
        chunks.material = clayMat
        sculptor.triangles = { chunks.triangles }

        // turn ring: invisible tap/drag band r 0.15–0.19, y 0.03–0.10 (12 boxes round the axis)
        // gate:allow geometry invisible collider band for turning (design entities3d.turnRing), never rendered
        val ringShapes = (0 until 12).map { i ->
            val a = 2 * PI.toFloat() * i / 12
            ShapeResource.createBox(Vector3(0.08f, 0.07f, 0.04f)).offsetByTranslationAndRotation(axisAngle(0f, 1f, 0f, -Math.toDegrees(a.toDouble()).toFloat() + 90f), Vector3(0.17f * cos(a), 0.065f, 0.17f * sin(a)))
        }
        ringHolder.components.set(CollisionComponent(ringShapes, physics)); ringHolder.components.set(InteractableComponent())
        root.addChild(ringHolder)
        turn.addChild(clayProxy)

        // tip rings, pulse, ring arc, mirror plane, bounds frame
        val ringMesh = mesh(Proc.ring(0.85f, 1.0f, 64), "tipRing")
        modelEntity(ringMesh, ringMat, "tipRingModel", tipRing); root.addChild(tipRing)
        modelEntity(ringMesh, ringMat, "tipRing2Model", tipRing2); root.addChild(tipRing2)
        modelEntity(mesh(Proc.ring(0.9f, 1.0f, 64), "pulse"), pulseMat, "pulseModel", pulse); root.addChild(pulse); pulse.enabled = false
        modelEntity(mesh(Proc.torus(0.175f, 0.004f, 96, 12, Layout.TT_TOP_Y - 0.012f), "ringArc"), pulseMat, "ringArcModel", ringArc); root.addChild(ringArc); ringArc.enabled = false
        buildMirror(); buildBounds()

        // the tool in hand: head box (from the tip 6 cm toward the user) and the knob ball, both tap/drag targets
        root.addChild(hand); root.addChild(head); root.addChild(knob)
        knob.components.set(CollisionComponent(listOf(ShapeResource.createSphere(Layout.KNOB_D / 2)), physics)); knob.components.set(InteractableComponent()); knob.components.set(HoverEffectComponent())
        // gate:allow geometry the move-knob marker: a 16 mm wooden bead at the handle end (design activeTool knob), not a prop
        modelEntity(mesh(Proc.sphere(0.008f), "knobBead"), woodMat, "knobBead", knob)
        setHeadCollider(Layout.HEAD_LEN)

        // kiln + jars + tools load from GLB (Tripo, prepared by tools/prep_assets.py)
        root.addChild(kiln); kiln.addChild(kilnBody); kiln.addChild(kilnLid); kiln.enabled = false
        scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            loadProps()
            CfLog.i("SCENE props ms=${SystemClock.elapsedRealtime() - t0}")
            // cold start: clay grows ≤ 4 chunks per frame (A9)
            Studio.onSceneReadyLoad(this@ClayScene)
            while (field.dirty.any { it }) { chunks.update(field, null, 4); delay(16) }
            Studio.triangles = chunks.triangles
            sculptor.placeTip(0f, 0.2f)
            updateProxy()
            ready = true
            CfLog.i("SCENE_READY tris=${chunks.triangles} chunks=${chunks.chunksShown} ms=${SystemClock.elapsedRealtime() - t0} worstUpload=${"%.0f".format(chunks.worstUploadMs)}")
            onReady?.invoke()
            frameLoop()
        }
    }

    private fun light() {
        val l = Entity().named("keyLight")
        // one warm key light from the upper-left front (design theme.lighting), moderate
        l.components.set(DirectionalLightComponent(Color4(1f, 0.93f, 0.85f, 1f), 1800f, false))
        l.place(q = axisAngle(0f, 1f, 0f, -30f) * axisAngle(1f, 0f, 0f, -40f))
        root.addChild(l)
    }

    private fun woodTexture(): TextureResource {
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
        return owned.keep(TextureResource.create(bmp))
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
        root.addChild(bounds); bounds.enabled = false
    }

    private suspend fun loadProps() {
        val names = Brush.entries.map { "tool_${it.id}" } + listOf("jar", "kiln_body", "kiln_lid")
        val loaded = names.map { n -> scope.async { n to runCatching { Entity.loadSuspend("asset://models/$n.glb") }.onFailure { CfLog.e("GLB $n failed", it) }.getOrNull() } }.awaitAll().toMap()
        // tools: one slot holder per tool (tap box), the model rests diagonally on the tray
        for ((i, b) in Brush.entries.withIndex()) {
            val slot = Entity().named("slot:${b.id}")
            slot.place(p = V3(Layout.TOOL_X[i], Layout.TOOL_Y, Layout.TOOL_Z))
            slot.components.set(CollisionComponent(listOf(ShapeResource.createBox(Layout.TOOL_BOX.sdk())), physics))
            slot.components.set(InteractableComponent()); slot.components.set(HoverEffectComponent())
            root.addChild(slot); slots[b] = slot
            val model = loaded["tool_${b.id}"] ?: continue
            model.named("toolModel:${b.id}")
            toolModels[b] = model
            restOnTray(b)
        }
        // three size dots (abstract indicator) shown at the active tool's empty slot
        // gate:allow geometry size indicator dots (design tools: 空位画三个小点 = 当前大小), abstract marker
        val dotMesh = mesh(Proc.sphere(0.0035f, 12, 6), "dot")
        repeat(3) { k -> sizeDots += modelEntity(dotMesh, Mats.unlit(owned, 0xFFFFFFFF.toInt()), "sizeDot$k", root).also { it.enabled = false } }
        // jars: Tripo jar + a glaze slurry disc in the mouth (only the disc's material changes per glaze)
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
                root.addChild(holder); holder.enabled = false
                jars[g] = holder
            }
        }
        loaded["kiln_body"]?.let { kilnBody.addChild(it); forEachMaterial(it) { m -> (m as? PhysicallyBasedMaterial)?.setCullingMode(com.pico.spatial.core.ecs.resource.MaterialCullingMode.NONE) } }
        loaded["kiln_lid"]?.let { kilnLid.addChild(it); forEachMaterial(it) { m -> (m as? PhysicallyBasedMaterial)?.setCullingMode(com.pico.spatial.core.ecs.resource.MaterialCullingMode.NONE) } }
        // vent glow: an emissive band just inside the lid's vent ring (visible through the holes only while firing)
        // gate:allow geometry emissive glow band inside the kiln lid (abstract light, not a prop)
        val glow = Mats.pbr(owned, 0xFF332015.toInt(), 0.9f).also { it.setEmissiveColor(Color4(0f, 0f, 0f, 1f)) }
        ventGlow = glow
        modelEntity(mesh(Proc.torus(0.150f, 0.010f, 64, 8, 0.012f), "vent"), glow, "ventGlow", kilnLid)
    }

    private fun forEachMaterial(e: Entity, f: (Material) -> Unit) {
        e.components.get<ModelComponent>()?.materials?.forEach(f)
        e.getChildren().forEach { forEachMaterial(it, f) }
    }

    // ------------------------------------------------------------------------------------------------ tools
    private fun slotPos(b: Brush) = V3(Layout.TOOL_X[b.ordinal], Layout.TOOL_Y, Layout.TOOL_Z)

    /** The tool lying on the tray: head toward the back-left, 45° (sample.html), slightly above the slot box centre. */
    private fun restOnTray(b: Brush) {
        val m = toolModels[b] ?: return
        if (m.hasParent()) m.getParent()?.removeChild(m)
        val dir = V3(0.707f, 0f, 0.707f)
        // child of the slot holder so the system hover highlight draws on it (board-input GazeTargets rule)
        val slot = slots[b]
        val local = V3(-0.04f, -0.013f, -0.03f)
        m.place(p = if (slot != null) local else slotPos(b) + local, q = fromTo(V3(1f, 0f, 0f), dir), s = 1f)
        (slot ?: root).addChild(m)
    }

    private fun toolScale(s: ToolSize) = when (s) { ToolSize.S -> 0.85f; ToolSize.M -> 1f; ToolSize.L -> 1.2f }

    /** Puts the selected tool in hand (flying from the tray) or just refreshes size dots / ring / scale. */
    fun applyTool(animate: Boolean) {
        if (Studio.guide == 2) { heldBrush?.let { restOnTray(it) }; heldBrush = null; toolOut = false; placeTool(); applyDots(); return }
        val b = Studio.brush
        sculptor.brush = b; sculptor.size = Studio.size; sculptor.mirror = Studio.mirror
        if (Studio.phase == Phase.SCULPT) {
            val prev = heldBrush
            if (prev != b) {
                if (prev != null) restOnTray(prev)
                heldBrush = b
                toolModels[b]?.let { m ->
                    if (m.hasParent()) m.getParent()?.removeChild(m)
                    hand.addChild(m); m.place(p = V3.ZERO, q = com.pico.spatial.core.math.Quat(0f, 0f, 0f, 1f), s = toolScale(Studio.size))
                }
                fly = if (animate) Fly(b, slotPos(b) + V3(0f, 0.03f, 0f), sculptor.tip, SystemClock.uptimeMillis(), 250, true) else null
            }
            toolModels[b]?.place(s = toolScale(Studio.size))
            toolOut = true
        }
        placeTool()
        applyDots()
    }

    private fun applyDots() {
        val b = heldBrush
        val show = toolOut && b != null && Studio.phase == Phase.SCULPT
        val n = Studio.size.ordinal + 1
        sizeDots.forEachIndexed { k, e ->
            e.enabled = show && k < n
            if (b != null) e.place(p = slotPos(b) + V3(-0.012f + k * 0.012f, -0.012f, 0.03f))
        }
    }

    private fun placeTool(now: Long = SystemClock.uptimeMillis()) {
        val tip = sculptor.tip
        val h = Layout.handleFor(tip.y)
        val f = fly
        val at = if (f != null) {
            val t = ((now - f.start) / f.ms.toFloat()).coerceIn(0f, 1f)
            val e = 1 - (1 - t).pow(3)
            if (t >= 1f) fly = null
            f.from.lerp(tip, e) + V3(0f, 0.03f * sin(PI.toFloat() * t), 0f)
        } else tip
        val held = toolOut && Studio.phase == Phase.SCULPT
        hand.enabled = held
        hand.place(p = at, q = fromTo(V3(1f, 0f, 0f), h))
        head.enabled = held; knob.enabled = held
        head.place(p = tip)
        knob.place(p = Layout.knobCentre(tip))
        setHeadCollider(Layout.headLenFor(tip.y))
        // tip ring on the surface, facing its normal; radius = tool radius; amber when the budget is reached
        val r = Studio.size.r
        val n = sculptor.tipNormal()
        tipRing.enabled = held && sculptor.lastHit
        tipRing.place(p = tip + n * 0.002f, q = fromTo(V3(0f, 0f, 1f), n), s = r)
        if (Studio.mirror && held) {
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
        // the head tap box reaches 6 cm toward the user from the tip (3 cm low by the turntable edge): in front of the clay proxy
        head.components.set(CollisionComponent(listOf(ShapeResource.createBox(Vector3(Layout.HEAD_D, Layout.HEAD_D, len)).offsetByTranslation(Vector3(0f, 0f, len / 2))), physics))
        head.components.set(InteractableComponent())
    }

    // ------------------------------------------------------------------------------------------------ phases
    /** Puts the volume in the look of [Studio.phase] (S05 / S10 / S11 / S12). */
    fun applyPhase() {
        val p = Studio.phase
        val sculpt = p == Phase.SCULPT
        if (!sculpt) { heldBrush?.let { restOnTray(it) }; heldBrush = null; toolOut = false }
        else if (!toolOut && Studio.guide != 2) applyTool(animate = true)
        Brush.entries.forEach { b -> slots[b]?.enabled = sculpt || p == Phase.LOADING; toolModels[b]?.enabled = sculpt || p == Phase.LOADING }
        jars.values.forEachIndexed { i, j -> j.enabled = p == Phase.GLAZE || p == Phase.FIRING }
        mirrorPlane.enabled = Studio.mirror && sculpt
        when (p) {
            Phase.SCULPT, Phase.LOADING -> { soak = null; chunks.material = clayMat }
            Phase.GLAZE -> applyGlazePreview(null)
            Phase.FIRED -> { soak = null; chunks.material = firedMats.getValue(Studio.currentGlaze ?: Studio.glaze); startSpin() }
            Phase.FIRING -> {}
        }
        placeTool(); applyDots()
        onChipsChanged?.invoke()
    }

    /** Glaze preview soaks in chunk by chunk from the bottom, 40 ms apart (design animation 选釉). */
    fun applyGlazePreview(hop: Glaze?) {
        val m = previewMats.getValue(Studio.glaze)
        soak = Triple(chunks.filledBottomUp(), SystemClock.uptimeMillis(), m)
        hop?.let { g -> jarHop = g to SystemClock.uptimeMillis() }
    }
    private var jarHop: Pair<Glaze, Long>? = null

    fun applyMirror() { sculptor.mirror = Studio.mirror; mirrorPlane.enabled = Studio.mirror && Studio.phase == Phase.SCULPT; placeTool() }

    fun fire(done: () -> Unit) {
        kiln.enabled = true; kilnStart = SystemClock.uptimeMillis(); kilnDone = done; kilnSwapped = false; kilnSounds = 0
        poseKiln(0f, 0f)
    }

    private fun poseKiln(u: Float, glow: Float) {
        val (by, bs, ly) = Layout.kilnPose(u)
        kilnBody.place(p = V3(0f, by, 0f), s = bs)
        kilnLid.place(p = V3(0f, ly, 0f))
        kilnBody.enabled = u > 0.02f; kilnLid.enabled = u > 0.02f
        ventGlow?.setEmissiveColor(Color4(1f * glow * 2.2f, 0.54f * glow * 2.2f, 0.24f * glow * 2.2f, 1f))
        lastKilnU = u
    }
    var lastKilnU = 0f; private set

    private fun startSpin() { spinStart = SystemClock.uptimeMillis(); spinUntil = spinStart + 6000; spinFrom = yaw }

    // ------------------------------------------------------------------------------------------------ turning
    fun setYaw(rad: Float) {
        yaw = rad
        sculptor.yaw = rad
        turn.place(q = axisAngle(0f, 1f, 0f, Math.toDegrees(rad.toDouble()).toFloat()))
        sculptor.refreshTip()
        placeTool()
    }

    fun turn90() {
        spinUntil = 0
        turnAnim = Triple(yaw, yaw + PI.toFloat() / 2, SystemClock.uptimeMillis())
        Studio.sound.woodTap()
        CfLog.i("TURN90 from=${Math.toDegrees(yaw.toDouble()).toInt()}")
    }

    fun yawDeg() = ((Math.toDegrees(yaw.toDouble()) % 360 + 360) % 360).toFloat()

    // ------------------------------------------------------------------------------------------------ input
    /** The pointer went down on [hit] and moved past the system's drag start. Returns true when taken. */
    fun dragStart(hit: Hit): Boolean {
        dragTotal = V3.ZERO; filtered = V3.ZERO; euroX.reset(0f); euroY.reset(0f); dragStartMs = SystemClock.uptimeMillis()
        drag = when {
            hit == Hit.Head && Studio.canSculpt && toolOut && Studio.guide != 2 -> { Studio.strokeBegin(); DragKind.HEAD }
            hit == Hit.Knob && Studio.canSculpt && toolOut -> { sculptor.pressKnob(); DragKind.KNOB }
            (hit == Hit.Clay || hit == Hit.Ring) && Studio.phase != Phase.FIRING && Studio.guide != 2 -> { spinUntil = 0; turnAnim = null; DragKind.TURN }
            else -> DragKind.NONE
        }
        CfLog.i("DRAG start hit=$hit kind=$drag")
        return drag != DragKind.NONE
    }

    /** [total] = movement since the press in volume metres (the SDK's dragAmount is cumulative). */
    fun dragMove(total: V3) {
        val now = SystemClock.uptimeMillis()
        val raw = total
        val prev = dragTotal
        dragTotal = raw
        when (drag) {
            DragKind.HEAD, DragKind.KNOB -> {
                val fx = euroX.filter(raw.x, now); val fy = euroY.filter(raw.y, now)
                val f = V3(fx, fy, raw.z)
                val d = f - filtered
                filtered = f
                val changed = sculptor.drag(d, now)
                if (changed) { Studio.sound.stroke(Studio.brush, pathM = sqrt(d.x * d.x + d.y * d.y)); }
                if (sculptor.edgeHit) boundsUntil = now + 400
                placeTool(now)
            }
            DragKind.TURN -> {
                val dx = raw.x - prev.x
                setYaw(yaw + dx / Layout.CLAY_R)
                Studio.onTurned(Math.toDegrees((dx / Layout.CLAY_R).toDouble()).toFloat())
            }
            DragKind.NONE -> {}
        }
    }

    fun dragEnd() {
        when (drag) {
            DragKind.HEAD -> {
                val wasGate = sculptor.phase == Sculptor.Phase.GATE
                Studio.strokeEnd()
                // a press that never passed the start gate is a poke (the system often sends short presses as drags)
                if (wasGate && SystemClock.uptimeMillis() - dragStartMs < 600 && dragTotal.length < 0.008f) Studio.pokeHead()
            }
            DragKind.KNOB -> { sculptor.release(); CfLog.i("MOVETIP tip=${sculptor.tip}") }
            DragKind.TURN -> CfLog.i("TURN yaw=${yawDeg().toInt()}")
            DragKind.NONE -> {}
        }
        drag = DragKind.NONE
        updateProxy()
        placeTool()
    }

    fun dropDrag() { if (drag != DragKind.NONE) dragEnd() }

    fun tap(hit: Hit?) {
        when (hit) {
            is Hit.Slot -> Studio.tapSlot(hit.brush)
            is Hit.Jar -> Studio.pickGlaze(hit.glaze)
            Hit.Head -> Studio.pokeHead()
            else -> {}
        }
    }

    /** Clay proxy for "pinch the clay and drag to turn": 4 stacked boxes inside the clay (design entities3d.clayProxy). */
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
        // contact shadow = footprint circle × 1.1 (design entities3d.contactShadow), updated on every release
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
    /** Replaces the whole field (new lump / open work), regrows all chunks over a few frames. */
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

    /** After a stroke / undo: mesh every dirty chunk now (release: "松手立刻补全"). */
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

    // ------------------------------------------------------------------------------------------------ frame loop
    private suspend fun frameLoop() {
        var lastMesh = 0L
        var lastLog = SystemClock.elapsedRealtime(); var f0 = 0
        while (scope.isActive) {
            delay(16)
            frames++
            val now = SystemClock.uptimeMillis()
            // dynamic mesh: ≤ 20 Hz, ≤ 6 chunks nearest the tip (design strokeRules.rate)
            if (now - lastMesh >= 50 && field.dirty.any { it } && !chunks.busy) {
                lastMesh = now
                chunks.update(field, sculptor.toClay(sculptor.tip), ClayChunks.PER_UPDATE)
                Studio.triangles = chunks.triangles
                val was = Studio.tooMuch
                sculptor.updateBudget(); Studio.tooMuch = sculptor.tooMuch
                if (was != Studio.tooMuch) { ringMat.let { (it as? com.pico.spatial.core.ecs.resource.UnlitMaterial)?.setBaseColor(if (Studio.tooMuch) argb4(0xFFFFB020.toInt()) else argb4(0xFFFFFFFF.toInt())) }; Studio.logState("budget") }
            }
            if (fly != null) placeTool(now)
            // turn 90 (easeInOutSine 300 ms)
            turnAnim?.let { (a, b, t0) ->
                val t = ((now - t0) / 300f).coerceIn(0f, 1f)
                setYaw(a + (b - a) * (0.5f - 0.5f * cos(PI.toFloat() * t)))
                if (t >= 1f) { turnAnim = null; Studio.sound.woodTap(); CfLog.i("TURN yaw=${yawDeg().toInt()}") }
            }
            // fired: one slow turn in 6 s
            if (spinUntil > now && drag == DragKind.NONE) setYaw(spinFrom + 2 * PI.toFloat() * ((now - spinStart) / 6000f))
            else if (spinUntil in 1..now) { spinUntil = 0; setYaw(spinFrom + 2 * PI.toFloat()) }
            // glaze soak
            soak?.let { (list, t0, m) ->
                val n = ((now - t0) / 40).toInt() + 1
                for (i in 0 until minOf(n, list.size)) chunks.setChunkMaterial(list[i], m)
                if (n >= list.size) { chunks.material = m; soak = null }
            }
            jarHop?.let { (g, t0) ->
                val t = ((now - t0) / 350f).coerceIn(0f, 1f)
                val i = g.ordinal
                jars[g]?.place(p = V3(Layout.JAR_X[i], Layout.JAR_Y - Layout.JAR_H / 2 + 0.01f * sin(PI.toFloat() * t), Layout.TOOL_Z))
                if (t >= 1f) jarHop = null
            }
            kilnTick(now)
            // bounds frame 0.4 s
            bounds.enabled = now < boundsUntil
            // guide hints
            val g = Studio.guide
            val breath = 0.5f + 0.5f * sin(now / 1200f * 2 * PI.toFloat())
            when {
                g == 2 -> { pulse.enabled = true; pulse.place(p = slotPos(Brush.ADD) + V3(0f, -0.01f, 0f), q = axisAngle(1f, 0f, 0f, -90f + Layout.TRAY_TILT_DEG), s = 0.045f + 0.006f * breath); ringArc.enabled = false }
                g == 3 -> { pulse.enabled = true; pulse.place(p = sculptor.tip + V3(0.012f * sin(now / 300f), 0f, 0.01f), q = com.pico.spatial.core.math.Quat(0f, 0f, 0f, 1f), s = 0.04f + 0.006f * breath); ringArc.enabled = false }
                g == 4 -> { pulse.enabled = false; ringArc.enabled = (now / 600) % 2 == 0L }
                else -> { pulse.enabled = false; ringArc.enabled = false }
            }
            if (now - lastLog >= 5000) {
                CfLog.i("FRAME loops=${(frames - f0) * 1000 / (now - lastLog)}/s tris=${chunks.triangles} state=${Studio.stateId()}")
                f0 = frames; lastLog = now
            }
        }
    }

    private fun kilnTick(now: Long) {
        if (kilnStart == 0L) return
        val t = now - kilnStart
        val u: Float; var glow = 0f
        when {
            t < 500 -> u = 1 - (1 - t / 500f).pow(3)
            t < 1700 -> { u = 1f; val k = (t - 500) / 1200f; glow = if (k < 0.5f) k * 2 else 1f - (k - 0.5f) * 0.8f }
            t < 2200 -> { u = 1 - ((t - 1700) / 500f).pow(3); glow = 0.6f * (1 - (t - 1700) / 500f) }
            else -> u = 0f
        }
        if (t >= 500 && kilnSounds == 0) { kilnSounds = 1; Studio.sound.kilnRoar() }
        if (t >= 1100 && !kilnSwapped) { kilnSwapped = true; chunks.material = firedMats.getValue(Studio.glaze) }
        if (t >= 1700 && kilnSounds == 1) { kilnSounds = 2; Studio.sound.kilnTing() }
        Studio.firingProgress = (t / 2600f).coerceIn(0f, 1f)
        poseKiln(u, glow)
        if (t >= 2600) {
            kiln.enabled = false; kilnStart = 0L
            val d = kilnDone; kilnDone = null; d?.invoke()
        }
    }

    /** Debug `boundsCheck` (M7): everything inside the volume / cylinder right now. */
    fun boundsCheck(): List<String> {
        val bad = ArrayList<String>()
        if (toolOut) bad += Layout.toolProblems(sculptor.tip)
        for (m in chunks.meshes()) for (v in 0 until m.vertexCount) {
            val x = m.pos[v * 3]; val y = m.pos[v * 3 + 1]; val z = m.pos[v * 3 + 2]
            if (sqrt(x * x + z * z) > Layout.CLAY_R + 1e-4f || y < Layout.CLAY_Y0 - 0.003f || y > Layout.CLAY_Y1) { bad += "clay vertex ($x,$y,$z)"; break }
        }
        if (kiln.enabled) bad += Layout.kilnProblems(lastKilnU)
        return bad
    }

    /** Probe (build step 0): scripted add strokes through the real pipeline, timing logged. */
    fun probe(seconds: Int, perUpdate: Int) = scope.launch {
        val s = sculptor
        chunks.resetStats()
        val f0 = frames
        val t0 = SystemClock.elapsedRealtime()
        s.placeTip(-0.08f, 0.16f); s.pressHead(t0)
        var updates = 0; var maxLag = 0L; var i = 0
        while (SystemClock.elapsedRealtime() - t0 < seconds * 1000L) {
            val t = (SystemClock.elapsedRealtime() - t0) / 1000f
            val tx = -0.08f + 0.16f * ((t * 0.5f) % 1f); val ty = 0.16f + 0.05f * sin(t * 6f)
            s.drag(V3(tx - s.orgX, ty - s.orgY, 0f), SystemClock.elapsedRealtime())
            val u0 = SystemClock.elapsedRealtime()
            val n = chunks.update(field, s.toClay(s.tip), perUpdate)
            maxLag = maxOf(maxLag, SystemClock.elapsedRealtime() - u0)
            if (n > 0) updates++
            i++; delay(50)
        }
        s.release()
        val ms = SystemClock.elapsedRealtime() - t0
        CfLog.i("PROBE seconds=$seconds perUpdate=$perUpdate events=$i updates=$updates (${"%.1f".format(updates * 1000f / ms)} Hz) uploads=${chunks.uploads} " +
            "meshAvg=${"%.2f".format(chunks.meshMsTotal / maxOf(1, chunks.meshedChunks))}ms/chunk uploadAvg=${"%.1f".format(chunks.uploadMsTotal / maxOf(1, chunks.uploads))}ms/chunk " +
            "worstUpload=${"%.0f".format(chunks.worstUploadMs)}ms worstUpdate=${maxLag}ms loop=${"%.1f".format((frames - f0) * 1000f / ms)}/s tris=${chunks.triangles}")
        Studio.triangles = chunks.triangles
    }

    fun dump() = "state=${Studio.stateId()} yaw=${yawDeg().toInt()} tip=${sculptor.tip} org=(${"%.3f".format(sculptor.orgX)}, ${"%.3f".format(sculptor.orgY)}) hit=${sculptor.lastHit} " +
        "brush=${Studio.brush.id}/${Studio.size.id} mirror=${Studio.mirror} tris=${chunks.triangles} chunks=${chunks.chunksShown} undo=${sculptor.undo.size} frames=$frames drag=$drag"

    fun close() { scope.cancel(); runCatching { root.destroy(true) }; owned.close() }

    companion object { const val VOL_H = Layout.VOL_H }
}
