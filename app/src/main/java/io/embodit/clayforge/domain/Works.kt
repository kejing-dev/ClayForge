package io.embodit.clayforge.domain

import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One saved work (design `dataModel.work`). [name] is empty for auto names (glaze · date, rendered in the UI language). */
data class WorkMeta(
    val id: String,
    val base: String,
    val fired: Boolean = false,
    val glaze: String? = null,
    val name: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
) {
    fun toJson(): JSONObject = JSONObject().put("v", 1).put("id", id).put("base", base).put("fired", fired).put("glaze", glaze ?: JSONObject.NULL)
        .put("name", name).put("createdAt", createdAt).put("updatedAt", updatedAt)

    companion object {
        fun fromJson(o: JSONObject): WorkMeta {
            require(o.optInt("v", 1) <= 1) { "version ${o.optInt("v")}" }
            return WorkMeta(o.getString("id"), o.optString("base", "ball"), o.optBoolean("fired"), o.optString("glaze").takeIf { it.isNotEmpty() && it != "null" },
                o.optString("name"), o.optLong("createdAt"), o.optLong("updatedAt"))
        }
    }
}

/** App preferences (design `dataModel.prefs`). */
data class Prefs(
    val lang: String = "system",
    val volume: Float = 0.65f,
    val muted: Boolean = false,
    val onboarded: Boolean = false,
    val lastWorkId: String? = null,
    val lastGlaze: String = Glaze.CELADON.id,
    val tool: String = Brush.ADD.id,
    val size: String = ToolSize.M.id,
)

/**
 * Works on disk (design `field.save`): files/works/<id>/field.bin.gz (64³ u8, gzip) + meta.json + thumb.png
 * (the PNG is written by the app layer). Writes are atomic (temp + rename). Pure java.io, JVM-testable.
 */
class WorkStore(root: File) {
    val dir = File(root, "works").apply { mkdirs() }

    fun newId(): String = UUID.randomUUID().toString()
    fun folder(id: String) = File(dir, id)
    fun thumbFile(id: String) = File(folder(id), "thumb.png")

    fun list(): List<WorkMeta> = dir.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { d ->
        runCatching { WorkMeta.fromJson(JSONObject(File(d, "meta.json").readText())) }.getOrNull()
    }.sortedByDescending { it.updatedAt }

    fun save(meta: WorkMeta, field: ClayField) {
        val d = folder(meta.id).apply { mkdirs() }
        atomic(File(d, "field.bin.gz"), Gz.pack(field.toBytes()))
        atomic(File(d, "meta.json"), meta.toJson().toString().toByteArray())
    }

    fun saveMeta(meta: WorkMeta) { atomic(File(folder(meta.id).apply { mkdirs() }, "meta.json"), meta.toJson().toString().toByteArray()) }

    /** Throws when the files are missing or broken (S19). */
    fun load(id: String, into: ClayField): WorkMeta {
        val d = folder(id)
        val meta = WorkMeta.fromJson(JSONObject(File(d, "meta.json").readText()))
        into.fromBytes(Gz.unpack(File(d, "field.bin.gz").readBytes()))
        return meta
    }

    fun meta(id: String): WorkMeta? = runCatching { WorkMeta.fromJson(JSONObject(File(folder(id), "meta.json").readText())) }.getOrNull()

    fun delete(id: String) { folder(id).deleteRecursively() }

    /** Debug `corruptSave`: break the field file of [id]. */
    fun corrupt(id: String) { File(folder(id), "field.bin.gz").writeBytes(byteArrayOf(1, 2, 3)) }

    private fun atomic(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}
