package io.embodit.clayforge.platform

import android.os.SystemClock
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerActionData
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import com.pico.spatial.tracking.hand.HandJoint
import com.pico.spatial.tracking.hand.HandTrackingProvider
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import io.embodit.clayforge.domain.V3
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.asin

/** A unit quaternion (x, y, z, w) with the two operations the trackers need. */
data class Q4(val x: Float, val y: Float, val z: Float, val w: Float) {
    fun rotate(v: V3): V3 {
        val ux = y * v.z - z * v.y; val uy = z * v.x - x * v.z; val uz = x * v.y - y * v.x
        val tx = 2 * ux; val ty = 2 * uy; val tz = 2 * uz
        return V3(v.x + w * tx + (y * tz - z * ty), v.y + w * ty + (z * tx - x * tz), v.z + w * tz + (x * ty - y * tx))
    }
    fun normalized(): Q4 { val l = kotlin.math.sqrt(x * x + y * y + z * z + w * w); return if (l < 1e-9f) Q4(0f, 0f, 0f, 1f) else Q4(x / l, y / l, z / l, w / l) }
}

/** Head pose in Stage space (HMDTrackingProvider). */
data class HeadPose(val position: V3, val rotation: Q4) {
    val forward: V3 get() = rotation.rotate(V3(0f, 0f, -1f))
    val pitchDeg: Float get() = Math.toDegrees(asin(forward.y.coerceIn(-1f, 1f).toDouble())).toFloat()
    val yawDeg: Float get() = Math.toDegrees(kotlin.math.atan2(-forward.x.toDouble(), -forward.z.toDouble())).toFloat()
}

/**
 * HMD pose (beat-pad HeadTracker). Uses (headpose-audit in ui-audit.md): placeUpright of the bench (cold start, recall),
 * the level gate before a window opens, the eye-hand depth ray origin (design container.inputs.eyeHand: the head is
 * only the ray origin, never the aim), the near-face 3D words facing the eye. Never to aim, place or select (D1).
 * A pose at the origin with the identity rotation is the tracker's "not yet" and is invalid (手语老师 v0.1.2).
 */
class HeadTracker {
    private var provider: HMDTrackingProvider? = null
    private var last: HeadPose? = null
    private var validRun = 0
    @Volatile var injected: HeadPose? = null

    fun start() {
        if (provider != null) return
        val p = HMDTrackingProvider()
        val r = runCatching { p.start() }
        CfLog.i("TRACK hmd start=${r.getOrNull() ?: r.exceptionOrNull()} support=${runCatching { p.supportState }.getOrNull()}")
        provider = p
    }

    fun stop() { runCatching { provider?.stop() }; provider = null; validRun = 0 }

    val steady: Boolean get() = validRun >= 3 || injected != null

    fun pose(): HeadPose? {
        injected?.let { return it }
        val p = provider ?: return last
        val pose = runCatching { p.latestData.hmdPose }.getOrNull() ?: return last
        val q = pose.rotation; val pos = pose.position
        if (pos.x == 0f && pos.y == 0f && pos.z == 0f && q.w == 1f && q.x == 0f && q.y == 0f && q.z == 0f) { validRun = 0; return last }
        val s = HeadPose(V3(pos.x, pos.y, pos.z), Q4(q.x, q.y, q.z, q.w).normalized())
        if (!(s.position.x.isFinite() && s.position.y.isFinite() && s.position.z.isFinite())) return last
        last = s; validRun++
        return s
    }
}

/** One controller in Stage space: grip position, rotation; forward = −z of the controller. */
data class CtrlPose(val position: V3, val rotation: Q4) {
    val forward: V3 get() = rotation.rotate(V3(0f, 0f, -1f))
}

/**
 * Both controllers (design container.inputs.controllerRay): poses from latestData on the main thread, buttons from the
 * action listener (own thread → atomics): trigger per hand, B / Y = put the tool back, thumbstick for turning / size.
 * Same approach as pruning-class ControllerInput / beat-pad Controllers.
 */
class Controllers {
    private var provider: ControllerTrackingProvider? = null
    /** Trigger edges of both sources (real listener + debug injection) → one press counter (QA r2 q2-1). */
    val edges = io.embodit.clayforge.domain.TriggerEdges()
    private val backPresses = AtomicInteger(0)
    private val stickX = AtomicInteger(0); private val stickY = AtomicInteger(0)
    private var backDown = false
    @Volatile var lastTriggerHand = 1
    var support: DataProvider.SupportState? = null; private set
    /** Debug `ctrl …`: an injected right-hand pose + trigger (emulator has no controller poses). */
    @Volatile var injected: Pair<CtrlPose, Boolean>? = null

    private val listener = ControllerTrackingProvider.ControllerActionListener { a: ControllerActionData ->
        val l = runCatching { a.left.triggerPressed }.getOrDefault(false)
        val r = runCatching { a.right.triggerPressed }.getOrDefault(false)
        if (edges.set(0, l)) lastTriggerHand = 0
        if (edges.set(1, r)) lastTriggerHand = 1
        val back = runCatching { a.right.bButtonPressed || a.left.yButtonPressed }.getOrDefault(false)
        if (back && !backDown) backPresses.incrementAndGet()
        backDown = back
        val rx = runCatching { a.right.thumbstickValue.x }.getOrDefault(0f); val lx = runCatching { a.left.thumbstickValue.x }.getOrDefault(0f)
        val ry = runCatching { a.right.thumbstickValue.y }.getOrDefault(0f); val ly = runCatching { a.left.thumbstickValue.y }.getOrDefault(0f)
        stickX.set(java.lang.Float.floatToIntBits(if (abs(rx) >= abs(lx)) rx else lx))
        stickY.set(java.lang.Float.floatToIntBits(if (abs(ry) >= abs(ly)) ry else ly))
    }

    fun start() {
        if (provider != null) return
        runCatching {
            val p = ControllerTrackingProvider()
            p.addControllerActionListener(listener)
            val r = p.start()
            support = p.supportState
            CfLog.i("TRACK controller start=$r support=$support")
            provider = p
        }.onFailure { CfLog.w("TRACK controller unavailable: $it") }
    }

    fun stop() { runCatching { provider?.removeControllerActionListener(listener); provider?.stop() }; provider = null }

    fun triggerHeld(hand: Int): Boolean = edges.held(hand)
    fun takeTriggerPresses(hand: Int) = edges.take(hand)
    /** Debug `ctrl x y z 0|1`: the right controller's pose + trigger; the trigger goes through [edges] like a real one. */
    fun inject(pose: CtrlPose?, pressed: Boolean) {
        if (pose == null) { injected = null; edges.clearInjected(1); return }
        injected = pose to pressed
        if (edges.set(1, pressed, injected = true)) lastTriggerHand = 1
    }
    fun takeBackPresses() = backPresses.getAndSet(0)
    val stickXv: Float get() = java.lang.Float.intBitsToFloat(stickX.get())
    val stickYv: Float get() = java.lang.Float.intBitsToFloat(stickY.get())
    fun injectStick(x: Float, y: Float) { stickX.set(java.lang.Float.floatToIntBits(x)); stickY.set(java.lang.Float.floatToIntBits(y)) }

    /** (left, right) poses; null when that controller does not deliver one (emulator: no data). */
    fun poses(): Pair<CtrlPose?, CtrlPose?> {
        injected?.let { return null to it.first }
        val p = provider ?: return null to null
        val s = runCatching { p.supportState }.getOrNull()
        if (s != support) { support = s; CfLog.i("TRACK controller support=$s") }
        if (s != DataProvider.SupportState.SUPPORTED) return null to null
        val d = runCatching { p.latestData }.getOrNull() ?: return null to null
        fun conv(c: com.pico.spatial.tracking.controller.ControllerPose?): CtrlPose? {
            c ?: return null
            val pos = V3(c.position.x, c.position.y, c.position.z)
            if (!(pos.x.isFinite() && pos.y.isFinite() && pos.z.isFinite())) return null
            if (pos == V3.ZERO && c.rotation.w == 1f) return null
            return CtrlPose(pos, Q4(c.rotation.x, c.rotation.y, c.rotation.z, c.rotation.w).normalized())
        }
        return conv(d.left) to conv(d.right)
    }
}

/** One hand's pinch: the point between thumb tip and index tip, pinched with hysteresis (on < 1.5 cm, off > 3.0 cm). */
data class Pinch(val point: V3, val pinched: Boolean, val indexDir: V3)

/**
 * Hand tracking (design container.inputs.handDirect): thumb / index tips → pinch point. Only when the system has hand
 * tracking on (Full Space). No prompt when it is off: controllers and eye-hand are the main paths.
 */
class Hands {
    private var provider: HandTrackingProvider? = null
    var support: DataProvider.SupportState? = null; private set
    private val held = BooleanArray(2)
    /** Debug `hand x y z pinch` (bench coordinates converted by the scene): the right hand. */
    @Volatile var injected: Pinch? = null

    fun start() {
        if (provider != null) return
        runCatching {
            val p = HandTrackingProvider()
            val r = p.start()
            support = p.supportState
            CfLog.i("TRACK hand start=$r support=$support")
            provider = p
        }.onFailure { CfLog.w("TRACK hand unavailable: $it") }
    }

    fun stop() { runCatching { provider?.stop() }; provider = null }

    val supported: Boolean get() = support == DataProvider.SupportState.SUPPORTED

    /** (left, right) pinches, null when that hand is not tracked. */
    fun pinches(): Pair<Pinch?, Pinch?> {
        injected?.let { return null to it }
        val p = provider ?: return null to null
        val s = runCatching { p.supportState }.getOrNull()
        if (s != support) { support = s; CfLog.i("TRACK hand support=$s") }
        if (s != DataProvider.SupportState.SUPPORTED) return null to null
        val d = runCatching { p.latestData }.getOrNull() ?: return null to null
        fun j(h: com.pico.spatial.tracking.hand.HandPose?, i: HandJoint.Index): V3? = runCatching {
            h?.joint(i)?.position?.let { V3(it.x, it.y, it.z) }?.takeIf { it.x.isFinite() && it.length > 1e-6f }
        }.getOrNull()
        fun one(h: com.pico.spatial.tracking.hand.HandPose?, k: Int): Pinch? {
            val t = j(h, HandJoint.Index.THUMB_TIP) ?: return null
            val i = j(h, HandJoint.Index.INDEX_TIP) ?: return null
            val prox = j(h, HandJoint.Index.INDEX_PROXIMAL) ?: j(h, HandJoint.Index.PALM) ?: i
            val gap = t.dist(i)
            held[k] = if (held[k]) gap < 0.030f else gap < 0.015f
            return Pinch((t + i) * 0.5f, held[k], (i - prox).normalized())
        }
        return one(d.left, 0) to one(d.right, 1)
    }
}

/** Monotonic ms for the trackers' callers. */
fun nowMs() = SystemClock.uptimeMillis()
