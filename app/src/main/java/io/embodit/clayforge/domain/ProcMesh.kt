package io.embodit.clayforge.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Plain mesh data with uvs (procedural props: turntable, tray, rings, rods). */
class PMesh {
    val pos = ArrayList<Float>(); val nrm = ArrayList<Float>(); val uv = ArrayList<Float>(); val idx = ArrayList<Int>()
    val vertexCount get() = pos.size / 3
    val triangles get() = idx.size / 3
    fun v(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float, w: Float): Int {
        pos += x; pos += y; pos += z; nrm += nx; nrm += ny; nrm += nz; uv += u; uv += w; return vertexCount - 1
    }
    fun tri(a: Int, b: Int, c: Int) { idx += a; idx += b; idx += c }
    fun arrays() = Triple(pos.toFloatArray(), nrm.toFloatArray(), idx.toIntArray())
    fun uvs() = uv.toFloatArray()
}

/**
 * Procedural shapes with true round cross-sections (≥ 12 segments, smooth normals; gate ring-from-boxes).
 * Used for what the design lists as procedural: the turntable (96 segments), the tray, the tip ring, the mirror rods,
 * the bounds frame, the kiln vent glow.
 */
object Proc {
    /** Surface of revolution about +y through profile points (r, y), outward normals from the profile tangent. */
    fun lathe(profile: List<Pair<Float, Float>>, seg: Int = 96, m: PMesh = PMesh()): PMesh {
        val n = profile.size
        val base = m.vertexCount
        for (i in 0 until n) {
            val (r, y) = profile[i]
            val (r0, y0) = profile[maxOf(0, i - 1)]; val (r1, y1) = profile[minOf(n - 1, i + 1)]
            var tx = r1 - r0; var ty = y1 - y0
            val l = sqrt(tx * tx + ty * ty).let { if (it < 1e-9f) 1f else it }; tx /= l; ty /= l
            val nr = ty; val ny = -tx // profile walks bottom → top / inside → out: normal points outward
            for (s in 0..seg) {
                val a = 2 * PI.toFloat() * s / seg
                val c = cos(a); val sn = sin(a)
                m.v(r * c, y, r * sn, nr * c, ny, nr * sn, s / seg.toFloat(), i / (n - 1f))
            }
        }
        for (i in 0 until n - 1) for (s in 0 until seg) {
            val a = base + i * (seg + 1) + s; val b = a + seg + 1
            m.tri(a, b, a + 1); m.tri(a + 1, b, b + 1)
        }
        return m
    }

    /** A flat disc facing +y (or −y when [down]) at height y. */
    fun disc(r: Float, y: Float, seg: Int = 96, down: Boolean = false, m: PMesh = PMesh()): PMesh {
        val ny = if (down) -1f else 1f
        val c = m.v(0f, y, 0f, 0f, ny, 0f, 0.5f, 0.5f)
        for (s in 0..seg) { val a = 2 * PI.toFloat() * s / seg; m.v(r * cos(a), y, r * sin(a), 0f, ny, 0f, 0.5f + 0.5f * cos(a), 0.5f + 0.5f * sin(a)) }
        for (s in 1..seg) if (down) m.tri(c, s, s + 1) else m.tri(c, s + 1, s)
        return m
    }

    /** A cylinder r × h standing on y0 with caps. */
    fun cylinder(r: Float, y0: Float, h: Float, seg: Int = 96, m: PMesh = PMesh()): PMesh {
        lathe(listOf(r to y0, r to y0 + h), seg, m)
        disc(r, y0 + h, seg, false, m); disc(r, y0, seg, true, m)
        return m
    }

    /** Flat annulus in the XY plane facing +z (tip ring, guide pulse); local origin = centre. */
    fun ring(inner: Float, outer: Float, seg: Int = 64, m: PMesh = PMesh()): PMesh {
        val base = m.vertexCount
        for (s in 0..seg) { val a = 2 * PI.toFloat() * s / seg; for (r in floatArrayOf(inner, outer)) m.v(r * cos(a), r * sin(a), 0f, 0f, 0f, 1f, 0.5f, 0.5f) }
        for (s in 0 until seg) { val a = base + s * 2; m.tri(a, a + 1, a + 3); m.tri(a, a + 3, a + 2) }
        return m
    }

    /** Torus about +y (ring radius R, tube radius r). */
    fun torus(R: Float, r: Float, seg: Int = 96, tube: Int = 16, y: Float = 0f, m: PMesh = PMesh()): PMesh {
        val base = m.vertexCount
        for (i in 0..seg) { val a = 2 * PI.toFloat() * i / seg
            for (j in 0..tube) { val b = 2 * PI.toFloat() * j / tube
                val cx = cos(a); val cz = sin(a); val nr = cos(b); val ny = sin(b)
                m.v((R + r * nr) * cx, y + r * ny, (R + r * nr) * cz, nr * cx, ny, nr * cz, i / seg.toFloat(), j / tube.toFloat()) } }
        for (i in 0 until seg) for (j in 0 until tube) { val a = base + i * (tube + 1) + j; val b = a + tube + 1; m.tri(a, a + 1, b); m.tri(a + 1, b + 1, b) }
        return m
    }

    /** A round rod (tube) from a to b, radius r, 12 sides. */
    fun rod(a: V3, b: V3, r: Float, sides: Int = 12, m: PMesh = PMesh()): PMesh {
        val d = (b - a).normalized()
        val u = (if (kotlin.math.abs(d.y) < 0.9f) V3(0f, 1f, 0f) else V3(1f, 0f, 0f)).cross(d).normalized()
        val w = d.cross(u)
        val base = m.vertexCount
        for (s in 0..sides) { val ang = 2 * PI.toFloat() * s / sides
            val n = u * cos(ang) + w * sin(ang)
            for (p in listOf(a, b)) { val q = p + n * r; m.v(q.x, q.y, q.z, n.x, n.y, n.z, 0.5f, 0.5f) } }
        for (s in 0 until sides) { val i = base + s * 2; m.tri(i, i + 2, i + 1); m.tri(i + 1, i + 2, i + 3) }
        return m
    }

    /** UV sphere. */
    fun sphere(r: Float, seg: Int = 24, rings: Int = 12, c: V3 = V3.ZERO, m: PMesh = PMesh()): PMesh {
        val base = m.vertexCount
        for (i in 0..rings) { val t = PI.toFloat() * i / rings
            for (j in 0..seg) { val p = 2 * PI.toFloat() * j / seg
                val nx = sin(t) * cos(p); val ny = cos(t); val nz = sin(t) * sin(p)
                m.v(c.x + r * nx, c.y + r * ny, c.z + r * nz, nx, ny, nz, j / seg.toFloat(), i / rings.toFloat()) } }
        for (i in 0 until rings) for (j in 0 until seg) { val a = base + i * (seg + 1) + j; val b = a + seg + 1; m.tri(a, a + 1, b); m.tri(a + 1, b + 1, b) }
        return m
    }

    /** Rounded tray: a slab w × h × d with 4 mm round edges along x (lathe of a stadium), uv along x for the grain. */
    fun tray(w: Float, h: Float, d: Float, m: PMesh = PMesh()): PMesh {
        // stadium cross-section in the y-z plane, extruded along x, end caps flat
        val rr = minOf(0.006f, h / 2)
        val pts = ArrayList<Pair<Float, Float>>() // (z, y)
        val corners = listOf(V3(0f, h - rr, d / 2 - rr), V3(0f, h - rr, -d / 2 + rr), V3(0f, rr, -d / 2 + rr), V3(0f, rr, d / 2 - rr))
        val startA = listOf(0.0, 0.5, 1.0, 1.5)
        for ((k, c) in corners.withIndex()) for (s in 0..4) {
            val a = (startA[k] + s / 8.0) * PI
            pts += (c.z + rr * cos(a).toFloat()) to (c.y + rr * sin(a).toFloat())
        }
        val base = m.vertexCount
        val n = pts.size
        for (i in 0 until n) {
            val (z, y) = pts[i]
            val (z0, y0) = pts[(i - 1 + n) % n]; val (z1, y1) = pts[(i + 1) % n]
            var tz = z1 - z0; var ty = y1 - y0; val l = sqrt(tz * tz + ty * ty); tz /= l; ty /= l
            val nz = -ty; val ny = tz // stadium walks clockwise in (z, y): flip to point outward
            for (xi in 0..1) { val x = if (xi == 0) -w / 2 else w / 2; m.v(x, y, z, 0f, ny, nz, x / 0.12f, i / n.toFloat()) }
        }
        for (i in 0 until n) { val a = base + i * 2; val b = base + ((i + 1) % n) * 2; m.tri(a, b, a + 1); m.tri(a + 1, b, b + 1) }
        // flat end caps
        for (xi in 0..1) {
            val x = if (xi == 0) -w / 2 else w / 2; val nx = if (xi == 0) -1f else 1f
            val c = m.v(x, h / 2, 0f, nx, 0f, 0f, 0.5f, 0.5f)
            val ring = pts.map { (z, y) -> m.v(x, y, z, nx, 0f, 0f, 0.5f, 0.5f) }
            for (i in 0 until n) { val a = ring[i]; val b = ring[(i + 1) % n]; if (xi == 0) m.tri(c, b, a) else m.tri(c, a, b) }
        }
        return m
    }
}
