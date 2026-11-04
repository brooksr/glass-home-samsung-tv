package com.rochehome.podcasts

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale

data class Show(
    val id: String,
    val title: String,
    val author: String,
    val feed: String,
    /** Cover art: Apple's copy (300 px), else the one Home Assistant serves. */
    val art: String,
    val newest: Long,
    val category: String = "Other",
    /** Chapters are inside the MP3 (ID3 CHAP frames). */
    val id3Chapters: Boolean = false,
    /** Subscribed on this phone from Discover (not in Home Assistant's list). */
    val added: Boolean = false,
    /** Apple's genre IDs (Discover only). */
    val genres: List<String> = emptyList(),
)

data class Episode(
    val id: String,
    val showId: String,
    val title: String,
    /** Unix seconds. */
    val date: Long,
    /** Seconds; 0 when the feed doesn't say. */
    val duration: Int,
    val url: String,
    val desc: String,
    /** Podcasting 2.0 chapters (JSON), if the feed links them. */
    val chaptersUrl: String = "",
    /** The feed's transcript and its kind: vtt, srt, json, txt or html. */
    val transcriptUrl: String = "",
    val transcriptType: String = "",
    /** The same episode on YouTube (a Watch button), if the show's channel has it. */
    val videoId: String = "",
    /** On YouTube only: no audio here; opens in YouTube. */
    val youtubeOnly: Boolean = false,
)

/** An audiobook from LibriVox: [show] carries its title, author, cover and shelf (as category); its parts play in order. */
data class Book(val show: Show, val desc: String, val secs: Int, val chapters: List<Episode>) {
    val id get() = show.id
}

data class Chapter(val start: Int, val title: String)
data class Line(val start: Int, val speaker: String, val text: String)
/** [estimated]: an audiobook's text, its timing spread over the part by paragraph length rather than known. */
data class Extras(val chapters: List<Chapter>, val lines: List<Line>, val timed: Boolean, val estimated: Boolean = false)

/** Shared with the TV through Home Assistant as [t, dur, done, at, showId]. */
data class Progress(val t: Int, val dur: Int, val done: Boolean, val at: Long, val showId: String) {
    fun toJson() = JSONArray().put(t).put(dur).put(if (done) 1 else 0).put(at).put(showId)
    val fraction: Float get() = if (dur > 0) (t.toFloat() / dur).coerceIn(0f, 1f) else 0f

    companion object {
        fun from(a: JSONArray?): Progress? {
            if (a == null || a.length() < 4) return null
            return Progress(a.optInt(0), a.optInt(1), a.optInt(2) == 1, a.optLong(3), a.optString(4, ""))
        }
    }
}

/**
 * The shows, their episodes and the listening progress.
 *
 * At home the lists come from Home Assistant (tools/podcasts/build.py writes
 * them every 30 minutes); away, a show's feed is read directly. Everything
 * is cached in the app's files so it opens offline.
 */
class Repo(private val context: Context) {
    private val dir = File(context.filesDir, "podcasts").apply { mkdirs() }
    private val haBase = BuildConfig.HA_URL.takeIf { it.isNotEmpty() }
    private val listBase = haBase?.let { "$it/local/roche-home/podcasts/" }
    private val entity = "sensor.roche_podcasts"

    private val _shows = MutableStateFlow<List<Show>>(emptyList())
    val shows: StateFlow<List<Show>> = _shows.asStateFlow()

    private val _episodes = MutableStateFlow<Map<String, List<Episode>>>(emptyMap())
    /** Episodes per show, newest first: the 3 newest for every show, all of them for shows opened. */
    val episodes: StateFlow<Map<String, List<Episode>>> = _episodes.asStateFlow()

    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: StateFlow<Map<String, Progress>> = _progress.asStateFlow()

    private val _status = MutableStateFlow("")
    /** A short note when the lists couldn't be refreshed; empty otherwise. */
    val status: StateFlow<String> = _status.asStateFlow()

    private val _opened = MutableStateFlow<Set<String>>(emptySet())
    /** Episodes opened in YouTube from here (YouTube doesn't say how far you watched). */
    val opened: StateFlow<Set<String>> = _opened.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val fullyLoaded = mutableSetOf<String>()
    private val syncLock = Mutex()

    /** Shows from Home Assistant's list (or the bundled one). */
    private var homeShows = emptyList<Show>()
    private val _added = MutableStateFlow<List<Show>>(emptyList())
    /** Shows subscribed on this phone from Discover. */
    val added: StateFlow<List<Show>> = _added.asStateFlow()
    /** Shows from Discover being looked at, not subscribed. */
    private val previews = mutableMapOf<String, Show>()
    /** Home Assistant's shows unsubscribed on this phone (the TV still lists them). */
    private var hidden = emptySet<String>()

    init {
        loadCache()
    }

    fun show(id: String): Show? = _shows.value.firstOrNull { it.id == id } ?: previews[id] ?: book(id)?.show

    /** In the list: Home Assistant's or added here. */
    fun subscribed(id: String) = _shows.value.any { it.id == id }

    fun episode(id: String): Episode? =
        _episodes.value.values.firstNotNullOfOrNull { list -> list.firstOrNull { it.id == id } } ?: bookChapters[id]

    // ---------- audiobooks (tools/audiobooks/audiobooks.json, bundled) ----------

    /** The audiobooks, read from the app's assets the first time they're needed. */
    val books: List<Book> by lazy {
        val a = runCatching { JSONObject(context.assets.open("audiobooks.json").bufferedReader().readText()).getJSONArray("books") }.getOrNull()
            ?: return@lazy emptyList()
        (0 until a.length()).map { i ->
            val b = a.getJSONObject(i)
            val id = b.getString("id")
            val desc = b.optString("desc")
            val ch = b.getJSONArray("chapters")
            val chapters = (0 until ch.length()).map { k ->
                val c = ch.getJSONArray(k)
                Episode(episodeId(c.getString(1)), id, c.getString(0), 0, c.optInt(2), c.getString(1), desc)
            }
            Book(Show(id, b.optString("title"), b.optString("author"), b.optString("librivox"), b.optString("art"), 0, b.optString("shelf")),
                desc, b.optInt("secs"), chapters)
        }
    }
    private val bookById by lazy { books.associateBy { it.id } }
    private val bookChapters by lazy { books.flatMap { it.chapters }.associateBy { it.id } }

    fun book(id: String): Book? = if (id.startsWith(BOOK)) bookById[id] else null

    private val textFiles by lazy { runCatching { context.assets.list("texts")?.toSet() }.getOrNull().orEmpty() }
    /** Has read-along text (for some or all of its parts). */
    fun hasText(b: Book) = "${b.id}.json" in textFiles

    private var textCache: Pair<String, JSONArray>? = null

    /**
     * A part's paragraphs, each with an estimated start: LibriVox's spoken intro
     * (about 18 s), then the rest of the part shared out by paragraph length.
     */
    @Synchronized
    private fun bookLines(b: Book, e: Episode): List<Line> {
        if (!hasText(b)) return emptyList()
        val parts = textCache?.takeIf { it.first == b.id }?.second ?: runCatching {
            JSONObject(context.assets.open("texts/${b.id}.json").bufferedReader().readText()).getJSONArray("parts")
        }.getOrNull()?.also { textCache = b.id to it } ?: return emptyList()
        val i = b.chapters.indexOfFirst { it.id == e.id }
        val paras = parts.optJSONArray(i) ?: return emptyList()
        val texts = (0 until paras.length()).map { paras.getString(it) }
        val total = texts.sumOf { it.length }.coerceAtLeast(1)
        val pre = if (e.duration > 120) 18.0 else 0.0
        val span = (e.duration - pre - 4).coerceAtLeast(1.0)
        var at = 0
        return texts.map { t -> Line((pre + span * at / total).toInt(), "", t).also { at += t.length } }
    }


    // ---------- cache ----------

    private fun loadCache() {
        runCatching { JSONArray(File(dir, "added.json").readText()) }.getOrNull()?.let { a ->
            _added.value = (0 until a.length()).map { showFromJson(a.getJSONObject(it), added = true) }
        }
        runCatching { JSONArray(File(dir, "hidden.json").readText()) }.getOrNull()?.let { a ->
            hidden = (0 until a.length()).map { a.getString(it) }.toSet()
        }
        val idx = File(dir, "index.json")
        val json = runCatching { JSONObject(idx.readText()) }.getOrNull()
            ?: runCatching { JSONObject(context.assets.open("sources.json").bufferedReader().readText()) }.getOrNull()
        json?.let { applyIndex(it) }
        publishShows()
        File(dir, "shows").listFiles()?.forEach { f ->
            runCatching { JSONObject(f.readText()) }.getOrNull()?.let { applyShowFile(f.nameWithoutExtension, it, full = false) }
        }
        runCatching { JSONArray(File(dir, "opened.json").readText()) }.getOrNull()?.let { a ->
            _opened.value = (0 until a.length()).map { a.getString(it) }.toSet()
        }
        runCatching { JSONObject(File(dir, "progress.json").readText()) }.getOrNull()?.let { p ->
            _progress.value = p.keys().asSequence().mapNotNull { k -> Progress.from(p.optJSONArray(k))?.let { k to it } }.toMap()
        }
    }

    private fun write(name: String, text: String) {
        val f = File(dir, name)
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(f)
    }

    /** podcasts.json from Home Assistant, or the bundled sources.json (no episodes). */
    private fun applyIndex(j: JSONObject) {
        val arr = j.optJSONArray("shows") ?: return
        val shows = mutableListOf<Show>()
        val eps = _episodes.value.toMutableMap()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            val id = s.getString("id")
            val image = s.optString("image").ifEmpty { s.optString("art").takeIf { it.startsWith("http") }.orEmpty() }
            val art = when {
                image.isNotEmpty() -> image.replace(Regex("/\\d+x\\d+bb\\.(jpg|png|webp)$"), "/300x300bb.jpg")
                s.optString("art").isNotEmpty() && listBase != null -> listBase + s.optString("art")
                else -> ""
            }
            shows += Show(id, s.optString("title"), s.optString("author"), s.optString("feed"), art, s.optLong("newest"),
                s.optString("category").ifEmpty { "Other" }, s.optBoolean("id3chapters"))
            val list = s.optJSONArray("eps")
            if (list != null && id !in fullyLoaded) eps[id] = parseEps(id, list)
        }
        homeShows = shows
        _episodes.value = eps
        publishShows()
    }

    /** Home Assistant's shows plus the ones added here (unless Home Assistant has them too). */
    private fun publishShows() {
        val home = homeShows.map { it.id }.toSet()
        val newest = _shows.value.associate { it.id to it.newest }
        _shows.value = homeShows.filter { it.id !in hidden } + _added.value.filter { it.id !in home }.map { it.copy(newest = maxOf(it.newest, newest[it.id] ?: 0)) }
    }

    private fun showFromJson(s: JSONObject, added: Boolean) = Show(s.getString("id"), s.optString("title"), s.optString("author"),
        s.optString("feed"), s.optString("art"), s.optLong("newest"), s.optString("category").ifEmpty { "Other" }, added = added)

    private fun saveAdded() {
        write("added.json", JSONArray().apply {
            _added.value.forEach { s ->
                put(JSONObject().put("id", s.id).put("title", s.title).put("author", s.author).put("feed", s.feed)
                    .put("art", s.art).put("newest", s.newest).put("category", s.category))
            }
        }.toString())
    }

    private fun parseEps(showId: String, a: JSONArray): List<Episode> = (0 until a.length()).map { i ->
        val e = a.getJSONObject(i)
        Episode(e.getString("id"), showId, e.optString("t"), e.optLong("d"), e.optInt("s"), e.optString("u"), e.optString("x"),
            e.optString("c"), e.optString("tr"), e.optString("tt"), e.optString("v"), e.optInt("yt") == 1)
    }

    private fun applyShowFile(id: String, j: JSONObject, full: Boolean) {
        val list = parseEps(id, j.optJSONArray("eps") ?: JSONArray())
        if (full) fullyLoaded += id
        _episodes.update { it + (id to list) }
    }

    private fun showJson(show: Show, eps: List<Episode>) = JSONObject()
        .put("id", show.id).put("title", show.title)
        .put("eps", JSONArray().apply {
            eps.forEach { e ->
                put(JSONObject().put("id", e.id).put("t", e.title).put("d", e.date).put("s", e.duration).put("u", e.url).put("x", e.desc)
                    .put("c", e.chaptersUrl).put("tr", e.transcriptUrl).put("tt", e.transcriptType)
                    .put("v", e.videoId).put("yt", if (e.youtubeOnly) 1 else 0))
            }
        })

    // ---------- refresh ----------

    /**
     * New lists from Home Assistant. Away from home, [everything] also reads
     * every feed directly (a pull to refresh), which costs some data.
     */
    suspend fun refresh(everything: Boolean) = withContext(Dispatchers.IO) {
        _refreshing.value = true
        try {
            val idx = listBase?.let { httpText(it + "podcasts.json", 5000) }
            val json = idx?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (json != null) {
                write("index.json", idx)
                fullyLoaded.clear()
                applyIndex(json)
                _status.value = ""
            } else if (everything) {
                val sem = Semaphore(8)
                coroutineScope {
                    _shows.value.map { s -> async { sem.withPermit { fromFeed(s) } } }.awaitAll()
                }
                _status.value = ""
            } else {
                _status.value = "Away from home: pull down to check every feed."
            }
            refreshAdded(everything)
            loadContinuing()
            sync()
        } finally {
            _refreshing.value = false
        }
    }

    /** All of one show's episodes (up to 100). */
    suspend fun loadShow(show: Show) = withContext(Dispatchers.IO) {
        if (homeShows.none { it.id == show.id }) { fromFeed(show); return@withContext }
        val text = listBase?.let { httpText(it + "shows/${show.id}.json", 5000) }
        val json = text?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (json != null) {
            write("shows/${show.id}.json", text)
            applyShowFile(show.id, json, full = true)
        } else {
            fromFeed(show)
        }
    }

    private fun fromFeed(show: Show): Boolean {
        val eps = runCatching {
            httpStream(show.feed, 20000) { parseFeed(show.id, it) }
        }.getOrNull() ?: return false
        if (eps.isEmpty()) return false
        // A show only being looked at in Discover isn't kept.
        if (subscribed(show.id)) write("shows/${show.id}.json", showJson(show, eps).toString())
        fullyLoaded += show.id
        _episodes.update { it + (show.id to eps) }
        _shows.update { list -> list.map { if (it.id == show.id) it.copy(newest = eps.first().date) else it } }
        return true
    }

    /** Shows added here aren't in Home Assistant's lists: read their feeds, at most every 30 minutes unless [everything]. */
    private suspend fun refreshAdded(everything: Boolean) {
        val home = homeShows.map { it.id }.toSet()
        val stale = System.currentTimeMillis() - 30 * 60_000L
        val due = _added.value.filter { s ->
            s.id !in home && (everything || File(dir, "shows/${s.id}.json").lastModified() < stale)
        }
        val sem = Semaphore(6)
        coroutineScope { due.map { s -> async { sem.withPermit { runCatching { fromFeed(s) } } } }.awaitAll() }
    }

    // ---------- discover (Apple's podcast directory) ----------

    /** Apple's top podcasts in the US. */
    suspend fun topCharts(): List<Show>? = withContext(Dispatchers.IO) {
        val chart = httpText("https://rss.marketingtools.apple.com/api/v2/us/podcasts/top/50/podcasts.json", 10000)
            ?.let { runCatching { JSONObject(it).getJSONObject("feed").getJSONArray("results") }.getOrNull() } ?: return@withContext null
        val ids = (0 until chart.length()).map { chart.getJSONObject(it).optString("id") }.filter { it.isNotEmpty() }
        val found = lookup(ids) ?: return@withContext null
        ids.mapNotNull { found[it] }
    }

    /** Apple's podcast search. */
    suspend fun searchDirectory(term: String): List<Show>? = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(term.trim(), "UTF-8")
        val raw = httpText("https://itunes.apple.com/search?media=podcast&entity=podcast&limit=40&term=$q", 10000) ?: return@withContext null
        runCatching { appleShows(JSONObject(raw).getJSONArray("results")) }.getOrNull()
    }

    /** Apple's details for up to a few hundred shows; null if Apple can't be reached. */
    private suspend fun lookup(ids: List<String>): Map<String, Show>? = coroutineScope {
        val parts = ids.distinct().chunked(100).map { chunk ->
            async {
                httpText("https://itunes.apple.com/lookup?entity=podcast&id=" + chunk.joinToString(","), 15000)
                    ?.let { raw -> runCatching { appleShows(JSONObject(raw).getJSONArray("results")) }.getOrNull() }
            }
        }.awaitAll()
        if (ids.isNotEmpty() && parts.all { it == null }) null else parts.filterNotNull().flatten().associateBy { it.id }
    }

    /** Genres of the shows in your list, from Apple (kept a week). */
    private suspend fun myGenres(): Map<String, List<String>> {
        val file = File(dir, "genres.json")
        val cached = runCatching { JSONObject(file.readText()) }.getOrNull()
            ?.takeIf { System.currentTimeMillis() - file.lastModified() < 7 * 86400_000L }
        val known = cached?.keys()?.asSequence()?.associateWith { k -> cached.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } } }.orEmpty()
        val mine = _shows.value
        val missing = mine.map { it.id }.filter { it !in known && it.all(Char::isDigit) }
        if (missing.isEmpty()) return known
        val found = lookup(missing)?.mapValues { it.value.genres }.orEmpty()
        val all = known + found
        write("genres.json", JSONObject().apply { all.forEach { (k, v) -> put(k, JSONArray(v)) } }.toString())
        return all
    }

    /**
     * Shows like the ones in your list: Apple's charts for your most common
     * genres, ranked by how closely each show's genres match your list's,
     * with a nudge for chart position and for creators you already follow.
     */
    suspend fun recommendations(): List<Show>? = withContext(Dispatchers.IO) {
        val mine = _shows.value
        val profile = myGenres()
        val counts = mine.mapNotNull { profile[it.id] }.flatten().groupingBy { it }.eachCount()
        val n = mine.size.coerceAtLeast(1).toDouble()
        val weight = counts.mapValues { it.value / n }
        val topGenres = counts.entries.sortedByDescending { it.value }.take(10).map { it.key }
        // Best chart position per show across the charts read.
        val rank = mutableMapOf<String, Int>()
        coroutineScope {
            (listOf<String?>(null) + topGenres).map { g ->
                async {
                    val url = "https://itunes.apple.com/us/rss/toppodcasts/limit=50" + (g?.let { "/genre=$it" } ?: "") + "/json"
                    val feed = httpText(url, 10000)?.let { runCatching { JSONObject(it).getJSONObject("feed") }.getOrNull() }
                    val entries = feed?.optJSONArray("entry") ?: feed?.optJSONObject("entry")?.let { JSONArray().put(it) } ?: JSONArray()
                    (0 until entries.length()).mapNotNull { i ->
                        entries.getJSONObject(i).optJSONObject("id")?.optJSONObject("attributes")?.optString("im:id")?.let { it to i }
                    }
                }
            }.awaitAll().flatten().forEach { (id, i) -> rank[id] = minOf(rank[id] ?: i, i) }
        }
        if (rank.isEmpty()) return@withContext null
        val found = lookup(rank.keys.toList()) ?: return@withContext null
        val authors = mine.map { it.author.lowercase() }.filter { it.isNotEmpty() }.toSet()
        fun score(s: Show): Double {
            val match = if (s.genres.isEmpty()) 0.0 else s.genres.sumOf { weight[it] ?: 0.0 } / Math.sqrt(s.genres.size.toDouble())
            val chart = 0.15 / (1 + (rank[s.id] ?: 50) / 10.0)
            val creator = if (s.author.lowercase() in authors) 0.25 else 0.0
            return match + chart + creator
        }
        found.values.sortedByDescending(::score)
    }

    private fun appleShows(a: JSONArray): List<Show> = (0 until a.length()).map { a.getJSONObject(it) }
        .filter { it.optString("feedUrl").isNotEmpty() && it.has("collectionId") }
        .map { r ->
            val art = r.optString("artworkUrl600").ifEmpty { r.optString("artworkUrl100") }
                .replace(Regex("/\\d+x\\d+bb\\.(jpg|png|webp)$"), "/300x300bb.jpg")
            val date = r.optString("releaseDate").let { d ->
                runCatching { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(d)!!.time / 1000 }.getOrDefault(0L)
            }
            Show(r.getLong("collectionId").toString(), r.optString("collectionName"), r.optString("artistName"), r.optString("feedUrl"),
                art, date, category(r.optString("primaryGenreName")),
                genres = r.optJSONArray("genreIds")?.let { g -> (0 until g.length()).map { g.getString(it) } }.orEmpty().filter { it != "26" })
        }

    /** Apple's genre, in the app's own categories. */
    private fun category(genre: String): String = when (genre) {
        "News", "Politics", "Daily News", "News Commentary", "Government" -> "News & Politics"
        "Society & Culture", "Philosophy", "Documentary", "Personal Journals", "Relationships", "Arts", "Books", "Religion & Spirituality", "Business" -> "Ideas & Culture"
        "Science", "Natural Sciences", "Life Sciences", "Physics", "Astronomy", "Social Sciences", "Health & Fitness", "Medicine" -> "Science"
        "History" -> "History & Legends"
        "True Crime", "Paranormal" -> "Mysteries & UFOs"
        "Technology", "Tech News" -> "Tech"
        "Comedy", "Comedy Interviews", "Improv", "Stand-Up", "TV & Film", "Music", "Leisure", "Fiction", "Sports" -> "Comedy & Entertainment"
        "Education", "Courses", "How To", "Language Learning", "Self-Improvement" -> "Learning"
        else -> genre.ifEmpty { "Other" }
    }

    /** Lets a show from Discover open like any other before it's subscribed. */
    fun preview(show: Show) {
        if (!subscribed(show.id)) previews[show.id] = show
    }

    suspend fun subscribe(show: Show) = withContext(Dispatchers.IO) {
        if (subscribed(show.id)) return@withContext
        previews.remove(show.id)
        if (show.id in hidden && homeShows.any { it.id == show.id }) {
            hidden = hidden - show.id
            write("hidden.json", JSONArray(hidden.toList()).toString())
            publishShows()
            return@withContext
        }
        _added.update { it + show.copy(added = true) }
        saveAdded()
        publishShows()
        // Keep what was already loaded while previewing; otherwise read the feed now.
        val eps = _episodes.value[show.id]
        if (eps.isNullOrEmpty()) fromFeed(show) else write("shows/${show.id}.json", showJson(show, eps).toString())
    }

    /**
     * Shows added here are dropped; Home Assistant's are hidden on this phone
     * (the TV keeps them; tools/podcasts/sources.json is the real list).
     */
    fun unsubscribe(id: String) {
        val show = _shows.value.firstOrNull { it.id == id } ?: return
        if (show.added) {
            _added.update { list -> list.filter { it.id != id } }
            saveAdded()
            File(dir, "shows/$id.json").delete()
        } else {
            hidden = hidden + id
            write("hidden.json", JSONArray(hidden.toList()).toString())
        }
        publishShows()
        // Still viewable (and re-subscribable) until the app closes.
        previews[id] = show.copy(added = false)
    }

    /** Episodes in progress (maybe started on the TV) that aren't among each show's newest. */
    private suspend fun loadContinuing() {
        val missing = _progress.value.filter { (id, p) -> !p.done && p.t > 0 && episode(id) == null }
            .values.map { it.showId }.distinct().mapNotNull { show(it) }.take(8)
        missing.forEach { runCatching { loadShow(it) } }
    }

    // ---------- chapters and transcripts (same rules as build.py) ----------

    private val extrasCache = mutableMapOf<String, Extras>()

    /** Read straight from the feed's links, so it works away from home too. */
    suspend fun extras(e: Episode): Extras = withContext(Dispatchers.IO) {
        extrasCache[e.id]?.let { return@withContext it }
        book(e.showId)?.let { b -> return@withContext Extras(emptyList(), bookLines(b, e), timed = true, estimated = true) }
        val show = show(e.showId)
        val chapters = runCatching {
            when {
                e.chaptersUrl.isNotEmpty() -> jsonChapters(httpText(e.chaptersUrl, 10000).orEmpty())
                show?.id3Chapters == true -> open(e.url, 15000).let { c -> try { id3Chapters(c.inputStream) } finally { c.disconnect() } }
                else -> emptyList()
            }
        }.getOrDefault(emptyList()).takeIf { it.size > 1 }.orEmpty()
        var timed = true
        val lines = if (e.transcriptUrl.isEmpty()) emptyList() else runCatching {
            val raw = httpText(e.transcriptUrl, 15000).orEmpty()
            timed = e.transcriptType in setOf("vtt", "srt", "json")
            parseTranscript(e.transcriptType, raw)
        }.getOrDefault(emptyList())
        Extras(chapters, lines, timed).also { if (chapters.isNotEmpty() || lines.isNotEmpty()) extrasCache[e.id] = it }
    }

    // ---------- queue (episode IDs; the player holds the real one) ----------

    fun saveQueue(ids: List<String>, current: Int) {
        write("queue.json", JSONObject().put("ids", JSONArray(ids)).put("current", current).toString())
    }

    fun loadQueue(): Pair<List<Episode>, Int> {
        val j = runCatching { JSONObject(File(dir, "queue.json").readText()) }.getOrNull() ?: return emptyList<Episode>() to 0
        val ids = j.optJSONArray("ids") ?: JSONArray()
        val eps = (0 until ids.length()).mapNotNull { episode(ids.getString(it)) }
        return eps to j.optInt("current").coerceIn(0, (eps.size - 1).coerceAtLeast(0))
    }

    // ---------- progress ----------

    fun markOpened(id: String) {
        if (id in _opened.value) return
        _opened.update { (it + id).toList().takeLast(500).toSet() }
        write("opened.json", JSONArray(_opened.value.toList()).toString())
    }

    fun setProgress(id: String, showId: String, t: Int, dur: Int, done: Boolean) {
        _progress.update { it + (id to Progress(t, dur, done, System.currentTimeMillis() / 1000, showId)) }
        saveProgress()
    }

    fun setPlayed(e: Episode, played: Boolean) {
        val old = _progress.value[e.id]
        setProgress(e.id, e.showId, if (played) (old?.dur ?: e.duration) else 0, old?.dur ?: e.duration, played)
    }

    private fun saveProgress() {
        val o = JSONObject()
        _progress.value.forEach { (k, v) -> o.put(k, v.toJson()) }
        write("progress.json", o.toString())
    }

    /**
     * Podcast progress: the newest 400 from the last 180 days (shared with the TV).
     * Audiobook parts are kept apart, so a long book can't push podcasts out.
     */
    private fun pruned(map: Map<String, Progress>): Map<String, Progress> {
        val cutoff = System.currentTimeMillis() / 1000 - 180 * 86400
        val (books, shows) = map.entries.partition { it.value.showId.startsWith(BOOK) }
        return shows.filter { it.value.at > cutoff }.sortedByDescending { it.value.at }.take(400).associate { it.toPair() } +
            books.sortedByDescending { it.value.at }.take(5000).associate { it.toPair() }
    }

    /** Same as the TV: newer entry per episode wins; write back when ours had news. */
    suspend fun sync() = withContext(Dispatchers.IO) {
        val base = haBase ?: return@withContext
        syncLock.withLock {
            runCatching {
                val raw = httpText("$base/api/states/$entity", 5000, auth = true)
                val theirs = raw?.let { JSONObject(it).optJSONObject("attributes")?.optJSONObject("progress") }
                if (raw == null && !reachable(base)) return@runCatching
                val remote = theirs?.let { t -> t.keys().asSequence().mapNotNull { k -> Progress.from(t.optJSONArray(k))?.let { k to it } }.toMap() }.orEmpty()
                val mine = _progress.value
                val merged = mine.toMutableMap()
                var changed = false
                remote.forEach { (k, v) -> if ((mine[k]?.at ?: -1) < v.at) { merged[k] = v; changed = true } }
                // Audiobook progress stays on the phone; the TV has no audiobooks.
                val push = merged.any { (k, v) -> !v.showId.startsWith(BOOK) && (remote[k]?.at ?: -1) < v.at }
                val final = pruned(merged)
                if (changed) { _progress.value = final; saveProgress() }
                if (push) {
                    val p = JSONObject()
                    val shared = final.filterValues { !it.showId.startsWith(BOOK) }
                    shared.forEach { (k, v) -> p.put(k, v.toJson()) }
                    val body = JSONObject().put("state", shared.size.toString()).put("attributes",
                        JSONObject().put("friendly_name", "Podcast progress").put("icon", "mdi:podcast").put("progress", p))
                    httpPost("$base/api/states/$entity", body.toString())
                }
            }
        }
    }

    // ---------- cast to the TV ----------

    /** Home Assistant is set up, so the TV can be cast to. */
    val canCast get() = haBase != null

    /**
     * Hands playback to Roche Home on the TV: writes [e], the place, the speed and
     * what's up next to an entity the TV checks every few seconds (app/js/podcasts.js).
     * False when Home Assistant can't be reached.
     */
    suspend fun castToTv(e: Episode, posSec: Int, speed: Float, upNext: List<Episode>): Boolean = withContext(Dispatchers.IO) {
        val base = haBase ?: return@withContext false
        fun showOf(x: Episode) = show(x.showId).let { s ->
            JSONObject().put("id", x.showId).put("title", s?.title.orEmpty()).put("art", s?.art.orEmpty())
        }
        fun epOf(x: Episode) = JSONObject().put("id", x.id).put("t", x.title).put("u", x.url).put("s", x.duration).put("x", x.desc)
            .put("showId", x.showId)
        val attrs = JSONObject()
            .put("friendly_name", "Podcast cast to the TV").put("icon", "mdi:cast")
            .put("at", System.currentTimeMillis() / 1000)
            .put("ep", epOf(e)).put("show", showOf(e))
            .put("pos", posSec).put("speed", speed.toDouble())
            .put("queue", JSONArray().apply { upNext.filter { !it.youtubeOnly }.take(40).forEach { put(JSONObject().put("ep", epOf(it)).put("show", showOf(it))) } })
        val body = JSONObject().put("state", e.title.take(250)).put("attributes", attrs)
        runCatching { httpPost("$base/api/states/sensor.roche_podcasts_cast", body.toString()) }.isSuccess
    }

    // ---------- network ----------

    private fun reachable(base: String) = runCatching {
        open(base + "/api/", 3000, auth = true).run { val c = responseCode; disconnect(); c in 200..499 }
    }.getOrDefault(false)

    private fun open(url: String, timeout: Int, auth: Boolean = false): HttpURLConnection {
        var u = URL(url)
        repeat(6) {
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = timeout
            c.readTimeout = timeout * 2
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "RochePodcasts/1.0 (Android)")
            if (auth) c.setRequestProperty("Authorization", "Bearer ${BuildConfig.HA_TOKEN}")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: return c
                c.disconnect()
                u = URL(u, loc)   // http <-> https redirects too, which HttpURLConnection won't follow
            } else return c
        }
        throw java.io.IOException("too many redirects")
    }

    private fun httpText(url: String, timeout: Int, auth: Boolean = false): String? = runCatching {
        val c = open(url, timeout, auth)
        try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
    }.getOrNull()

    private fun <T> httpStream(url: String, timeout: Int, read: (InputStream) -> T): T? {
        val c = open(url, timeout)
        try { return if (c.responseCode == 200) c.inputStream.buffered().use(read) else null } finally { c.disconnect() }
    }

    private fun httpPost(url: String, body: String) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 10000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Authorization", "Bearer ${BuildConfig.HA_TOKEN}")
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        c.disconnect()
        if (code !in 200..299) throw java.io.IOException("HTTP $code")
    }

    // ---------- RSS (same rules as tools/podcasts/build.py) ----------

    companion object {
        private const val KEEP = 100
        /** Audiobook IDs are LibriVox's, prefixed. */
        const val BOOK = "lv"
        private val TRANSCRIPT_KINDS = mapOf("text/vtt" to "vtt", "application/srt" to "srt", "application/x-subrip" to "srt", "text/srt" to "srt",
            "application/json" to "json", "text/plain" to "txt", "text/html" to "html")
        private val TRANSCRIPT_ORDER = listOf("vtt", "srt", "json", "txt", "html")
        private const val DESC = 600

        fun episodeId(key: String): String =
            MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)

        private val dateFormats = listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, dd MMM yyyy HH:mm Z",
            "dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss")

        fun date(s: String?): Long {
            val v = s?.trim()?.replace(Regex("\\s+"), " ") ?: return 0
            for (f in dateFormats) {
                runCatching { return SimpleDateFormat(f, Locale.US).parse(v)!!.time / 1000 }
            }
            return 0
        }

        fun seconds(s: String?): Int {
            val parts = s?.trim()?.split(":")?.map { it.toDoubleOrNull() ?: return 0 } ?: return 0
            return parts.fold(0.0) { acc, p -> acc * 60 + p }.toInt()
        }

        fun plain(s: String?, limit: Int = 0): String {
            if (s.isNullOrEmpty()) return ""
            var t = s.replace(Regex("<(br|p|/p|li|div)[^>]*>", RegexOption.IGNORE_CASE), " ").replace(Regex("<[^>]+>"), "")
            t = android.text.Html.fromHtml(t.replace("<", "&lt;"), android.text.Html.FROM_HTML_MODE_LEGACY).toString()
            t = t.replace(Regex("\\s+"), " ").trim()
            if (limit > 0 && t.length > limit) t = t.take(limit).substringBeforeLast(' ') + "…"
            return t
        }

        fun jsonChapters(raw: String): List<Chapter> {
            val a = JSONObject(raw).optJSONArray("chapters") ?: return emptyList()
            return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optBoolean("toc", true) }
                .map { Chapter(it.optDouble("startTime").toInt(), plain(it.optString("title"))) }
        }

        /** CHAP frames from the ID3v2 tag at the start of an MP3; reads only the tag. */
        fun id3Chapters(input: InputStream): List<Chapter> {
            val head = ByteArray(10)
            if (input.readNBytes(head, 0, 10) < 10 || String(head, 0, 3, Charsets.ISO_8859_1) != "ID3") return emptyList()
            val ver = head[3].toInt()
            fun syncsafe(b: ByteArray, o: Int) = ((b[o].toInt() and 0x7f) shl 21) or ((b[o + 1].toInt() and 0x7f) shl 14) or
                ((b[o + 2].toInt() and 0x7f) shl 7) or (b[o + 3].toInt() and 0x7f)
            fun int(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or
                ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)
            val size = syncsafe(head, 6)
            if (size > 8 * 1024 * 1024) return emptyList()
            val tag = input.readNBytes(size)
            fun frames(b: ByteArray, from: Int, to: Int) = sequence {
                var i = from
                while (i + 10 <= to && b[i].toInt() != 0) {
                    val id = String(b, i, 4, Charsets.ISO_8859_1)
                    val n = if (ver >= 4) syncsafe(b, i + 4) else int(b, i + 4)
                    if (n < 0 || i + 10 + n > to) break
                    yield(Triple(id, i + 10, i + 10 + n))
                    i += 10 + n
                }
            }
            fun text(b: ByteArray, s: Int, e: Int): String {
                val cs = when (b[s].toInt()) { 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1 }
                return String(b, s + 1, e - s - 1, cs).trim('\u0000', ' ')
            }
            val out = mutableListOf<Chapter>()
            for ((id, s, e) in frames(tag, 0, tag.size)) {
                if (id != "CHAP") continue
                var z = s
                while (z < e && tag[z].toInt() != 0) z++
                val startMs = int(tag, z + 1)
                var title = ""
                for ((sid, ss, se) in frames(tag, z + 17, e)) if (sid == "TIT2") title = text(tag, ss, se)
                out += Chapter(startMs / 1000, title)
            }
            return out.sortedBy { it.start }
        }

        private fun stamp(v: String): Int = v.trim().replace(',', '.').split(':').fold(0.0) { a, p -> a * 60 + (p.toDoubleOrNull() ?: 0.0) }.toInt()

        fun parseTranscript(kind: String, raw: String): List<Line> {
            val lines = mutableListOf<Line>()
            when (kind) {
                "vtt", "srt" -> for (block in raw.replace("\r", "").split(Regex("\n\\s*\n"))) {
                    val m = Regex("([\\d:.,]+)\\s*-->").find(block) ?: continue
                    val body = block.substring(m.range.last + 1).substringAfter('\n', "")
                    var who = Regex("<v\\s+([^>]+)>").find(body)?.groupValues?.get(1)?.trim().orEmpty()
                    var words = plain(body)
                    if (who.isEmpty()) Regex("^([A-Z][\\w .'-]{0,40}):\\s+(.*)$").find(words)?.let { who = it.groupValues[1]; words = it.groupValues[2] }
                    if (words.isNotEmpty()) lines += Line(stamp(m.groupValues[1]), who, words)
                }
                "json" -> {
                    val a = JSONObject(raw).optJSONArray("segments") ?: JSONArray()
                    for (i in 0 until a.length()) {
                        val seg = a.getJSONObject(i)
                        val words = plain(seg.optString("body"))
                        if (words.isNotEmpty()) lines += Line(seg.optDouble("startTime").toInt(), seg.optString("speaker"), words)
                    }
                }
                else -> return raw.split(Regex("\n\\s*\n|</p>|<br\\s*/?>\\s*<br\\s*/?>")).map { plain(it) }.filter { it.isNotEmpty() }.map { Line(0, "", it) }
            }
            // Cues are a few words each: join into sentences of up to ~25 s, per speaker.
            val merged = mutableListOf<Line>()
            for (ln in lines) {
                val last = merged.lastOrNull()
                if (last != null && last.speaker == ln.speaker && ln.start - last.start < 25 && !Regex("[.!?][\"”]?$").containsMatchIn(last.text)) {
                    merged[merged.size - 1] = last.copy(text = last.text + " " + ln.text)
                } else merged += ln
            }
            return merged
        }

        fun parseFeed(showId: String, input: InputStream): List<Episode> {
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            p.setInput(input, null)
            val out = mutableListOf<Episode>()
            var inItem = false
            var title = ""; var guid = ""; var pub = ""; var dur = ""; var url = ""; var type = ""; var summary = ""; var desc = ""
            var chapters = ""; var trUrl = ""; var trType = ""
            val pc = "https://podcastindex.org/namespace/1.0"
            val itunes = "http://www.itunes.com/dtds/podcast-1.0.dtd"
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG) {
                    val ns = p.namespace
                    when {
                        p.name == "item" && ns.isEmpty() -> { inItem = true; title = ""; guid = ""; pub = ""; dur = ""; url = ""; type = ""; summary = ""; desc = ""; chapters = ""; trUrl = ""; trType = "" }
                        !inItem -> {}
                        p.name == "enclosure" && ns.isEmpty() -> { url = p.getAttributeValue(null, "url").orEmpty(); type = p.getAttributeValue(null, "type").orEmpty().lowercase() }
                        p.name == "title" && ns.isEmpty() -> title = p.nextText()
                        p.name == "guid" && ns.isEmpty() -> guid = p.nextText().trim()
                        p.name == "pubDate" && ns.isEmpty() -> pub = p.nextText()
                        p.name == "description" && ns.isEmpty() -> desc = p.nextText()
                        p.name == "duration" && ns == itunes -> dur = p.nextText()
                        p.name == "summary" && ns == itunes -> summary = p.nextText()
                        p.name == "chapters" && ns == pc -> chapters = p.getAttributeValue(null, "url").orEmpty()
                        p.name == "transcript" && ns == pc -> {
                            val kind = TRANSCRIPT_KINDS[p.getAttributeValue(null, "type").orEmpty().lowercase()]
                            val u = p.getAttributeValue(null, "url").orEmpty()
                            if (kind != null && u.isNotEmpty() && (trType.isEmpty() || TRANSCRIPT_ORDER.indexOf(kind) < TRANSCRIPT_ORDER.indexOf(trType))) {
                                trType = kind; trUrl = u
                            }
                        }
                    }
                } else if (p.eventType == XmlPullParser.END_TAG && p.name == "item" && p.namespace.isEmpty()) {
                    inItem = false
                    if (url.isNotEmpty() && (type.isEmpty() || type.startsWith("audio") || "mpeg" in type)) {
                        out += Episode(episodeId(guid.ifEmpty { url }), showId, plain(title), date(pub), seconds(dur), url,
                            plain(summary.ifEmpty { desc }, DESC), chapters, trUrl, trType)
                    }
                }
            }
            return out.sortedByDescending { it.date }.take(KEEP)
        }
    }
}
