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
        // ring vertices are c + 1 … c + seg + 1 (v0.1.0 used absolute 1…seg: only right when the disc was the mesh's first part)
        for (s in 1..seg) if (down) m.tri(c, c + s, c + s + 1) else m.tri(c, c + s + 1, c + s)
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
    fun rod(a: V3, b: V3, r: Float, sides: Int = 12, m: PMesh = PMesh(), caps: Boolean = false): PMesh {
        val d = (b - a).normalized()
        val u = (if (kotlin.math.abs(d.y) < 0.9f) V3(0f, 1f, 0f) else V3(1f, 0f, 0f)).cross(d).normalized()
        val w = d.cross(u)
        val base = m.vertexCount
        for (s in 0..sides) { val ang = 2 * PI.toFloat() * s / sides
            val n = u * cos(ang) + w * sin(ang)
            for (p in listOf(a, b)) { val q = p + n * r; m.v(q.x, q.y, q.z, n.x, n.y, n.z, 0.5f, 0.5f) } }
        for (s in 0 until sides) { val i = base + s * 2; m.tri(i, i + 2, i + 1); m.tri(i + 1, i + 2, i + 3) }
        if (caps) capRing(m, a, -d, (0 until sides).map { base + it * 2 }, outward = false).also { capRing(m, b, d, (0 until sides).map { base + it * 2 + 1 }, outward = true) }
        return m
    }

    /** Fan cap over a ring of existing vertices (positions reused, so the mesh stays closed). */
    private fun capRing(m: PMesh, c: V3, n: V3, ring: List<Int>, outward: Boolean) {
        val ci = m.v(c.x, c.y, c.z, n.x, n.y, n.z, 0.5f, 0.5f)
        val k = ring.map { i -> m.v(m.pos[i * 3], m.pos[i * 3 + 1], m.pos[i * 3 + 2], n.x, n.y, n.z, 0.5f, 0.5f) }
        for (s in k.indices) { val a = k[s]; val b = k[(s + 1) % k.size]; if (outward) m.tri(ci, a, b) else m.tri(ci, b, a) }
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

    /**
     * Closed rounded box centred at [c], half extents [h], edge radius [r] (a sphere-swept inner box: smooth normals,
     * seam vertices of neighbouring faces coincide, so it welds watertight). Planar uv per face × [uvPerM].
     */
    fun roundedBox(c: V3, h: V3, r: Float, seg: Int = 4, uvPerM: Float = 1f, m: PMesh = PMesh()): PMesh {
        val rr = minOf(r, h.x, h.y, h.z)
        fun coords(he: Float): List<Float> {
            val out = ArrayList<Float>()
            for (k in 0..seg) out += -he + rr - rr * cos(PI.toFloat() / 2 * k / seg)
            for (k in seg downTo 0) out += he - rr + rr * cos(PI.toFloat() / 2 * k / seg)
            return out.distinct()
        }
        val cx = coords(h.x); val cy = coords(h.y); val cz = coords(h.z)
        val inner = V3(h.x - rr, h.y - rr, h.z - rr)
        // faces: axis (0 x, 1 y, 2 z), sign; (u, v) axes chosen so (u × v) = outward normal
        data class F(val axis: Int, val sign: Float, val ua: Int, val va: Int)
        val faces = listOf(F(0, 1f, 1, 2), F(0, -1f, 2, 1), F(1, 1f, 2, 0), F(1, -1f, 0, 2), F(2, 1f, 0, 1), F(2, -1f, 1, 0))
        val cs = listOf(cx, cy, cz); val hs = floatArrayOf(h.x, h.y, h.z); val ins = floatArrayOf(inner.x, inner.y, inner.z)
        for (f in faces) {
            val us = cs[f.ua]; val vs = cs[f.va]
            val base = m.vertexCount
            for (j in vs.indices) for (i in us.indices) {
                val p = FloatArray(3)
                p[f.axis] = f.sign * hs[f.axis]; p[f.ua] = us[i]; p[f.va] = vs[j]
                val q = FloatArray(3) { k -> clampf(p[k], -ins[k], ins[k]) }
                var nx = p[0] - q[0]; var ny = p[1] - q[1]; var nz = p[2] - q[2]
                val l = sqrt(nx * nx + ny * ny + nz * nz)
                if (l < 1e-9f) { nx = 0f; ny = 0f; nz = 0f; when (f.axis) { 0 -> nx = f.sign; 1 -> ny = f.sign; else -> nz = f.sign } } else { nx /= l; ny /= l; nz /= l }
                val x = q[0] + nx * rr; val y = q[1] + ny * rr; val z = q[2] + nz * rr
                m.v(c.x + x, c.y + y, c.z + z, nx, ny, nz, (us[i] + hs[f.ua]) * uvPerM, (vs[j] + hs[f.va]) * uvPerM)
            }
            val nu = us.size
            // winding: (u × v) must equal the outward normal
            val uDir = FloatArray(3).also { it[f.ua] = 1f }; val vDir = FloatArray(3).also { it[f.va] = 1f }
            val cr = V3(uDir[1] * vDir[2] - uDir[2] * vDir[1], uDir[2] * vDir[0] - uDir[0] * vDir[2], uDir[0] * vDir[1] - uDir[1] * vDir[0])
            val nrmV = FloatArray(3).also { it[f.axis] = f.sign }
            val ccw = cr.x * nrmV[0] + cr.y * nrmV[1] + cr.z * nrmV[2] > 0
            for (j in 0 until vs.size - 1) for (i in 0 until nu - 1) {
                val a = base + j * nu + i; val b = a + 1; val d = a + nu; val e = d + 1
                if (ccw) { m.tri(a, b, e); m.tri(a, e, d) } else { m.tri(a, e, b); m.tri(a, d, e) }
            }
        }
        return m
    }

    /** A helical heating coil (closed tube ends capped by tiny discs skipped: a coil is read as a tube), from a to b. */
    fun helix(a: V3, b: V3, coilR: Float, pitch: Float, tubeR: Float, sides: Int = 8, m: PMesh = PMesh()): PMesh {
        val axis = b - a; val len = axis.length; val d = axis.normalized()
        val u = (if (kotlin.math.abs(d.y) < 0.9f) V3(0f, 1f, 0f) else V3(1f, 0f, 0f)).cross(d).normalized()
        val w = d.cross(u)
        val turns = len / pitch
        val steps = maxOf(8, (turns * 6).toInt())
        fun at(t: Float): V3 { val ang = 2 * PI.toFloat() * turns * t; return a + d * (len * t) + (u * cos(ang) + w * sin(ang)) * coilR }
        val base = m.vertexCount
        for (k in 0..steps) {
            val t = k / steps.toFloat()
            val p = at(t); val tan = (at(minOf(1f, t + 1e-3f)) - at(maxOf(0f, t - 1e-3f))).normalized()
            val n1 = (if (kotlin.math.abs(tan.y) < 0.9f) V3(0f, 1f, 0f) else V3(1f, 0f, 0f)).cross(tan).normalized(); val n2 = tan.cross(n1)
            for (s in 0..sides) { val ang = 2 * PI.toFloat() * s / sides; val n = n1 * cos(ang) + n2 * sin(ang); val q = p + n * tubeR; m.v(q.x, q.y, q.z, n.x, n.y, n.z, t, s / sides.toFloat()) }
        }
        for (k in 0 until steps) for (s in 0 until sides) { val i = base + k * (sides + 1) + s; val j = i + sides + 1; m.tri(i, j, i + 1); m.tri(i + 1, j, j + 1) }
        // close both ends (M18 watertight parts)
        val t0 = (at(1e-3f) - at(0f)).normalized(); val t1 = (at(1f) - at(1f - 1e-3f)).normalized()
        capRing(m, at(0f), -t0, (0 until sides).map { base + it }, outward = false)
        capRing(m, at(1f), t1, (0 until sides).map { base + steps * (sides + 1) + it }, outward = true)
        return m
    }

    /** Welded edge check: every edge of a closed mesh is shared by exactly two triangles (positions rounded to 0.01 mm). */
    fun watertight(m: PMesh): Boolean {
        val key = HashMap<Triple<Long, Long, Long>, Int>()
        fun id(v: Int): Int { val k = Triple(Math.round(m.pos[v * 3] * 1e5), Math.round(m.pos[v * 3 + 1] * 1e5), Math.round(m.pos[v * 3 + 2] * 1e5)); return key.getOrPut(k) { key.size } }
        val edges = HashMap<Long, Int>()
        for (t in 0 until m.triangles) {
            val a = id(m.idx[t * 3]); val b = id(m.idx[t * 3 + 1]); val c = id(m.idx[t * 3 + 2])
            if (a == b || b == c || a == c) continue
            for ((p, q) in listOf(a to b, b to c, c to a)) { val e = minOf(p, q).toLong() * 1_000_000 + maxOf(p, q); edges[e] = (edges[e] ?: 0) + 1 }
        }
        return edges.values.all { it == 2 }
    }
}
