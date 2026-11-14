package io.embodit.clayforge.domain

import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The clay: a 64³ density field (design `field`): 5 mm per cell, values 0…1, surface at 0.5.
 * Coordinates are "clay" coordinates = turntable local, metres: axis at x = z = 0, y measured from the volume floor
 * (so the turntable top is y 0.07). Point (i, j, k) sits at (X0 + i·H, Y0 + j·H, Z0 + k·H).
 *
 * Every point on the outer layer of the grid and every point outside the working cylinder (r ≤ 0.145, y ≤ 0.38,
 * i.e. the design cylinder r 0.15 inset one cell) is kept at 0, so the surface always closes inside the grid and
 * the clay never leaves the cylinder (design `spatialLayout.clayRegion`).
 */
class ClayField {
    val f = FloatArray(N * N * N)
    /** Chunks whose mesh is out of date (64 = 4 × 4 × 4). */
    val dirty = BooleanArray(CHUNKS)
    /** 16³ point blocks touched since [clearTouched] (undo granularity). */
    val touched = BooleanArray(CHUNKS)

    fun copyFrom(o: ClayField) { System.arraycopy(o.f, 0, f, 0, f.size) }
    fun copy(): ClayField = ClayField().also { it.copyFrom(this) }
    fun markAllDirty() { dirty.fill(true) }
    fun clearTouched() { touched.fill(false) }

    /** Trilinear sample at clay coordinates (0 outside the grid). */
    fun sample(x: Float, y: Float, z: Float): Float {
        val fx = (x - X0) / H; val fy = (y - Y0) / H; val fz = (z - Z0) / H
        if (fx < 0f || fy < 0f || fz < 0f || fx >= N - 1 || fy >= N - 1 || fz >= N - 1) return 0f
        val i = fx.toInt(); val j = fy.toInt(); val k = fz.toInt()
        val u = fx - i; val v = fy - j; val w = fz - k
        val b = idx(i, j, k)
        val c000 = f[b]; val c100 = f[b + 1]; val c010 = f[b + N]; val c110 = f[b + N + 1]
        val c001 = f[b + NN]; val c101 = f[b + NN + 1]; val c011 = f[b + NN + N]; val c111 = f[b + NN + N + 1]
        val x00 = c000 + (c100 - c000) * u; val x10 = c010 + (c110 - c010) * u
        val x01 = c001 + (c101 - c001) * u; val x11 = c011 + (c111 - c011) * u
        val y0 = x00 + (x10 - x00) * v; val y1 = x01 + (x11 - x01) * v
        return y0 + (y1 - y0) * w
    }

    /** Outward surface normal at a clay point (minus the field gradient). */
    fun normalAt(p: V3): V3 {
        val e = H
        val gx = sample(p.x - e, p.y, p.z) - sample(p.x + e, p.y, p.z)
        val gy = sample(p.x, p.y - e, p.z) - sample(p.x, p.y + e, p.z)
        val gz = sample(p.x, p.y, p.z - e) - sample(p.x, p.y, p.z + e)
        val l = sqrt(gx * gx + gy * gy + gz * gz)
        return if (l < 1e-6f) V3(0f, 0f, 1f) else V3(gx / l, gy / l, gz / l)
    }

    /** Marks the chunks whose mesh reads point (i, j, k) and the block it lives in. */
    fun touch(i: Int, j: Int, k: Int) {
        touched[blockOf(i, j, k)] = true
        val ci0 = chunkLo(i); val ci1 = chunkHi(i)
        val cj0 = chunkLo(j); val cj1 = chunkHi(j)
        val ck0 = chunkLo(k); val ck1 = chunkHi(k)
        for (c in ck0..ck1) for (b in cj0..cj1) for (a in ci0..ci1) dirty[chunkIndex(a, b, c)] = true
    }

    /** Marks every chunk / block a box of points touches (cheaper than per point). */
    fun touchBox(i0: Int, i1: Int, j0: Int, j1: Int, k0: Int, k1: Int) {
        for (c in chunkLo(k0)..chunkHi(k1)) for (b in chunkLo(j0)..chunkHi(j1)) for (a in chunkLo(i0)..chunkHi(i1)) dirty[chunkIndex(a, b, c)] = true
        for (c in k0 / C..k1 / C) for (b in j0 / C..j1 / C) for (a in i0 / C..i1 / C) touched[chunkIndex(a, b, c)] = true
    }

    /** Fills from a signed distance function (negative inside), with the cylinder and the turntable top applied. */
    fun fromSdf(sd: (Float, Float, Float) -> Float) {
        for (k in 0 until N) for (j in 0 until N) for (i in 0 until N) {
            val id = idx(i, j, k)
            if (!MASK[id]) { f[id] = 0f; continue }
            val x = px(i); val y = py(j); val z = pz(k)
            var d = sd(x, y, z)
            d = maxOf(d, sqrt(x * x + z * z) - R_MAX, Y_FLOOR - y)
            f[id] = 1f / (1f + exp(d / (H * 0.55f)))
        }
        markAllDirty()
    }

    /** 8-bit quantised copy (save format, design `field.save`). */
    fun toBytes(): ByteArray = ByteArray(f.size) { (clampf(f[it], 0f, 1f) * 255f + 0.5f).toInt().toByte() }

    fun fromBytes(b: ByteArray) {
        require(b.size == f.size) { "field size ${b.size}" }
        for (id in f.indices) f[id] = if (MASK[id]) (b[id].toInt() and 0xFF) / 255f else 0f
        markAllDirty()
    }

    /** Solid volume in cubic metres (field ≥ 0.5 counted as full cells), for the debug dump. */
    fun solidCells(): Int { var n = 0; for (v in f) if (v >= 0.5f) n++; return n }

    companion object {
        const val N = 64
        const val NN = N * N
        const val H = 0.005f
        const val X0 = -0.1575f
        const val Y0 = 0.065f
        const val Z0 = -0.1575f
        /** Design cylinder r 0.15, inset one cell. */
        const val R_MAX = 0.145f
        const val R_DESIGN = 0.15f
        /** Turntable top (clay sits on it) and the highest clay point the grid allows. */
        const val Y_FLOOR = 0.0675f
        const val Y_TOP = 0.38f
        /** Chunk size in cubes; 63 cubes per axis → 4 chunks (the last one has 15). */
        const val C = 16
        const val CPA = 4
        const val CHUNKS = CPA * CPA * CPA

        fun idx(i: Int, j: Int, k: Int) = (k * N + j) * N + i
        fun px(i: Int) = X0 + i * H
        fun py(j: Int) = Y0 + j * H
        fun pz(k: Int) = Z0 + k * H
        fun chunkIndex(a: Int, b: Int, c: Int) = (c * CPA + b) * CPA + a
        fun blockOf(i: Int, j: Int, k: Int) = chunkIndex(minOf(i / C, CPA - 1), minOf(j / C, CPA - 1), minOf(k / C, CPA - 1))
        /** Chunk a reads points [16a − 1, 16a + 16]; these are the chunks that read point p. */
        private fun chunkLo(p: Int) = maxOf(0, Math.floorDiv(p - 1, C))
        private fun chunkHi(p: Int) = minOf(CPA - 1, (p + 1) / C)

        /** Grid index range (inclusive) of points within [r] of a coordinate along one axis. */
        fun rangeX(c: Float, r: Float) = maxOf(1, floor((c - r - X0) / H).toInt()) to minOf(N - 2, floor((c + r - X0) / H).toInt() + 1)
        fun rangeY(c: Float, r: Float) = maxOf(1, floor((c - r - Y0) / H).toInt()) to minOf(N - 2, floor((c + r - Y0) / H).toInt() + 1)
        fun rangeZ(c: Float, r: Float) = maxOf(1, floor((c - r - Z0) / H).toInt()) to minOf(N - 2, floor((c + r - Z0) / H).toInt() + 1)

        /** true where the clay may exist: inside the cylinder and not on the grid's outer layer. */
        val MASK: BooleanArray = BooleanArray(N * N * N).also { m ->
            for (k in 1 until N - 1) for (j in 1 until N - 1) for (i in 1 until N - 1) {
                val x = px(i); val z = pz(k); val y = py(j)
                m[idx(i, j, k)] = x * x + z * z <= R_MAX * R_MAX && y <= Y_TOP && y >= Y0 + H * 0.5f
            }
        }
    }
}
