package io.embodit.clayforge.domain

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Trigger edges per hand (QA round 2 q2-1). The real action listener and the debug injection both call [set]; a press
 * is counted on the rising edge of either source, so `ctrl … 1` goes down the same path as a real trigger. While an
 * injected source is active for a hand, its held state wins (the emulator listener keeps reporting "up").
 */
class TriggerEdges {
    private val real = arrayOf(AtomicBoolean(false), AtomicBoolean(false))
    private val inj = arrayOf(AtomicBoolean(false), AtomicBoolean(false))
    private val injActive = arrayOf(AtomicBoolean(false), AtomicBoolean(false))
    private val presses = arrayOf(AtomicInteger(0), AtomicInteger(0))

    /** One trigger sample of [hand]; [injected] = the debug source. Returns true on a rising edge. */
    fun set(hand: Int, down: Boolean, injected: Boolean = false): Boolean {
        val prev = if (injected) { injActive[hand].set(true); inj[hand].getAndSet(down) } else real[hand].getAndSet(down)
        val rising = down && !prev
        if (rising) presses[hand].incrementAndGet()
        return rising
    }

    /** The debug source for [hand] is gone (pose dropped): back to the real trigger. */
    fun clearInjected(hand: Int) { injActive[hand].set(false); inj[hand].set(false) }

    fun held(hand: Int): Boolean = if (injActive[hand].get()) inj[hand].get() else real[hand].get()
    fun take(hand: Int): Int = presses[hand].getAndSet(0)
}

/** What the controller channel does this frame (pure, so the decision is unit-tested; ClayScene.controllerFrame). */
enum class CtrlAct { NONE, PUT_BACK, SWAP, BEGIN, AWAY, MOVE, END }

object CtrlChannel {
    /**
     * [strokeOn] = a controller stroke is running; [otherStroke] = a hand stroke owns the clay; [ownSlot] / [otherSlot] =
     * the tip is at the riding tool's tray slot / another slot; [nearClay] = the tip touches the clay.
     */
    fun decide(strokeOn: Boolean, otherStroke: Boolean, held: Boolean, presses: Int, ownSlot: Boolean, otherSlot: Boolean, canSculpt: Boolean, nearClay: Boolean): CtrlAct = when {
        strokeOn && held -> CtrlAct.MOVE
        strokeOn -> CtrlAct.END
        otherStroke || !held || presses <= 0 -> CtrlAct.NONE
        ownSlot -> CtrlAct.PUT_BACK
        otherSlot -> CtrlAct.SWAP
        canSculpt && nearClay -> CtrlAct.BEGIN
        else -> CtrlAct.AWAY
    }
}
