package io.embodit.clayforge.scene

import android.os.SystemClock
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import io.embodit.clayforge.domain.ChunkMesh
import io.embodit.clayforge.domain.ClayField
import io.embodit.clayforge.domain.SurfaceNets
import io.embodit.clayforge.domain.V3
import io.embodit.clayforge.platform.CfLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The clay as ≤ 64 chunk entities (design `entities3d.clayChunks`, `strokeRules.rate`): dirty chunks are meshed on a
 * worker (Surface Nets, pure Kotlin) and uploaded with createWithMeshModel / replaceWithMeshModel, nearest to the
 * tip first, at most [perUpdate] per update. Empty chunks have no entity.
 */
internal class ClayChunks(private val parent: Entity, private val owned: Owned) {
    private class Slot { var entity: ModelEntity? = null; var mesh: MeshResource? = null; var tris = 0; var data: ChunkMesh = ChunkMesh.EMPTY }
    private val slots = Array(ClayField.CHUNKS) { Slot() }
    private val nets = SurfaceNets()
    /** One update at a time: the mesher's buffers are shared (two overlapping updates corrupted them in the probe). */
    private val lock = Mutex()
    val busy: Boolean get() = lock.isLocked
    var material: Material? = null
        set(v) { field = v; if (v != null) slots.forEach { s -> s.entity?.components?.get(ModelComponent::class.java)?.let { m -> m.materials[0] = v } } }

    val triangles: Int get() = slots.sumOf { it.tris }
    val chunksShown: Int get() = slots.count { it.entity != null && it.tris > 0 }
    /** Latest CPU meshes (for export / thumbnails); index = chunk. */
    fun meshes(): List<ChunkMesh> = slots.map { it.data }

    // timing stats for the probe / perf log
    var lastMeshMs = 0.0; var lastUploadMs = 0.0; var worstUploadMs = 0.0; var uploads = 0; var meshedChunks = 0
    var uploadMsTotal = 0.0; var meshMsTotal = 0.0

    /** Per-chunk material override (glaze soaking in block by block). */
    fun setChunkMaterial(chunk: Int, m: Material) { slots[chunk].entity?.components?.get(ModelComponent::class.java)?.let { it.materials[0] = m } }

    /** Chunk indices that currently have clay, bottom row first (glaze / kiln order). */
    fun filledBottomUp(): List<Int> = (0 until ClayField.CHUNKS).filter { slots[it].tris > 0 }.sortedBy { (it / ClayField.CPA) % ClayField.CPA }

    /**
     * Meshes up to [perUpdate] dirty chunks (all when [perUpdate] ≤ 0), nearest [near] first (clay coords), and
     * uploads them. Meshing runs on Dispatchers.Default; uploads on the caller (main) thread.
     */
    suspend fun update(field: ClayField, near: V3?, perUpdate: Int): Int = lock.withLock { updateLocked(field, near, perUpdate) }

    private suspend fun updateLocked(field: ClayField, near: V3?, perUpdate: Int): Int {
        val dirty = (0 until ClayField.CHUNKS).filter { field.dirty[it] }
        if (dirty.isEmpty()) return 0
        val order = if (near == null) dirty else dirty.sortedBy { centreOf(it).dist(near) }
        val pick = if (perUpdate > 0) order.take(perUpdate) else order
        pick.forEach { field.dirty[it] = false }
        val t0 = SystemClock.elapsedRealtimeNanos()
        val built = withContext(Dispatchers.Default) { pick.map { it to nets.mesh(field, it) } }
        val t1 = SystemClock.elapsedRealtimeNanos()
        lastMeshMs = (t1 - t0) / 1e6; meshMsTotal += lastMeshMs; meshedChunks += pick.size
        var worst = 0.0
        for ((c, m) in built) {
            val u0 = SystemClock.elapsedRealtimeNanos()
            apply(c, m)
            val ms = (SystemClock.elapsedRealtimeNanos() - u0) / 1e6
            if (ms > worst) worst = ms
            uploads++
        }
        lastUploadMs = (SystemClock.elapsedRealtimeNanos() - t1) / 1e6; uploadMsTotal += lastUploadMs
        if (worst > worstUploadMs) worstUploadMs = worst
        return pick.size
    }

    private fun apply(c: Int, m: ChunkMesh) {
        val s = slots[c]
        s.data = m
        s.tris = m.triangles
        if (m.isEmpty) { s.entity?.enabled = false; return }
        val (model, bounds) = Meshes.of(m)
        val mesh = s.mesh
        if (mesh == null) {
            val r = owned.keep(MeshResource.createWithMeshModel(model = model, bounds = bounds, name = "clay:$c"))
            s.mesh = r
            s.entity = modelEntity(r, material!!, "clay:$c", parent)
        } else {
            runCatching { mesh.replaceWithMeshModel(model, bounds) }.onFailure { CfLog.e("MESH replace chunk=$c failed", it) }
            s.entity?.components?.get(ModelComponent::class.java)?.mesh = mesh
            s.entity?.enabled = true
        }
    }

    fun resetStats() { uploads = 0; meshedChunks = 0; uploadMsTotal = 0.0; meshMsTotal = 0.0; worstUploadMs = 0.0 }

    companion object {
        const val PER_UPDATE = 6
        fun centreOf(c: Int): V3 {
            val a = c % ClayField.CPA; val b = (c / ClayField.CPA) % ClayField.CPA; val k = c / (ClayField.CPA * ClayField.CPA)
            val h = ClayField.C * ClayField.H
            return V3(ClayField.X0 + (a + 0.5f) * h, ClayField.Y0 + (b + 0.5f) * h, ClayField.Z0 + (k + 0.5f) * h)
        }
    }
}
