package io.embodit.clayforge.scene

import com.pico.spatial.core.ecs.BoundingBox
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshModel
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector2
import com.pico.spatial.core.math.Vector3
import io.embodit.clayforge.domain.ChunkMesh
import io.embodit.clayforge.domain.V3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal fun V3.sdk() = Vector3(x, y, z)

internal fun argb4(c: Int, alpha: Float = 1f) = Color4(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, alpha)

internal val Entity.tf: TransformComponent? get() = components[TransformComponent::class.java]

internal fun Entity.place(p: V3? = null, q: Quat? = null, s: Float? = null): Entity {
    val t = tf ?: return this
    if (p != null) t.setPosition(p.sdk())
    if (q != null) t.setQuaternion(q)
    if (s != null) t.setScaleVector(Vector3(s, s, s))
    return this
}

internal fun Entity.named(n: String): Entity = also { runCatching { it.setName(n) } }

internal fun isUnder(e: Entity?, ancestor: Entity): Boolean {
    var p = e
    while (p != null) { if (p == ancestor) return true; p = if (p.hasParent()) p.getParent() else null }
    return false
}

/** Rotation about a unit axis, degrees. */
internal fun axisAngle(x: Float, y: Float, z: Float, deg: Float): Quat {
    val h = Math.toRadians(deg.toDouble()).toFloat() / 2f
    val s = sin(h)
    return Quat(x * s, y * s, z * s, cos(h))
}

/** Shortest rotation taking unit [a] onto unit [b]. */
internal fun fromTo(a: V3, b: V3): Quat {
    val d = a.dot(b)
    if (d > 0.99999f) return Quat(0f, 0f, 0f, 1f)
    if (d < -0.99999f) { val ax = (if (kotlin.math.abs(a.x) < 0.9f) V3(1f, 0f, 0f) else V3(0f, 1f, 0f)).cross(a).normalized(); return Quat(ax.x, ax.y, ax.z, 0f) }
    val c = a.cross(b)
    val s = sqrt((1 + d) * 2)
    return Quat(c.x / s, c.y / s, c.z / s, s / 2)
}

internal operator fun Quat.times(o: Quat): Quat = Quat(
    w * o.x + x * o.w + y * o.z - z * o.y,
    w * o.y - x * o.z + y * o.w + z * o.x,
    w * o.z + x * o.y - y * o.x + z * o.w,
    w * o.w - x * o.x - y * o.y - z * o.z,
)

/** Resources made global and closed with the scene. */
internal class Owned {
    private val list = ArrayList<Resource>()
    fun <T : Resource> keep(r: T): T { r.toGlobal(); list += r; return r }
    fun close() { list.forEach { runCatching { it.close() } }; list.clear() }
}

internal object Mats {
    fun pbr(o: Owned, argb: Int, roughness: Float, metallic: Float = 0f, cull: Boolean = true): PhysicallyBasedMaterial =
        o.keep(PhysicallyBasedMaterial.create(BlendingMode.OPAQUE).apply {
            setBaseColor(argb4(argb)); setRoughness(roughness); setMetallic(metallic)
            if (!cull) setCullingMode(MaterialCullingMode.NONE)
        })

    fun unlit(o: Owned, argb: Int, opacity: Float = 1f, depthTest: Boolean = true): UnlitMaterial =
        o.keep(UnlitMaterial.create(if (opacity < 1f) BlendingMode.TRANSPARENT else BlendingMode.OPAQUE).apply {
            setBaseColor(argb4(argb))
            if (opacity < 1f) { setOpacity(opacity); setDepthWrite(false) }
            runCatching { setApplyToneMapping(false) }
            if (!depthTest) runCatching { setDepthTest(false) }
            setCullingMode(MaterialCullingMode.NONE)
        })
}

/** Plain mesh data → SDK model (positions / normals / optional uv). */
internal object Meshes {
    fun model(pos: FloatArray, nrm: FloatArray, idx: IntArray, uv: FloatArray? = null): Pair<MeshModel, BoundingBox> {
        val n = pos.size / 3
        val p = ArrayList<Vector3>(n); val nn = ArrayList<Vector3>(n)
        var mnx = Float.MAX_VALUE; var mny = Float.MAX_VALUE; var mnz = Float.MAX_VALUE
        var mxx = -Float.MAX_VALUE; var mxy = -Float.MAX_VALUE; var mxz = -Float.MAX_VALUE
        for (i in 0 until n) {
            val x = pos[i * 3]; val y = pos[i * 3 + 1]; val z = pos[i * 3 + 2]
            p += Vector3(x, y, z); nn += Vector3(nrm[i * 3], nrm[i * 3 + 1], nrm[i * 3 + 2])
            if (x < mnx) mnx = x; if (y < mny) mny = y; if (z < mnz) mnz = z
            if (x > mxx) mxx = x; if (y > mxy) mxy = y; if (z > mxz) mxz = z
        }
        val ix = ArrayList<Int>(idx.size).apply { idx.forEach { add(it) } }
        val uvs = uv?.let { u -> ArrayList<Vector2>(n).apply { for (i in 0 until n) add(Vector2(u[i * 2], u[i * 2 + 1])) } }
        val model = if (uvs != null) MeshModel(positions = p, triangleIndices = ix, normals = nn, uv0 = uvs) else MeshModel(positions = p, triangleIndices = ix, normals = nn)
        val b = BoundingBox(center = Vector3((mnx + mxx) / 2, (mny + mxy) / 2, (mnz + mxz) / 2),
            halfExtent = Vector3(maxOf((mxx - mnx) / 2, 0.0005f), maxOf((mxy - mny) / 2, 0.0005f), maxOf((mxz - mnz) / 2, 0.0005f)))
        return model to b
    }

    fun of(m: ChunkMesh) = model(m.pos, m.nrm, m.idx)

    fun create(pos: FloatArray, nrm: FloatArray, idx: IntArray, name: String, uv: FloatArray? = null): MeshResource {
        val (m, b) = model(pos, nrm, idx, uv)
        return MeshResource.createWithMeshModel(model = m, bounds = b, name = name)
    }
}

internal fun modelEntity(mesh: MeshResource, mat: Material, name: String, parent: Entity): ModelEntity =
    ModelEntity(mesh, mat).also { it.named(name); parent.addChild(it) }
