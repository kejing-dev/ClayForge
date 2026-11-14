package io.embodit.clayforge.domain

import kotlin.math.PI
import kotlin.math.abs

/** 1€ filter (Casiez 2012) for the tip (design strokeRules ④: min cutoff 1.0 Hz, β 0.02). */
class OneEuro(private val minCutoff: Float = 1.0f, private val beta: Float = 0.02f, private val dCutoff: Float = 1.0f) {
    private var x = 0f
    private var dx = 0f
    private var lastMs = -1L

    fun reset(v: Float) { x = v; dx = 0f; lastMs = -1L }

    fun filter(v: Float, nowMs: Long): Float {
        if (lastMs < 0) { lastMs = nowMs; x = v; return v }
        val dt = ((nowMs - lastMs).coerceAtLeast(1L)) / 1000f
        lastMs = nowMs
        val ed = alpha(dCutoff, dt)
        dx += ((v - x) / dt - dx) * ed
        val cutoff = minCutoff + beta * abs(dx)
        x += (v - x) * alpha(cutoff, dt)
        return x
    }

    private fun alpha(cutoff: Float, dt: Float): Float { val tau = 1f / (2f * PI.toFloat() * cutoff); return 1f / (1f + tau / dt) }
}
