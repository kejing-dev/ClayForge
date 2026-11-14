package io.embodit.clayforge.domain

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Merged clay mesh in clay coordinates (metres), for export / thumbnails. */
class MergedMesh(val pos: FloatArray, val nrm: FloatArray, val idx: IntArray) {
    val triangles get() = idx.size / 3

    companion object {
        fun of(chunks: List<ChunkMesh>): MergedMesh {
            val nv = chunks.sumOf { it.vertexCount }; val ni = chunks.sumOf { it.idx.size }
            val p = FloatArray(nv * 3); val n = FloatArray(nv * 3); val ix = IntArray(ni)
            var vo = 0; var io = 0
            for (c in chunks) {
                System.arraycopy(c.pos, 0, p, vo * 3, c.pos.size); System.arraycopy(c.nrm, 0, n, vo * 3, c.nrm.size)
                for (k in c.idx.indices) ix[io + k] = c.idx[k] + vo
                vo += c.vertexCount; io += c.idx.size
            }
            return MergedMesh(p, n, ix)
        }
    }
}

/**
 * GLB 2.0 and binary STL writers (design I9 / M6). Coordinates: metres, +Y up, the clay's base at y 0 (the turntable
 * offset is removed so the piece stands on the origin, as 3D printing tools and other apps expect).
 */
object Exporter {
    private fun shifted(m: MergedMesh): FloatArray {
        val out = m.pos.copyOf()
        var minY = Float.MAX_VALUE
        for (i in 1 until out.size step 3) if (out[i] < minY) minY = out[i]
        if (minY == Float.MAX_VALUE) minY = 0f
        for (i in 1 until out.size step 3) out[i] -= minY
        return out
    }

    fun glb(m: MergedMesh, argb: Int, roughness: Float, name: String): ByteArray {
        val pos = shifted(m)
        val nv = pos.size / 3
        val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE); val max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (v in 0 until nv) for (a in 0..2) { val c = pos[v * 3 + a]; if (c < min[a]) min[a] = c; if (c > max[a]) max[a] = c }
        if (nv == 0) { min.fill(0f); max.fill(0f) }
        val posBytes = nv * 12; val nrmBytes = nv * 12; val idxBytes = m.idx.size * 4
        val bin = ByteBuffer.allocate(posBytes + nrmBytes + idxBytes).order(ByteOrder.LITTLE_ENDIAN)
        pos.forEach { bin.putFloat(it) }
        for (i in 0 until nv) {
            // renormalise (glTF requires unit normals)
            val x = m.nrm[i * 3]; val y = m.nrm[i * 3 + 1]; val z = m.nrm[i * 3 + 2]
            val l = kotlin.math.sqrt(x * x + y * y + z * z).let { if (it < 1e-9f) 1f else it }
            if (l < 1e-6f || x * x + y * y + z * z < 1e-12f) { bin.putFloat(0f); bin.putFloat(1f); bin.putFloat(0f) } else { bin.putFloat(x / l); bin.putFloat(y / l); bin.putFloat(z / l) }
        }
        m.idx.forEach { bin.putInt(it) }
        val r = ((argb shr 16) and 0xFF) / 255.0; val g = ((argb shr 8) and 0xFF) / 255.0; val b = (argb and 0xFF) / 255.0
        fun lin(c: Double) = if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        val json = """{"asset":{"version":"2.0","generator":"ClayForge"},"scene":0,"scenes":[{"nodes":[0]}],""" +
            """"nodes":[{"mesh":0,"name":${q(name)}}],""" +
            """"meshes":[{"name":${q(name)},"primitives":[{"attributes":{"POSITION":0,"NORMAL":1},"indices":2,"material":0}]}],""" +
            """"materials":[{"name":"glaze","pbrMetallicRoughness":{"baseColorFactor":[${f(lin(r))},${f(lin(g))},${f(lin(b))},1],"metallicFactor":0,"roughnessFactor":${f(roughness.toDouble())}}}],""" +
            """"buffers":[{"byteLength":${posBytes + nrmBytes + idxBytes}}],""" +
            """"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":$posBytes,"target":34962},{"buffer":0,"byteOffset":$posBytes,"byteLength":$nrmBytes,"target":34962},{"buffer":0,"byteOffset":${posBytes + nrmBytes},"byteLength":$idxBytes,"target":34963}],""" +
            """"accessors":[{"bufferView":0,"componentType":5126,"count":$nv,"type":"VEC3","min":[${f(min[0])},${f(min[1])},${f(min[2])}],"max":[${f(max[0])},${f(max[1])},${f(max[2])}]},""" +
            """{"bufferView":1,"componentType":5126,"count":$nv,"type":"VEC3"},{"bufferView":2,"componentType":5125,"count":${m.idx.size},"type":"SCALAR"}]}"""
        var jb = json.toByteArray(Charsets.UTF_8)
        if (jb.size % 4 != 0) jb += ByteArray(4 - jb.size % 4) { 0x20 }
        val binArr = bin.array().let { if (it.size % 4 == 0) it else it + ByteArray(4 - it.size % 4) }
        val total = 12 + 8 + jb.size + 8 + binArr.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67); out.putInt(2); out.putInt(total)
        out.putInt(jb.size); out.putInt(0x4E4F534A); out.put(jb)
        out.putInt(binArr.size); out.putInt(0x004E4942); out.put(binArr)
        return out.array()
    }

    /** Binary STL in millimetres (slicers assume mm). */
    fun stl(m: MergedMesh, name: String): ByteArray {
        val pos = shifted(m)
        val n = m.triangles
        val out = ByteBuffer.allocate(84 + n * 50).order(ByteOrder.LITTLE_ENDIAN)
        val head = "ClayForge $name".toByteArray(Charsets.US_ASCII).copyOf(80)
        out.put(head); out.putInt(n)
        for (t in 0 until n) {
            val a = m.idx[t * 3]; val b = m.idx[t * 3 + 1]; val c = m.idx[t * 3 + 2]
            fun p(v: Int, k: Int) = pos[v * 3 + k] * 1000f
            val ux = p(b, 0) - p(a, 0); val uy = p(b, 1) - p(a, 1); val uz = p(b, 2) - p(a, 2)
            val vx = p(c, 0) - p(a, 0); val vy = p(c, 1) - p(a, 1); val vz = p(c, 2) - p(a, 2)
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val l = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz); if (l > 0) { nx /= l; ny /= l; nz /= l }
            out.putFloat(nx); out.putFloat(ny); out.putFloat(nz)
            for (v in intArrayOf(a, b, c)) { out.putFloat(p(v, 0)); out.putFloat(p(v, 1)); out.putFloat(p(v, 2)) }
            out.putShort(0)
        }
        return out.array()
    }

    private fun f(v: Double) = "%.6f".format(java.util.Locale.US, v)
    /** Exact float (shortest round-trip repr): accessor min / max must equal the stored values bit for bit. */
    private fun f(v: Float) = v.toString()
    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/** CPU thumbnail (design `field.thumbnail`): orthographic ray march along −z, 256², point light, transparent bg. */
object Thumbnail {
    fun render(f: ClayField, argb: Int, glossy: Boolean, size: Int = 256): IntArray {
        val px = IntArray(size * size)
        val cr = ((argb shr 16) and 0xFF) / 255f; val cg = ((argb shr 8) and 0xFF) / 255f; val cb = (argb and 0xFF) / 255f
        val half = 0.165f; val cy = 0.225f
        val light = V3(-0.45f, 0.65f, 0.6f).normalized()
        for (yy in 0 until size) for (xx in 0 until size) {
            val x = -half + (xx + 0.5f) / size * 2 * half
            val y = cy + half - (yy + 0.5f) / size * 2 * half
            var z = 0.16f; var hit = false
            while (z > -0.16f) { if (f.sample(x, y, z) > 0.5f) { hit = true; break }; z -= 0.003f }
            if (!hit) continue
            var a = z; var b = z + 0.003f
            repeat(5) { val m = (a + b) / 2; if (f.sample(x, y, m) > 0.5f) a = m else b = m }
            val n = f.normalAt(V3(x, y, a))
            val diff = maxOf(0f, n.dot(light))
            var spec = 0f
            if (glossy) { val h = (light + V3(0f, 0f, 1f)).normalized(); spec = Math.pow(maxOf(0f, n.dot(h)).toDouble(), 40.0).toFloat() * 0.55f }
            val k = 0.32f + 0.75f * diff
            fun ch(c: Float) = (clampf(c * k + spec, 0f, 1f) * 255).toInt()
            px[yy * size + xx] = (0xFF shl 24) or (ch(cr) shl 16) or (ch(cg) shl 8) or ch(cb)
        }
        return px
    }
}

/** Gzip helpers for the saved field. */
object Gz {
    fun pack(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> java.util.zip.GZIPOutputStream(o).use { it.write(b) } }.toByteArray()
    fun unpack(b: ByteArray): ByteArray = java.util.zip.GZIPInputStream(b.inputStream()).use { it.readBytes() }
}
