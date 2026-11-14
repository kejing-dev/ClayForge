package io.embodit.clayforge

import io.embodit.clayforge.domain.ClayField
import io.embodit.clayforge.domain.Sample
import io.embodit.clayforge.domain.StartShape
import io.embodit.clayforge.domain.SurfaceNets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshTest {
    private fun meshAll(f: ClayField) = (0 until ClayField.CHUNKS).map { SurfaceNets().mesh(f, it) }

    @Test fun ballIsClosedAndOutward() {
        val f = ClayField().apply { fromSdf(StartShape.BALL::sdf) }
        val meshes = meshAll(f)
        val tris = meshes.sumOf { it.triangles }
        println("ball triangles=$tris chunks=${meshes.count { !it.isEmpty }}")
        assertTrue(tris in 5000..20000)
        // every triangle's face normal agrees with its vertex normals (outward winding)
        var bad = 0; var total = 0
        for (m in meshes) for (t in 0 until m.triangles) {
            val a = m.idx[t * 3]; val b = m.idx[t * 3 + 1]; val c = m.idx[t * 3 + 2]
            fun p(v: Int, k: Int) = m.pos[v * 3 + k]
            val ux = p(b, 0) - p(a, 0); val uy = p(b, 1) - p(a, 1); val uz = p(b, 2) - p(a, 2)
            val vx = p(c, 0) - p(a, 0); val vy = p(c, 1) - p(a, 1); val vz = p(c, 2) - p(a, 2)
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            val d = nx * m.nrm[a * 3] + ny * m.nrm[a * 3 + 1] + nz * m.nrm[a * 3 + 2]
            total++; if (d < 0) bad++
        }
        println("winding bad=$bad / $total")
        assertTrue(bad < total / 100)
        // bounds: inside the cylinder, sitting on the turntable top
        var minY = 1f; var maxR = 0f
        for (m in meshes) for (v in 0 until m.vertexCount) { minY = minOf(minY, m.pos[v * 3 + 1]); maxR = maxOf(maxR, kotlin.math.hypot(m.pos[v * 3], m.pos[v * 3 + 2])) }
        println("minY=$minY maxR=$maxR")
        assertEquals(0.07f, minY, 0.004f)
        assertTrue(maxR <= 0.15f)
    }

    @Test fun meshingSpeed() {
        val f = ClayField().apply { fromSdf(Sample.CAT_HEAD::sdf) }
        val sn = SurfaceNets()
        repeat(3) { (0 until 64).forEach { sn.mesh(f, it) } }
        val t0 = System.nanoTime()
        var tris = 0
        repeat(10) { tris = (0 until 64).sumOf { sn.mesh(f, it).triangles } }
        val perChunkMs = (System.nanoTime() - t0) / 1e6 / 640
        println("cat triangles=$tris per-chunk=%.3f ms".format(perChunkMs))
        assertTrue(perChunkMs < 3.0)
    }

    @Test fun allSamplesFitTheBudget() {
        for (s in Sample.entries) {
            val f = ClayField().apply { fromSdf(s::sdf) }
            val tris = meshAll(f).sumOf { it.triangles }
            println("${s.id} triangles=$tris")
            assertTrue("${s.id} $tris", tris in 3000..52000)
        }
    }
}
