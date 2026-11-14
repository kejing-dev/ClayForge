package io.embodit.clayforge.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Signed distance helpers (negative inside), clay coordinates in metres. */
object Sdf {
    fun len(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z)
    fun sph(x: Float, y: Float, z: Float, cx: Float, cy: Float, cz: Float, r: Float) = len(x - cx, y - cy, z - cz) - r
    fun ell(x: Float, y: Float, z: Float, cx: Float, cy: Float, cz: Float, a: Float, b: Float, c: Float): Float {
        val k0 = len((x - cx) / a, (y - cy) / b, (z - cz) / c)
        val k1 = len((x - cx) / (a * a), (y - cy) / (b * b), (z - cz) / (c * c))
        return if (k1 < 1e-9f) -min(a, min(b, c)) else k0 * (k0 - 1f) / k1
    }
    fun cap(x: Float, y: Float, z: Float, ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, r: Float): Float {
        val px = x - ax; val py = y - ay; val pz = z - az
        val dx = bx - ax; val dy = by - ay; val dz = bz - az
        val h = clampf((px * dx + py * dy + pz * dz) / (dx * dx + dy * dy + dz * dz), 0f, 1f)
        return len(px - dx * h, py - dy * h, pz - dz * h) - r
    }
    fun smin(a: Float, b: Float, k: Float): Float { val h = max(k - abs(a - b), 0f) / k; return min(a, b) - h * h * k * 0.25f }
    fun ssub(a: Float, b: Float, k: Float) = -smin(-a, b, k)
    fun rbox(x: Float, y: Float, z: Float, cx: Float, cy: Float, cz: Float, bx: Float, by: Float, bz: Float, r: Float): Float {
        val qx = abs(x - cx) - bx + r; val qy = abs(y - cy) - by + r; val qz = abs(z - cz) - bz + r
        return len(max(qx, 0f), max(qy, 0f), max(qz, 0f)) + min(max(qx, max(qy, qz)), 0f) - r
    }
}

/** Starting lumps (design `startShapes`, `spatialLayout.clayStart`): the bottom sits on the turntable top. */
enum class StartShape(val id: String) {
    BALL("ball"), EGG("egg"), BLOCK("block");

    fun sdf(x: Float, y: Float, z: Float): Float = when (this) {
        // Ø 0.18 ball resting on y 0.07 (centre 0.155 as in the lessons' sample.html, design L1)
        BALL -> max(Sdf.sph(x, y, z, 0f, 0.155f, 0f, 0.09f), 0.07f - y)
        // 0.15 × 0.21 egg standing up
        EGG -> max(Sdf.ell(x, y, z, 0f, 0.175f, 0f, 0.075f, 0.105f, 0.075f), 0.07f - y)
        // 0.15 × 0.13 × 0.15 block, 2 cm round corners
        BLOCK -> Sdf.rbox(x, y, z, 0f, 0.135f, 0f, 0.075f, 0.065f, 0.075f, 0.02f)
    }

    companion object { fun of(id: String) = entries.firstOrNull { it.id == id } ?: BALL }
}

/** The six sample works (design `content.samples`): metaball recipes written down in code. */
enum class Sample(val id: String, val zh: String, val en: String, val tagZh: String, val tagEn: String, val glaze: Glaze?) {
    MUSHROOM("mushroom", "雨后的蘑菇", "Mushroom after rain", "伞盖边转着加一圈泥", "Add a ring of clay round the cap edge", Glaze.PERSIMMON),
    CAT_HEAD("catHead", "打盹的猫头", "Napping cat", "转到侧面，用钩刀往外揪耳朵", "Turn it, then hook the ears outward", null),
    SNOWMAN("snowman", "门口的雪人", "Snowman by the door", "两团一叠，抹平接缝", "Stack two balls, smooth the seam", Glaze.MOON_WHITE),
    MONSTER("monster", "床底小怪兽", "Under-bed monster", "对称开着挖眼睛", "Carve eyes with mirror on", Glaze.MIST_BLUE),
    VASE("vase", "窗台小花瓶", "Windowsill vase", "侧面挖一道花纹，转着挖", "Carve a band on the side as you turn", Glaze.CELADON),
    TURTLE("turtle", "晒背小乌龟", "Sunbathing turtle", "四只脚对称着拉", "Pull four legs with mirror on", Glaze.CELADON);

    fun sdf(x: Float, y: Float, z: Float): Float = when (this) {
        MUSHROOM -> {
            val s = Sdf.cap(x, y, z, 0f, 0.07f, 0f, 0f, 0.17f, 0f, 0.035f)
            var c = Sdf.ell(x, y, z, 0f, 0.2f, 0f, 0.11f, 0.06f, 0.11f)
            c = Sdf.ssub(c, Sdf.ell(x, y, z, 0f, 0.16f, 0f, 0.09f, 0.035f, 0.09f), 0.02f)
            Sdf.smin(s, c, 0.02f)
        }
        CAT_HEAD -> {
            var h = Sdf.ell(x, y, z, 0f, 0.17f, 0f, 0.1f, 0.09f, 0.09f)
            h = Sdf.smin(h, Sdf.cap(x, y, z, -0.055f, 0.22f, 0f, -0.075f, 0.29f, 0f, 0.018f), 0.03f)
            h = Sdf.smin(h, Sdf.cap(x, y, z, 0.055f, 0.22f, 0f, 0.075f, 0.29f, 0f, 0.018f), 0.03f)
            h = Sdf.ssub(h, Sdf.sph(x, y, z, -0.035f, 0.19f, 0.085f, 0.016f), 0.008f)
            h = Sdf.ssub(h, Sdf.sph(x, y, z, 0.035f, 0.19f, 0.085f, 0.016f), 0.008f)
            h = Sdf.smin(h, Sdf.sph(x, y, z, 0f, 0.16f, 0.09f, 0.012f), 0.01f)
            max(h, 0.08f - y)
        }
        SNOWMAN -> {
            var a = Sdf.sph(x, y, z, 0f, 0.14f, 0f, 0.075f)
            a = Sdf.smin(a, Sdf.sph(x, y, z, 0f, 0.255f, 0f, 0.052f), 0.03f)
            a = Sdf.smin(a, Sdf.cap(x, y, z, 0f, 0.255f, 0.045f, 0f, 0.25f, 0.085f, 0.009f), 0.01f)
            max(a, 0.075f - y)
        }
        MONSTER -> {
            var a = Sdf.ell(x, y, z, 0f, 0.18f, 0f, 0.095f, 0.11f, 0.085f)
            a = Sdf.smin(a, Sdf.cap(x, y, z, -0.05f, 0.27f, 0f, -0.085f, 0.33f, 0f, 0.016f), 0.02f)
            a = Sdf.smin(a, Sdf.cap(x, y, z, 0.05f, 0.27f, 0f, 0.085f, 0.33f, 0f, 0.016f), 0.02f)
            a = Sdf.ssub(a, Sdf.sph(x, y, z, -0.035f, 0.21f, 0.08f, 0.02f), 0.01f)
            a = Sdf.ssub(a, Sdf.sph(x, y, z, 0.035f, 0.21f, 0.08f, 0.02f), 0.01f)
            a = Sdf.ssub(a, Sdf.ell(x, y, z, 0f, 0.15f, 0.085f, 0.045f, 0.012f, 0.03f), 0.01f)
            max(a, 0.075f - y)
        }
        VASE -> {
            val r = sqrt(x * x + z * z)
            val prof = 0.06f + 0.035f * sin(((y - 0.07f) / 0.26f * PI * 1.1).toFloat()) - 0.02f * max(0f, (y - 0.28f) / 0.06f)
            var a = max(r - prof, max(0.075f - y, y - 0.33f))
            a = Sdf.ssub(a, max(r - (prof - 0.012f), 0.11f - y), 0.006f)
            a = Sdf.ssub(a, max(abs(y - 0.19f) - 0.006f, prof - 0.004f - r), 0.004f)
            a
        }
        TURTLE -> {
            var s = max(Sdf.ell(x, y, z, 0f, 0.1f, 0f, 0.1f, 0.06f, 0.12f), 0.085f - y)
            s = Sdf.smin(s, Sdf.sph(x, y, z, 0f, 0.12f, 0.14f, 0.032f), 0.02f)
            for ((a, b) in listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1))
                s = Sdf.smin(s, Sdf.cap(x, y, z, a * 0.07f, 0.09f, b * 0.07f, a * 0.11f, 0.085f, b * 0.1f, 0.018f), 0.015f)
            s
        }
    }

    companion object { fun of(id: String) = entries.firstOrNull { it.id == id } }
}

/** Six glazes (design `theme.palette.glazes`) + the raw clay colour. */
enum class Glaze(val id: String, val zh: String, val en: String, val argb: Int, val tagZh: String, val tagEn: String) {
    CELADON("celadon", "青瓷", "Celadon", 0xFF9DBFA8.toInt(), "雨后天青，最耐看", "Rain-washed green, never gets old"),
    MOON_WHITE("moonWhite", "月白", "Moon White", 0xFFE9ECEB.toInt(), "像刚洗过的瓷碗", "Like a freshly washed bowl"),
    JUN_RED("junRed", "钧红", "Jun Red", 0xFFA8322D.toInt(), "一窑一个样", "No two firings alike"),
    TENMOKU("tenmoku", "天目黑", "Tenmoku", 0xFF2B211C.toInt(), "黑里透一点褐", "Black with a hint of brown"),
    PERSIMMON("persimmon", "柿釉", "Persimmon", 0xFFC8682F.toInt(), "秋天的颜色", "The colour of autumn"),
    MIST_BLUE("mistBlue", "雾蓝", "Mist Blue", 0xFF6F8FAF.toInt(), "安静的蓝", "A quiet blue");

    companion object {
        const val CLAY_ARGB = 0xFFB9724F.toInt()
        fun of(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/** Debug `fill` (design S20 capture): a gyroid sponge inside a ball, ~56–60k triangles, just over the 55k gate. */
object FillShape {
    var radius = 0.104f
    var period = 0.062f
    fun sdf(x: Float, y: Float, z: Float): Float {
        val k = (2 * PI / period).toFloat()
        val yy = y - 0.175f
        val g = sin(x * k) * kotlin.math.cos(yy * k) + sin(yy * k) * kotlin.math.cos(z * k) + sin(z * k) * kotlin.math.cos(x * k)
        val sheet = abs(g) / k - 0.004f
        val ball = Sdf.sph(x, y, z, 0f, 0.175f, 0f, radius)
        return max(max(sheet, ball), 0.07f - y)
    }
}
