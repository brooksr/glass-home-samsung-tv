package com.rochehome.podcasts

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A movie or show on one or more of our streaming services (tools/watch/build.py). */
data class Title(
    val id: String, val movie: Boolean, val title: String, val year: Int, val genres: List<String>, val rated: String,
    val overview: String, val poster: String, val backdrop: String, val imdb: String,
    /** 0 when IMDb has no rating. */
    val imdbRating: Float, val imdbVotes: Int, val popularity: Float,
    val cast: List<String>,
    /** Director(s) of a movie, creator(s) of a show. */
    val by: List<String>,
    /** YouTube video ID. */
    val trailer: String,
    /** Service keys, e.g. netflix, prime. */
    val services: List<String>,
    /** Minutes (movies). */
    val runtime: Int,
    val seasons: Int, val ended: Boolean, val lastYear: Int,
) {
    val tmdbId get() = id.drop(1)
    /** "2008–2013", "2019–" or "1972". */
    val years get() = when {
        movie || year == 0 -> if (year > 0) "$year" else ""
        ended && lastYear > year -> "$year–$lastYear"
        ended -> "$year"
        else -> "$year–"
    }
    val length get() = if (movie) (if (runtime > 0) mins(runtime * 60) else "") else if (seasons > 0) "$seasons season" + (if (seasons > 1) "s" else "") else ""
}

class Service(val key: String, val label: String)

/**
 * The Watch tab's list: Home Assistant serves the newest (tools/watch/build.py, weekly);
 * until it's been reached, the copy bundled in the app. Saved titles ("My list") stay on the phone.
 */
class Watch(private val context: Context) {
    private val file = File(context.filesDir, "watch.json")
    private val url = BuildConfig.HA_URL.takeIf { it.isNotEmpty() }?.let { "$it/local/roche-home/watch/watch.json" }
    private val prefs = context.getSharedPreferences("watch", 0)

    private val _titles = MutableStateFlow<List<Title>>(emptyList())
    val titles: StateFlow<List<Title>> = _titles.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()
    private val _saved = MutableStateFlow(prefs.getStringSet("saved", emptySet()).orEmpty().toSet())
    val saved: StateFlow<Set<String>> = _saved.asStateFlow()
    var services: List<Service> = emptyList(); private set
    var images = "https://image.tmdb.org/t/p/"; private set
    /** Unix seconds the list was built. */
    var updated = 0L; private set
    private var loaded = false
    private var checkedAt = 0L

    fun toggleSaved(t: Title) {
        _saved.value = if (t.id in _saved.value) _saved.value - t.id else _saved.value + t.id
        prefs.edit { putStringSet("saved", _saved.value) }
    }

    fun find(id: String) = _titles.value.firstOrNull { it.id == id }
    fun service(key: String) = services.firstOrNull { it.key == key }
    fun poster(t: Title, size: String = "w185") = if (t.poster.isEmpty()) null else images + size + t.poster
    fun backdrop(t: Title) = if (t.backdrop.isEmpty()) null else images + "w780" + t.backdrop

    /** The saved or bundled list, then Home Assistant's if it's newer (checked at most every 6 hours unless [force]). */
    suspend fun refresh(force: Boolean) = withContext(Dispatchers.IO) {
        if (!loaded) {
            loaded = true
            _refreshing.value = true   // "Loading…" rather than "No list yet" while it's read
            val text = runCatching { file.readText() }.getOrNull() ?: runCatching { context.assets.open("watch.json").bufferedReader().readText() }.getOrNull()
            text?.let { runCatching { parse(it) } }
            _refreshing.value = false
        }
        val u = url ?: return@withContext
        if (!force && System.currentTimeMillis() - checkedAt < 6 * 3600_000L) return@withContext
        _refreshing.value = true
        try {
            val text = runCatching {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 5000
                c.readTimeout = 30000
                c.setRequestProperty("User-Agent", "RochePodcasts/1.0 (Android)")
                try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
            }.getOrNull()
            if (text != null) {
                checkedAt = System.currentTimeMillis()
                val before = updated
                runCatching { parse(text) }
                if (updated > before) file.writeText(text)
            }
        } finally {
            _refreshing.value = false
        }
    }

    private fun parse(text: String) {
        val j = JSONObject(text)
        val at = j.optLong("updated")
        if (at <= updated) return
        fun strings(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
        val s = j.getJSONArray("services")
        services = (0 until s.length()).map { s.getJSONObject(it) }.map { Service(it.getString("key"), it.getString("label")) }
        images = j.optString("images", images)
        val a = j.getJSONArray("titles")
        _titles.value = (0 until a.length()).map { a.getJSONObject(it) }.map {
            Title(it.getString("id"), it.optString("kind") == "movie", it.getString("title"), it.optInt("year"), strings(it.optJSONArray("genres")),
                it.optString("rated"), it.optString("overview"), it.optString("poster"), it.optString("backdrop"), it.optString("imdb"),
                it.optDouble("imdbRating", 0.0).toFloat(), it.optInt("imdbVotes"), it.optDouble("popularity", 0.0).toFloat(),
                strings(it.optJSONArray("cast")), strings(it.optJSONArray("by")), it.optString("trailer"), strings(it.optJSONArray("services")),
                it.optInt("runtime"), it.optInt("seasons"), it.optBoolean("ended"), it.optInt("lastYear"))
        }
        updated = at
    }
}

// ---------- opening a title in its app ----------

/** Each service's Android app, and its search (which the app opens when it handles the link, else the browser). */
private val APPS = mapOf(
    "netflix" to "com.netflix.mediaclient",
    "prime" to "com.amazon.avod.thirdpartyclient",
    "disney" to "com.disney.disneyplus",
    "hulu" to "com.hulu.plus",
    "paramount" to "com.cbs.app",
    "tubi" to "com.tubitv",
    "youtube" to "com.google.android.youtube",
)

private fun searchUrl(service: String, t: Title): String {
    val q = Uri.encode(t.title)
    return when (service) {
        "netflix" -> "https://www.netflix.com/search?q=$q"
        "prime" -> "https://www.amazon.com/gp/video/search?phrase=$q"
        "disney" -> "https://www.disneyplus.com/search?q=$q"
        "hulu" -> "https://www.hulu.com/search?q=$q"
        "paramount" -> "https://www.paramountplus.com/search/?q=$q"
        "tubi" -> "https://tubitv.com/search/$q"
        "youtube" -> "https://www.youtube.com/results?search_query=" + Uri.encode("${t.title} ${t.year} full movie")
        else -> "https://www.google.com/search?q=" + Uri.encode("watch ${t.title} ${t.year}")
    }
}

/** In the service's app if it takes the link, else in the browser. */
fun openOn(ctx: Context, service: String, t: Title) {
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl(service, t))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val inApp = APPS[service]?.let { pkg -> runCatching { ctx.startActivity(Intent(view).setPackage(pkg)) }.isSuccess } ?: false
    if (!inApp) runCatching { ctx.startActivity(view) }
}

private fun browse(ctx: Context, url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

/** A service's colours for its badge. */
private fun brand(key: String): Pair<Color, Color> = when (key) {
    "netflix" -> Color(0xFFE50914) to Color.White
    "prime" -> Color(0xFF00A8E1) to Color.White
    "disney" -> Color(0xFF113CCF) to Color.White
    "hulu" -> Color(0xFF1CE783) to Color.Black
    "paramount" -> Color(0xFF0064FF) to Color.White
    "tubi" -> Color(0xFF7408FF) to Color.White
    "youtube" -> Color(0xFFFF0000) to Color.White
    else -> Color.Gray to Color.White
}

@Composable
fun ServiceBadge(label: String, key: String) {
    val (bg, fg) = brand(key)
    Text(label, Modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 5.dp, vertical = 1.dp),
        color = fg, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
}

/** "123K", "1.2M". */
fun votes(n: Int) = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000f)
    n >= 1000 -> "${n / 1000}K"
    else -> "$n"
}

@Composable
fun ImdbScore(t: Title, big: Boolean = false) {
    if (t.imdbRating <= 0f) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Star, null, Modifier.size(if (big) 18.dp else 14.dp), tint = Color(0xFFF5C518))
        Spacer(Modifier.width(3.dp))
        Text("%.1f".format(t.imdbRating), fontWeight = FontWeight.SemiBold,
            style = if (big) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge)
        Text(" IMDb · ${votes(t.imdbVotes)}", color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = if (big) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.labelSmall)
    }
}

// ---------- the tab ----------

private const val SAVED = "saved"
private val SORTS = listOf("Popular", "IMDb rating", "Newest")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchScreen(watch: Watch, query: String, list: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(), open: (Title) -> Unit) {
    val scope = rememberCoroutineScope()
    val titles by watch.titles.collectAsStateWithLifecycle()
    val saved by watch.saved.collectAsStateWithLifecycle()
    val refreshing by watch.refreshing.collectAsStateWithLifecycle()
    /** A service key, [SAVED], or null for all. */
    var service by rememberSaveable { mutableStateOf<String?>(null) }
    /** "movie", "tv" or null for both. */
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(SORTS[0]) }
    LaunchedEffect(Unit) { watch.refresh(force = false) }
    // To the top when a filter changes, but not on coming back to the tab (its place is kept).
    var filters by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(service, kind, genre, sort, query) {
        val now = listOf(service, kind, genre, sort, query).joinToString("|")
        if (filters.isNotEmpty() && filters != now) list.scrollToItem(0)
        filters = now
    }

    val genres = remember(titles) { titles.flatMap { it.genres }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key } }
    val shown = remember(titles, saved, service, kind, genre, sort, query) {
        val q = query.trim().lowercase()
        titles.asSequence()
            .filter { service == null || (if (service == SAVED) it.id in saved else service in it.services) }
            .filter { kind == null || it.movie == (kind == "movie") }
            .filter { genre == null || genre in it.genres }
            .filter { q.isEmpty() || it.title.lowercase().contains(q) || it.cast.any { c -> c.lowercase().contains(q) } || it.by.any { c -> c.lowercase().contains(q) } }
            .let { s ->
                when (sort) {
                    // Ratings with few votes swing wildly, so those go after the rest.
                    "IMDb rating" -> s.sortedWith(compareBy<Title>({ it.imdbVotes < 10_000 }, { -it.imdbRating }, { -it.imdbVotes }))
                    "Newest" -> s.sortedWith(compareBy<Title>({ -(if (it.movie) it.year else maxOf(it.year, it.lastYear)) }, { -it.popularity }))
                    else -> s.sortedByDescending { it.popularity }
                }
            }.toList()
    }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { scope.launch { watch.refresh(force = true) } }) {
        LazyColumn(Modifier.fillMaxSize(), state = list) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(service == null, { service = null }, { Text("All") })
                    FilterChip(service == SAVED, { service = if (service == SAVED) null else SAVED }, { Text("My list") },
                        leadingIcon = { Icon(Icons.Filled.Bookmark, null, Modifier.size(16.dp)) })
                    watch.services.forEach { s -> FilterChip(service == s.key, { service = if (service == s.key) null else s.key }, { Text(s.label) }) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(kind == "movie", { kind = if (kind == "movie") null else "movie" }, { Text("Movies") })
                    FilterChip(kind == "tv", { kind = if (kind == "tv") null else "tv" }, { Text("Shows") })
                    MenuChip(genre ?: "Genre", genre != null, listOf("Any genre") + genres) { genre = it.takeIf { g -> g != "Any genre" } }
                    MenuChip(sort, sort != SORTS[0], SORTS) { sort = it }
                }
            }
            if (shown.isEmpty()) item {
                Text(when {
                    titles.isEmpty() -> if (refreshing) "Loading…" else "No list yet. Pull down to refresh at home."
                    service == SAVED && saved.isEmpty() -> "Nothing saved yet. Open a title and tap Save."
                    else -> "Nothing matches."
                }, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(shown, key = { it.id }) { t -> TitleRow(watch, t, t.id in saved) { open(t) } }
            if (watch.updated > 0 && shown.isNotEmpty()) item {
                Text("${shown.size} titles · updated ${ago(watch.updated)} · from TMDB (JustWatch) and IMDb", Modifier.padding(16.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MenuChip(label: String, selected: Boolean, options: List<String>, pick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(selected, { open = true }, { Text(label) }, trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp)) })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { pick(o); open = false }) }
        }
    }
}

@Composable
fun TitleRow(watch: Watch, t: Title, saved: Boolean, open: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Poster(watch.poster(t), Modifier.width(64.dp).height(96.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (saved) Icon(Icons.Filled.Bookmark, "Saved", Modifier.padding(start = 4.dp).size(16.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(listOf(t.years, if (t.movie) "Movie" else "Show", t.length, t.rated).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(t.genres.take(3).joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            ImdbScore(t)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                t.services.forEach { s -> ServiceBadge(watch.service(s)?.label ?: s, s) }
            }
        }
    }
}

@Composable
private fun Poster(url: String?, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (url != null) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/** A title's page: its details, and buttons to open it in each service it's on. */
@Composable
fun TitleScreen(watch: Watch, t: Title) {
    val ctx = LocalContext.current
    val saved by watch.saved.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        watch.backdrop(t)?.let { AsyncImage(it, null, Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentScale = ContentScale.Crop) }
        Column(Modifier.padding(16.dp)) {
            Row {
                if (watch.backdrop(t) == null) Poster(watch.poster(t), Modifier.width(96.dp).height(144.dp).padding(end = 12.dp))
                Column {
                    Text(t.title, style = MaterialTheme.typography.headlineSmall)
                    Text(listOf(t.years, if (t.movie) "Movie" else "Show", t.length, t.rated).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(t.genres.joinToString(", "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    ImdbScore(t, big = true)
                }
            }
            Spacer(Modifier.height(16.dp))
            t.services.forEach { s ->
                val label = watch.service(s)?.label ?: s
                val (bg, fg) = brand(s)
                Button(onClick = { openOn(ctx, s, t) }, Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg)) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Watch on $label")
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { watch.toggleSaved(t) }) {
                    Icon(if (t.id in saved) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (t.id in saved) "Saved" else "Save")
                }
                if (t.trailer.isNotEmpty()) OutlinedButton(onClick = { openYouTube(ctx, t.trailer) }) {
                    Icon(Icons.Filled.SmartDisplay, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Trailer")
                }
            }
            if (t.overview.isNotEmpty()) Text(t.overview, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyLarge)
            if (t.by.isNotEmpty()) Detail(if (t.movie) "Directed by" else "Created by", t.by.joinToString(", "))
            if (t.cast.isNotEmpty()) Detail("Starring", t.cast.joinToString(", "))
            Spacer(Modifier.height(12.dp))
            if (t.imdb.isNotEmpty()) TextButton(onClick = { browse(ctx, "https://www.imdb.com/title/${t.imdb}/") }) {
                Text("IMDb page")
                Spacer(Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(16.dp))
            }
            // TMDB's where-to-watch page links straight to the title in each service (the buttons above search for it).
            TextButton(onClick = { browse(ctx, "https://www.themoviedb.org/${if (t.movie) "movie" else "tv"}/${t.tmdbId}/watch?locale=US") }) {
                Text("Direct links to watch (TMDB)")
                Spacer(Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun Detail(label: String, text: String) {
    Text(label, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    Text(text, style = MaterialTheme.typography.bodyMedium)
}
