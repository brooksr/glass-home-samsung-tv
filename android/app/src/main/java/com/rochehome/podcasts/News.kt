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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

data class NewsFeed(val category: String, val name: String, val url: String)

data class Article(val id: String, val source: String, val category: String, val title: String, val link: String,
                   /** Unix seconds. */ val date: Long, val summary: String, val image: String,
                   /** The paper behind a Google News item; shown instead of the feed's name. */ val publisher: String = "")

/**
 * The News tab: articles from the feeds in tools/podcasts/news.json (bundled
 * in the app), read straight from each site so it works anywhere. Cached in
 * the app's files; refreshed on open when older than 30 minutes, or by pulling
 * down. Keeps the last 4 days, plus each feed's 3 newest from the last month. Articles open in the browser; read ones are dimmed.
 */
class News(private val context: Context) {
    private val dir = File(context.filesDir, "news").apply { mkdirs() }
    val feeds: List<NewsFeed> = runCatching {
        val a = JSONObject(context.assets.open("news.json").bufferedReader().readText()).getJSONArray("feeds")
        (0 until a.length()).map { a.getJSONObject(it) }.map { NewsFeed(it.getString("category"), it.getString("name"), it.getString("url")) }
    }.getOrDefault(emptyList())

    private val _articles = MutableStateFlow<List<Article>>(emptyList())
    val articles: StateFlow<List<Article>> = _articles.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _read = MutableStateFlow<Set<String>>(emptySet())
    val read: StateFlow<Set<String>> = _read.asStateFlow()
    private var fetchedAt = 0L

    init {
        runCatching {
            val j = JSONObject(File(dir, "articles.json").readText())
            fetchedAt = j.optLong("at")
            val a = j.getJSONArray("articles")
            _articles.value = (0 until a.length()).map { a.getJSONObject(it) }.map {
                Article(it.getString("id"), it.getString("source"), it.getString("category"), it.getString("title"), it.getString("link"),
                    it.getLong("date"), it.optString("summary"), it.optString("image"), it.optString("publisher"))
            }
        }
        runCatching {
            val a = JSONArray(File(dir, "read.json").readText())
            _read.value = (0 until a.length()).map { a.getString(it) }.toSet()
        }
    }

    fun markRead(a: Article) {
        _read.value = _read.value + a.id
        // Only articles still listed need remembering.
        val keep = _articles.value.map { it.id }.toSet()
        File(dir, "read.json").writeText(JSONArray(_read.value.filter { it in keep }).toString())
    }

    suspend fun refresh(force: Boolean) = withContext(Dispatchers.IO) {
        if (!force && System.currentTimeMillis() - fetchedAt < 30 * 60_000 && _articles.value.isNotEmpty()) return@withContext
        _refreshing.value = true
        try {
            val sem = Semaphore(8)
            val fresh = coroutineScope {
                feeds.map { f -> async { sem.withPermit { runCatching { fetch(f) }.getOrNull() } } }.awaitAll()
            }
            // A feed that failed keeps its articles from last time.
            val old = _articles.value.groupBy { it.source }
            val all = feeds.zip(fresh).flatMap { (f, got) -> got ?: old[f.name].orEmpty() }
            // The last 4 days, plus each feed's 3 newest from the last month (for sites that post rarely).
            val now = System.currentTimeMillis() / 1000
            val recent = all.filter { it.date == 0L || it.date > now - 4 * 86400 }
            val slow = all.groupBy { it.source }.values.flatMap { list -> list.sortedByDescending { it.date }.take(3).filter { it.date > now - 30 * 86400 } }
            // The same story can come from a paper's feed and a Google News search.
            _articles.value = (recent + slow).distinctBy { it.link }.distinctBy { it.title.lowercase() }.sortedByDescending { it.date }
            fetchedAt = System.currentTimeMillis()
            val a = JSONArray()
            _articles.value.forEach {
                a.put(JSONObject().put("id", it.id).put("source", it.source).put("category", it.category).put("title", it.title)
                    .put("link", it.link).put("date", it.date).put("summary", it.summary).put("image", it.image).put("publisher", it.publisher))
            }
            File(dir, "articles.json").writeText(JSONObject().put("at", fetchedAt).put("articles", a).toString())
        } finally {
            _refreshing.value = false
        }
    }

    private fun fetch(f: NewsFeed): List<Article> {
        var u = URL(f.url)
        repeat(5) {
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 20000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "RochePodcasts/1.0 (Android)")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location"); c.disconnect()
                u = URL(u, loc ?: return emptyList())
                return@repeat
            }
            try {
                return if (code == 200) c.inputStream.buffered().use { parse(f, it) }.take(25) else emptyList()
            } finally { c.disconnect() }
        }
        return emptyList()
    }

    companion object {
        private val formats = listOf("EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, dd MMM yyyy HH:mm Z",
            "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd")

        fun date(s: String): Long {
            val v = s.trim().replace(Regex("\\s+"), " ")
            for (f in formats) runCatching { return SimpleDateFormat(f, Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(v)!!.time / 1000 }
            return 0
        }

        private fun firstImage(html: String) = Regex("<img[^>]+src=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1).orEmpty()

        /** RSS 2.0, RSS 1.0 (RDF) and Atom. */
        fun parse(f: NewsFeed, input: InputStream): List<Article> {
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            p.setInput(input, null)
            val out = mutableListOf<Article>()
            var inItem = false
            var title = ""; var link = ""; var date = ""; var summary = ""; var content = ""; var image = ""; var guid = ""; var publisher = ""
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                val name = p.name
                if (p.eventType == XmlPullParser.START_TAG) {
                    if (name == "item" || name == "entry") {
                        inItem = true; title = ""; link = ""; date = ""; summary = ""; content = ""; image = ""; guid = ""; publisher = ""
                        continue
                    }
                    if (!inItem) continue
                    val media = p.namespace.contains("search.yahoo.com/mrss")
                    when {
                        media && (name == "content" || name == "thumbnail") -> {
                            val type = p.getAttributeValue(null, "type").orEmpty() + p.getAttributeValue(null, "medium").orEmpty()
                            if (image.isEmpty() && (type.isEmpty() || "image" in type)) image = p.getAttributeValue(null, "url").orEmpty()
                        }
                        else -> when (name) {
                        "title" -> title = p.nextText()
                        "link" -> {
                            val href = p.getAttributeValue(null, "href")
                            val rel = p.getAttributeValue(null, "rel")
                            if (href != null) { if (rel == null || rel == "alternate") link = href } else link = p.nextText().trim()
                        }
                        "guid", "id" -> guid = p.nextText().trim()
                        "source" -> publisher = p.nextText().trim()
                        "pubDate", "published", "updated", "date" -> { val d = p.nextText(); if (date.isEmpty() || name == "published") date = d }
                        "description", "summary" -> summary = p.nextText()
                        "encoded", "content" -> content = runCatching { p.nextText() }.getOrDefault("")
                        "enclosure" -> if (image.isEmpty() && p.getAttributeValue(null, "type").orEmpty().startsWith("image")) image = p.getAttributeValue(null, "url").orEmpty()
                        }
                    }
                } else if (p.eventType == XmlPullParser.END_TAG && (name == "item" || name == "entry")) {
                    inItem = false
                    if (title.isBlank() || link.isBlank()) continue
                    if (image.isEmpty()) image = firstImage(content.ifEmpty { summary })
                    var headline = Repo.plain(title)
                    // Google News: "Headline - Paper", and a summary that only repeats the headline.
                    var text = Repo.plain(summary.ifEmpty { content }, 280)
                    if (publisher.isNotEmpty()) {
                        headline = headline.removeSuffix(" - $publisher")
                        if (Regex("\\bobituar(y|ies)\\b", RegexOption.IGNORE_CASE).containsMatchIn(headline)) continue
                        if (text.startsWith(headline.take(40))) text = ""
                    }
                    out += Article(Repo.episodeId(guid.ifEmpty { link }), f.name, f.category, headline, link, date(date), text, image, publisher)
                }
            }
            return out
        }
    }
}
