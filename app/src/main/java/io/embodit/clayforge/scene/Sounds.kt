package io.embodit.clayforge.scene

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import io.embodit.clayforge.Studio
import io.embodit.clayforge.domain.Brush
import io.embodit.clayforge.platform.CfLog
import kotlin.math.pow
import kotlin.random.Random

/**
 * Event sounds only (design `assets.sound`, B24: no ambience). CC0 recordings (sound-sources.md), files peak at
 * −1 dBFS; gains below are the design's dB values. ±3 % random pitch; one instance per kind (a new one stops the
 * previous); scrape loops while carving / smoothing and fades out on release.
 */
class Sounds(context: Context) {
    private val pool = SoundPool.Builder().setMaxStreams(8)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = HashMap<String, Int>()
    private val streams = HashMap<String, Int>()
    private var lastVariant = HashMap<String, Int>()
    private var scrapeOn = false
    private var squishPath = 0f

    init {
        for (n in listOf("squish_1", "squish_2", "squish_3", "scrape", "wood_tap_1", "wood_tap_2", "wood_tap_3", "jar_clink_1", "jar_clink_2", "kiln_roar", "kiln_ting", "kiln_latch")) {
            val rid = context.resources.getIdentifier(n, "raw", context.packageName)
            if (rid == 0) CfLog.w("SOUND missing raw/$n") else ids[n] = pool.load(context, rid, 1)
        }
    }

    /**
     * +9 dB over the design's gains: at the design values the 30 s sample measured RMS −27.6 dBFS (gate ≥ −20, deviation);
     * v0.1.2 +8 → +9 dB (render −20.3 → −19.5 dBFS), the kiln roar / ting 1 dB under the design so the peak stays −1.4 dBFS.
     */
    private val boostDb = 9f

    private fun gain(db: Float): Float {
        val p = Studio.prefs
        if (p.muted) return 0f
        return (10f.pow((db + boostDb) / 20f) * p.volume / 0.65f).coerceIn(0f, 1f)
    }

    private fun play(kind: String, variants: Int, db: Float, rate: Float = 1f, loop: Int = 0): Int {
        val v = if (variants <= 1) 0 else { var x = Random.nextInt(variants); if (x == lastVariant[kind]) x = (x + 1) % variants; lastVariant[kind] = x; x }
        val name = if (variants <= 1) kind else "${kind}_${v + 1}"
        val id = ids[name] ?: return 0
        streams[kind]?.let { pool.stop(it) }
        val g = gain(db)
        if (g <= 0f) return 0
        val r = (rate * (1f + (Random.nextFloat() - 0.5f) * 0.06f)).coerceIn(0.5f, 2f)
        val s = pool.play(id, g, g, 1, loop, r)
        streams[kind] = s
        return s
    }

    /** Sculpting feedback: add = a squish every 1.5 cm of path (and on poke); carve / smooth = scrape loop. */
    /** [demo] = the lesson's tool moving by itself: the same sounds 3 dB lower (design lessons.watch.sound). */
    fun stroke(b: Brush, poke: Boolean = false, pathM: Float = 0f, demo: Boolean = false) {
        val d = if (demo) -3f else 0f
        when (b) {
            Brush.ADD -> {
                squishPath += pathM
                if (poke || squishPath >= 0.015f) { squishPath = 0f; play("squish", 3, -12f + d) }
            }
            Brush.CARVE, Brush.SMOOTH -> {
                if (poke) { play("scrape", 1, (if (b == Brush.SMOOTH) -18f else -14f) + d, if (b == Brush.SMOOTH) 0.8f else 1f); return }
                if (!scrapeOn) { scrapeOn = true; play("scrape", 1, (if (b == Brush.SMOOTH) -18f else -14f) + d, if (b == Brush.SMOOTH) 0.8f else 1f, loop = -1) }
            }
            Brush.PULL -> {}
        }
    }

    fun strokeEnd() {
        squishPath = 0f
        if (scrapeOn) { scrapeOn = false; streams["scrape"]?.let { s -> pool.setVolume(s, 0f, 0f); pool.stop(s) } }
    }

    fun woodTap() = play("wood_tap", 3, -10f)
    fun jar() = play("jar_clink", 2, -10f)
    fun kilnRoar() = play("kiln_roar", 1, -9f)
    fun kilnTing() = play("kiln_ting", 1, -9f)
    /** Door hinge / latch (design kiln sound group: open, latch「咔」). */
    fun kilnLatch() = play("kiln_latch", 1, -10f)
    fun kilnDoor() = play("wood_tap", 3, -14f, rate = 0.8f)

    fun stopAll() { strokeEnd(); streams.values.forEach { pool.stop(it) }; streams.clear(); pool.autoPause() }
    fun resume() = pool.autoResume()
    fun release() = pool.release()
}
