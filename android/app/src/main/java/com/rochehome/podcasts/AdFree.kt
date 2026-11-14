package com.rochehome.podcasts

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * A stretch of an episode: [kind] says how it was found, joined with "+" when several ways
 * agreed: dai (copies differ), known (ad library), shared (on another show), skip (you
 * skipped it before), host (sponsor read), transcript, bumper.
 */
data class Cut(val start: Double, val end: Double, val kind: String) {
    val length get() = end - start
}

/**
 * An episode's ad breaks, from tools/podcasts/adfree.py on the Home Assistant laptop.
 * [cuts]: seconds of the original that the ad-free copy leaves out. [maybe]: likely sponsor
 * reads left in, in the ad-free copy's seconds (the original's when there are no cuts).
 * [skip]: ads the second pass found in the transcript (the host's own reads), in the same time as
 * [maybe]; the player skips them by itself. [extras]: the second pass's chapters and transcript
 * are at [AdFree.extrasUrl]. [orig]: the original's length; [at]: when it was done (unix s).
 */
data class AdInfo(val at: Long, val file: Boolean, val dur: Double, val orig: Double, val cuts: List<Cut>, val maybe: List<Cut>,
                  val skip: List<Cut> = emptyList(), val extras: Boolean = false) {
    fun toClean(t: Double): Double {
        var out = t
        for ((s, e) in cuts) if (t >= e) out -= e - s else if (t > s) out -= t - s
        return out.coerceAtLeast(0.0)
    }

    fun toOrig(t: Double): Double {
        var out = t
        for ((s, e) in cuts) if (out >= s) out += e - s
        return out
    }

    /** A sponsor read to skip by itself at [t] (seconds in the audio playing), or null. */
    fun skipAt(t: Double, clean: Boolean): Cut? =
        if (file && !clean) null else skip.firstOrNull { t >= it.start + 0.5 && t < it.end - 1 }

    /** A sponsor read flagged at [t] (seconds in the audio playing) or null. */
    fun maybeAt(t: Double, clean: Boolean): Cut? =
        if (file && !clean) null else maybe.firstOrNull { t >= it.start && t < it.end - 3 }
}

/**
 * Ad-free copies: Home Assistant serves adfree/<id>.mp3 and adfree/index.json beside the
 * podcast lists. Episodes that have a copy play it, unless Home Assistant failed to answer in
 * the last 10 minutes (away from home); then the original plays, ads and all. (Skipping the
 * cuts in the original was tried: the host inserts different ads each time, so the times
 * missed, cutting into the show.) A copy that can't be reached falls back to the original.
 * The index is kept on the phone.
 */
object AdFree {
    private val base = BuildConfig.HA_URL.takeIf { it.isNotEmpty() }?.let { "$it/local/roche-home/podcasts/adfree/" }
    private val infos = ConcurrentHashMap<String, AdInfo>()
    @Volatile private var reachedAt = 0L
    @Volatile private var failedAt = 0L
    private var file: File? = null

    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, "podcasts/adfree.json")
        runCatching { parse(file!!.readText()) }
    }

    val atHome get() = base != null && (reachedAt >= failedAt || System.currentTimeMillis() - failedAt > 10 * 60_000L)

    /** The copy couldn't be fetched: play originals for a while. */
    fun markAway() { failedAt = System.currentTimeMillis() }

    fun info(id: String): AdInfo? = infos[id]

    /** The ad-free copy is what [id] plays now. */
    fun plays(id: String) = atHome && infos[id]?.file == true

    /** The second pass's chapters and transcript (build.py's extras format), in the copy's time. */
    fun extrasUrl(id: String): String? = infos[id]?.takeIf { it.extras && base != null }?.let { "$base$id.extras.json?v=${it.at}" }

    fun url(id: String): String? = infos[id]?.takeIf { it.file && base != null }?.let { "$base$id.mp3?v=${it.at}" }

    /** Reads the index from Home Assistant (blocking; call off the main thread). False when it can't be reached. */
    fun refresh(): Boolean {
        val b = base ?: return false
        val text = runCatching {
            val c = URL(b + "index.json?t=" + System.currentTimeMillis() / 30_000).openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 6000
            c.useCaches = false
            try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
        }.getOrNull() ?: run { markAway(); return false }
        if (runCatching { parse(text) }.isFailure) return false
        reachedAt = System.currentTimeMillis()
        runCatching { file?.writeText(text) }
        return true
    }

    private fun parse(text: String) {
        val eps = JSONObject(text).optJSONObject("eps") ?: return
        fun cuts(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { i ->
            a!!.optJSONArray(i)?.let { r -> Cut(r.optDouble(0), r.optDouble(1), r.optString(2)) }
        }
        val fresh = eps.keys().asSequence().associateWith { id ->
            val j = eps.getJSONObject(id)
            AdInfo(j.optLong("at"), j.optInt("f") == 1, j.optDouble("d"), j.optDouble("o"), cuts(j.optJSONArray("cuts")).sortedBy { it.start },
                cuts(j.optJSONArray("maybe")), cuts(j.optJSONArray("skip")).sortedBy { it.start }, j.optInt("x") == 1)
        }
        infos.keys.retainAll(fresh.keys)
        infos.putAll(fresh)
    }

    /**
     * Where to start [id] (ms) from its saved place [p], [back] seconds early, in the time of the
     * audio it plays: the ad-free copy when [clean], else the original. 0 to start at the beginning.
     */
    fun startMs(id: String, p: Progress?, clean: Boolean = plays(id), back: Int = 5): Long {
        if (p == null || p.done || p.t <= 15) return 0L
        val info = infos[id]
        val t = when {
            info == null || !info.file -> p.t.toDouble()
            clean && !p.clean -> info.toClean(p.t.toDouble())
            !clean && p.clean -> info.toOrig(p.t.toDouble())
            else -> p.t.toDouble()
        }
        return ((t - back).coerceAtLeast(0.0) * 1000).toLong()
    }
}
