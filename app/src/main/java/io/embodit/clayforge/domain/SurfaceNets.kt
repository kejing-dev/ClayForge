package io.embodit.clayforge.domain

import kotlin.math.sqrt

/** One chunk's mesh in clay coordinates: xyz positions, xyz normals, triangle indices. */
class ChunkMesh(val pos: FloatArray, val nrm: FloatArray, val idx: IntArray) {
    val vertexCount get() = pos.size / 3
    val triangles get() = idx.size / 3
    val isEmpty get() = idx.isEmpty()

    /** Axis-aligned bounds (min xyz, max xyz). */
    fun bounds(): FloatArray {
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (v in 0 until vertexCount) for (a in 0..2) { val c = pos[v * 3 + a]; if (c < b[a]) b[a] = c; if (c > b[a + 3]) b[a + 3] = c }
        return b
    }

    companion object { val EMPTY = ChunkMesh(FloatArray(0), FloatArray(0), IntArray(0)) }
}

/**
 * Surface Nets (Gibson 1998) per 16³ chunk (design `field.meshing`): one vertex per surface-crossing cube at the mean
 * of its edge crossings, one quad per sign-changing grid edge. Normals = field gradient (smooth), no UVs.
 * A chunk owns the cubes [16a, 16a + 16) and emits the quads of the edges at its cubes' min corners; it computes
 * vertices one cube further on the low side so quads across chunk borders close without seams (shared vertices are
 * bit-identical in both chunks).
 */
class SurfaceNets {
    private val n = ClayField.N
    private val span = ClayField.C + 1
    private val vid = IntArray(span * span * span)
    private val corner = FloatArray(8)
    private var pos = FloatArray(3 * 4096)
    private var nrm = FloatArray(3 * 4096)
    private var tri = IntArray(6 * 4096)
    private var nv = 0
    private var ni = 0

    fun mesh(field: ClayField, chunk: Int): ChunkMesh {
        val cpa = ClayField.CPA
        val a = chunk % cpa; val b = (chunk / cpa) % cpa; val c = chunk / (cpa * cpa)
        val i0 = a * ClayField.C; val j0 = b * ClayField.C; val k0 = c * ClayField.C
        val i1 = minOf(i0 + ClayField.C, n - 1); val j1 = minOf(j0 + ClayField.C, n - 1); val k1 = minOf(k0 + ClayField.C, n - 1)
        val f = field.f
        nv = 0; ni = 0
        vid.fill(-1)
        // vertices for cubes [i0 − 1, i1) × …
        for (k in maxOf(0, k0 - 1) until k1) for (j in maxOf(0, j0 - 1) until j1) for (i in maxOf(0, i0 - 1) until i1) {
            var mask = 0
            for (q in 0 until 8) {
                val v = f[ClayField.idx(i + (q and 1), j + ((q shr 1) and 1), k + ((q shr 2) and 1))] - 0.5f
                corner[q] = v
                if (v > 0f) mask = mask or (1 shl q)
            }
            if (mask == 0 || mask == 255) continue
            var sx = 0f; var sy = 0f; var sz = 0f; var cnt = 0
            for (e in 0 until 12) {
                val qa = EA[e]; val qb = EB[e]
                val va = corner[qa]; val vb = corner[qb]
                if ((va > 0f) == (vb > 0f)) continue
                val t = va / (va - vb)
                sx += (qa and 1) + ((qb and 1) - (qa and 1)) * t
                sy += ((qa shr 1) and 1) + (((qb shr 1) and 1) - ((qa shr 1) and 1)) * t
                sz += ((qa shr 2) and 1) + (((qb shr 2) and 1) - ((qa shr 2) and 1)) * t
                cnt++
            }
            val x = ClayField.px(i) + sx / cnt * ClayField.H
            val y = ClayField.py(j) + sy / cnt * ClayField.H
            val z = ClayField.pz(k) + sz / cnt * ClayField.H
            vid[local(i - i0, j - j0, k - k0)] = addVertex(field, x, y, z)
        }
        // quads for edges starting at the chunk's own cubes' min corners
        for (k in k0 until k1) for (j in j0 until j1) for (i in i0 until i1) {
            if (j == 0 || k == 0 || i == 0) continue
            val inside = f[ClayField.idx(i, j, k)] > 0.5f
            // x edge (i,j,k)->(i+1,j,k): cubes (i, j-1..j, k-1..k)
            if (i + 1 < n && (f[ClayField.idx(i + 1, j, k)] > 0.5f) != inside)
                quad(v(i, j - 1, k - 1, i0, j0, k0), v(i, j, k - 1, i0, j0, k0), v(i, j, k, i0, j0, k0), v(i, j - 1, k, i0, j0, k0), inside)
            if (j + 1 < n && (f[ClayField.idx(i, j + 1, k)] > 0.5f) != inside)
                quad(v(i - 1, j, k - 1, i0, j0, k0), v(i - 1, j, k, i0, j0, k0), v(i, j, k, i0, j0, k0), v(i, j, k - 1, i0, j0, k0), inside)
            if (k + 1 < n && (f[ClayField.idx(i, j, k + 1)] > 0.5f) != inside)
                quad(v(i - 1, j - 1, k, i0, j0, k0), v(i, j - 1, k, i0, j0, k0), v(i, j, k, i0, j0, k0), v(i - 1, j, k, i0, j0, k0), inside)
        }
        return ChunkMesh(pos.copyOf(nv * 3), nrm.copyOf(nv * 3), tri.copyOf(ni))
    }

    private fun local(di: Int, dj: Int, dk: Int) = ((dk + 1) * span + (dj + 1)) * span + (di + 1)
    private fun v(i: Int, j: Int, k: Int, i0: Int, j0: Int, k0: Int) = vid[local(i - i0, j - j0, k - k0)]

    private fun addVertex(field: ClayField, x: Float, y: Float, z: Float): Int {
        if ((nv + 1) * 3 > pos.size) { pos = pos.copyOf(pos.size * 2); nrm = nrm.copyOf(nrm.size * 2) }
        val e = ClayField.H * 0.7f
        val gx = field.sample(x - e, y, z) - field.sample(x + e, y, z)
        val gy = field.sample(x, y - e, z) - field.sample(x, y + e, z)
        val gz = field.sample(x, y, z - e) - field.sample(x, y, z + e)
        val l = sqrt(gx * gx + gy * gy + gz * gz).let { if (it < 1e-9f) 1f else it }
        val o = nv * 3
        pos[o] = x; pos[o + 1] = y; pos[o + 2] = z
        nrm[o] = gx / l; nrm[o + 1] = gy / l; nrm[o + 2] = gz / l
        return nv++
    }

    private fun quad(q0: Int, q1: Int, q2: Int, q3: Int, flip: Boolean) {
        if (q0 < 0 || q1 < 0 || q2 < 0 || q3 < 0) return
        if (ni + 6 > tri.size) tri = tri.copyOf(tri.size * 2)
        if (flip) { tri[ni++] = q0; tri[ni++] = q1; tri[ni++] = q2; tri[ni++] = q0; tri[ni++] = q2; tri[ni++] = q3 }
        else { tri[ni++] = q0; tri[ni++] = q2; tri[ni++] = q1; tri[ni++] = q0; tri[ni++] = q3; tri[ni++] = q2 }
    }

    companion object {
        // the 12 cube edges as corner pairs (corner q = x | y << 1 | z << 2)
        private val EA = intArrayOf(0, 2, 4, 6, 0, 1, 4, 5, 0, 1, 2, 3)
        private val EB = intArrayOf(1, 3, 5, 7, 2, 3, 6, 7, 4, 5, 6, 7)
    }
}
