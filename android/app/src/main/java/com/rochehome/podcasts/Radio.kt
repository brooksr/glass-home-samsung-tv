package com.rochehome.podcasts

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A live station. [id] is "radio:soma:<channel>" or "radio:rb:<Radio Browser UUID>". */
data class Station(
    val id: String,
    val name: String,
    val url: String,
    val logo: String,
    /** Genres, comma-separated. */
    val tags: String,
    val about: String = "",
    /** HLS (.m3u8) rather than a plain Icecast/Shoutcast stream. */
    val hls: Boolean = false,
    /** "MP3 128k" and the like. */
    val quality: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("url", url).put("logo", logo).put("tags", tags)
        .put("about", about).put("hls", hls).put("quality", quality)

    companion object {
        const val PREFIX = "radio:"

        /**
         * The song from a stream's StreamTitle, readable. iHeartRadio's stations (KIIS FM and others) pack
         * tags into it: `Ariana Grande - text="Hate That I Made You Love Me" song_spot="M" MediaBaseId="…"`;
         * that becomes "Ariana Grande - Hate That I Made You Love Me". Other key="value" tags are dropped.
         */
        fun song(raw: String?): String? {
            var t = raw?.trim() ?: return null
            Regex("""\btext="([^"]*)"""").find(t)?.let { m ->
                val artist = t.substring(0, m.range.first).trim().trimEnd('-', ' ')
                t = listOf(artist, m.groupValues[1].trim()).filter { it.isNotEmpty() }.joinToString(" - ")
            }
            t = t.replace(Regex("""\s*\b\w+="[^"]*""""), "").trim().trim('-', ' ')
            return t.takeIf { it.isNotEmpty() }
        }
        fun from(j: JSONObject) = Station(j.getString("id"), j.optString("name"), j.optString("url"), j.optString("logo"),
            j.optString("tags"), j.optString("about"), j.optBoolean("hls"), j.optString("quality"))
    }
}

/**
 * Internet radio: SomaFM's channels (listener-supported, no ads) and the
 * stations in Radio Browser (radio-browser.info, a free community directory
 * of ~50,000; no key, but it asks apps to say who they are and to report
 * plays). Favorites and recently played stations stay on the phone.
 */
class Radio(context: Context) {
    private val file = File(context.filesDir, "radio.json")

    private val _favorites = MutableStateFlow<List<Station>>(emptyList())
    val favorites: StateFlow<List<Station>> = _favorites.asStateFlow()
    private val _recent = MutableStateFlow<List<Station>>(emptyList())
    val recent: StateFlow<List<Station>> = _recent.asStateFlow()

    /** Every station seen this run, so a picked ID (from Android Auto, say) can be played. */
    private val known = mutableMapOf<String, Station>()
    private var soma: List<Station>? = null
    private var local: List<Station>? = null

    init {
        runCatching { JSONObject(file.readText()) }.getOrNull()?.let { j ->
            fun list(k: String) = j.optJSONArray(k)?.let { a -> (0 until a.length()).map { Station.from(a.getJSONObject(it)) } }.orEmpty()
            _favorites.value = list("favorites")
            _recent.value = list("recent")
            (_favorites.value + _recent.value).forEach { known[it.id] = it }
        }
    }

    private fun save() {
        val j = JSONObject().put("favorites", JSONArray(_favorites.value.map { it.toJson() }))
            .put("recent", JSONArray(_recent.value.map { it.toJson() }))
        val tmp = File(file.path + ".tmp")
        tmp.writeText(j.toString())
        tmp.renameTo(file)
    }

    fun station(id: String): Station? = known[id]

    fun isFavorite(id: String) = _favorites.value.any { it.id == id }

    fun toggleFavorite(s: Station) {
        _favorites.value = if (isFavorite(s.id)) _favorites.value.filter { it.id != s.id } else _favorites.value + s
        save()
    }

    /** Remembers [s] as just played, and tells Radio Browser (its popularity counts come from these). */
    suspend fun played(s: Station) = withContext(Dispatchers.IO) {
        _recent.value = (listOf(s) + _recent.value.filter { it.id != s.id }).take(12)
        save()
        if (s.id.startsWith("radio:rb:")) browser("/json/url/" + s.id.removePrefix("radio:rb:"))
    }

    // ---------- SomaFM ----------

    /** SomaFM's channels, most listened first. */
    suspend fun somaFm(): List<Station>? = withContext(Dispatchers.IO) {
        soma?.let { return@withContext it }
        val a = text("https://somafm.com/channels.json")?.let { runCatching { JSONObject(it).getJSONArray("channels") }.getOrNull() }
            ?: return@withContext null
        (0 until a.length()).map { a.getJSONObject(it) }
            .sortedByDescending { it.optString("listeners").toIntOrNull() ?: 0 }
            .map { c ->
                val id = c.getString("id")
                // The 128k MP3 on ice2 plays for every channel (checked 2026-11-02); the .pls playlists name the same.
                Station("radio:soma:$id", c.optString("title"), "https://ice2.somafm.com/$id-128-mp3",
                    c.optString("xlimage").ifEmpty { c.optString("largeimage") }, c.optString("genre").replace('|', ','),
                    c.optString("description"), quality = "MP3 128k")
            }
            .also { list -> soma = list; list.forEach { known[it.id] = it } }
    }

    // ---------- Radio Browser ----------

    private val servers = listOf("de1", "nl1", "at1", "de2", "fi1")

    private fun browser(path: String): String? {
        for (s in servers) text("https://$s.api.radio-browser.info$path")?.let { return it }
        return null
    }

    private fun stations(path: String): List<Station>? =
        browser(path)?.let { runCatching { JSONArray(it) }.getOrNull() }?.let { parse(it) }

    private fun parse(a: JSONArray, keep: (JSONObject) -> Boolean = { true }): List<Station> {
        val seen = mutableSetOf<String>()
        return (0 until a.length()).map { a.getJSONObject(it) }
            .filter(keep)
            .filter { it.optString("url_resolved").startsWith("http") && it.optInt("lastcheckok", 1) == 1 }
            .filter { seen.add(it.optString("name").trim().lowercase()) }   // the same station listed twice
            .map { r ->
                val codec = r.optString("codec").takeIf { it.isNotEmpty() && it != "UNKNOWN" }
                val kbps = r.optInt("bitrate").takeIf { it > 0 }?.let { "${it}k" }
                Station("radio:rb:" + r.getString("stationuuid"), r.optString("name").trim(), r.optString("url_resolved"),
                    r.optString("favicon"), r.optString("tags"), hls = r.optInt("hls") == 1,
                    quality = listOfNotNull(codec, kbps).joinToString(" "))
            }
            .also { list -> list.forEach { known[it.id] = it } }
    }

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")
    private val common = "&hidebroken=true&order=clickcount&reverse=true"

    suspend fun search(term: String): List<Station>? = withContext(Dispatchers.IO) {
        stations("/json/stations/search?name=${q(term.trim())}&limit=60$common")
    }

    suspend fun genre(tag: String): List<Station>? = withContext(Dispatchers.IO) {
        stations("/json/stations/search?tag=${q(tag)}&limit=60$common")
    }

    /** The most played stations in the US. */
    suspend fun popular(): List<Station>? = withContext(Dispatchers.IO) {
        stations("/json/stations/search?countrycode=US&limit=60$common")
    }

    /**
     * Stations near home (LATITUDE/LONGITUDE in .env; within 150 km), from
     * California's list. Radio Browser's own distance search ignores the
     * distance, and much of "California" is online-only streams with no place.
     */
    suspend fun local(): List<Station>? = withContext(Dispatchers.IO) {
        local?.let { return@withContext it }
        val a = browser("/json/stations/search?state=California&countrycode=US&limit=500$common")
            ?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return@withContext null
        val lat = BuildConfig.LATITUDE.toDoubleOrNull()
        val lon = BuildConfig.LONGITUDE.toDoubleOrNull()
        parse(a) { r ->
            val la = r.optDouble("geo_lat", Double.NaN)
            val lo = r.optDouble("geo_long", Double.NaN)
            !r.optString("name").startsWith("SomaFM") &&
                (lat == null || lon == null || (!la.isNaN() && !lo.isNaN() && km(lat, lon, la, lo) < 150))
        }.take(60).also { local = it }
    }

    private fun km(a: Double, b: Double, c: Double, d: Double): Double {
        val r = Math.toRadians(c - a)
        val s = Math.toRadians(d - b)
        val h = Math.sin(r / 2).let { it * it } + Math.cos(Math.toRadians(a)) * Math.cos(Math.toRadians(c)) * Math.sin(s / 2).let { it * it }
        return 6371 * 2 * Math.asin(Math.sqrt(h))
    }

    private fun text(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 12000
        c.setRequestProperty("User-Agent", "RochePodcasts/1.0 (Android; personal)")
        try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
    }.getOrNull()
}
