package io.embodit.clayforge.domain

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Small immutable vector in metres (pure Kotlin so the domain tests run on a plain JVM). */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = V3(x * s, y * s, z * s)
    operator fun unaryMinus() = V3(-x, -y, -z)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val length: Float get() = sqrt(x * x + y * y + z * z)
    fun normalized(): V3 { val l = length; return if (l < 1e-9f) V3(0f, 1f, 0f) else V3(x / l, y / l, z / l) }
    fun dist(o: V3) = (this - o).length
    fun lerp(o: V3, t: Float) = V3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
    /** Rotates about +Y by [rad] (right-handed: +x turns toward −z). */
    fun rotY(rad: Float): V3 { val c = cos(rad); val s = sin(rad); return V3(c * x + s * z, y, -s * x + c * z) }
    override fun toString() = "(%.3f, %.3f, %.3f)".format(x, y, z)

    companion object {
        val ZERO = V3(0f, 0f, 0f)
        val UP = V3(0f, 1f, 0f)
    }
}

fun clampf(v: Float, lo: Float, hi: Float) = if (v < lo) lo else if (v > hi) hi else v
