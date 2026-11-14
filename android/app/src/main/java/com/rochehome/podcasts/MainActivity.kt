package com.rochehome.podcasts

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Speed
import androidx.compose.foundation.Canvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material.icons.filled.FiberNew
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil3.compose.AsyncImage
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Opened by a voice request: it decides what plays, so last time's queue isn't put back over it. */
@Volatile var voiceRequested = false

class MainActivity : ComponentActivity() {
    private var controller by mutableStateOf<MediaController?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val ask = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) ask += Manifest.permission.POST_NOTIFICATIONS
        // Android 17+: reaching Home Assistant on the home network is a runtime permission.
        if (Build.VERSION.SDK_INT >= 37) ask += "android.permission.ACCESS_LOCAL_NETWORK"
        val missing = ask.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
        val repo = (application as PodcastApp).repo
        takeSearch(intent)
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            MaterialTheme(colorScheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) {
                Surface(Modifier.fillMaxSize()) { App(repo, (application as PodcastApp).news, controller) }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray, deviceId: Int) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults, deviceId)
        val repo = (application as PodcastApp).repo
        lifecycleScope.launch { repo.refresh(everything = false) }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({ controller = runCatching { future.get() }.getOrNull() }, MoreExecutors.directExecutor())
    }

    // A spoken request from the phone's assistant ("play … on Brookstream"): the service picks what to play,
    // as it does for the car (PlaybackService.voice). Its own connection to the player, since the screen may be
    // off and locked (the activity's connection only exists while it's showing).
    private fun takeSearch(intent: Intent?) {
        if (intent?.action != android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        val q = intent.getStringExtra(android.app.SearchManager.QUERY).orEmpty()
        voiceRequested = true
        intent.action = Intent.ACTION_MAIN   // not again on rotation
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(applicationContext, token).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            c.setMediaItem(androidx.media3.common.MediaItem.Builder()
                .setRequestMetadata(androidx.media3.common.MediaItem.RequestMetadata.Builder().setSearchQuery(q).build()).build())
            c.prepare()
            c.play()
            android.os.Handler(mainLooper).postDelayed({ c.release() }, 10_000)
        }, MoreExecutors.directExecutor())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeSearch(intent)
    }

    override fun onStop() {
        controller?.release()
        controller = null
        super.onStop()
    }
}

/** What the UI needs from the player, refreshed twice a second. */
class Playing(val id: String?, val title: String, val show: String, val art: String?, val playing: Boolean,
              /** Radio: the song on air (from the stream), if it says. */
              val song: String?, val live: Boolean,
              val position: Long, val duration: Long, val speed: Float, val queue: List<String>, val index: Int,
              /** Playing the episode's ad-free copy ([AdFree]): the position is in its time. */
              val clean: Boolean = false)

@Composable
fun rememberPlaying(c: MediaController?): Playing? {
    var state by remember { mutableStateOf<Playing?>(null) }
    LaunchedEffect(c) {
        while (true) {
            state = c?.currentMediaItem?.let { item ->
                val name = item.mediaMetadata.title?.toString().orEmpty()
                Playing(item.mediaId, name, item.mediaMetadata.artist?.toString().orEmpty(),
                    item.mediaMetadata.artworkUri?.toString(), c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING),
                    item.mediaMetadata.extras?.getString("song"),
                    item.mediaId.startsWith(Station.PREFIX),
                    c.currentPosition, c.duration.coerceAtLeast(0), c.playbackParameters.speed,
                    (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }, c.currentMediaItemIndex,
                    PlaybackService.cleanOf(item))
            }
            delay(500)
        }
    }
    return state
}

private val SPEEDS = listOf(1f, 1.2f, 1.5f, 1.75f, 2f, 0.8f)
private val CATEGORIES = listOf("News & Politics", "Ideas & Culture", "Science", "History & Legends", "Mysteries & UFOs", "Tech",
    "Comedy & Entertainment", "Giants", "Learning")
private fun catRank(c: String) = CATEGORIES.indexOf(c).let { if (it < 0) CATEGORIES.size else it }

/** Everything an episode row can do. */
class Actions(
    val play: (Episode) -> Unit,
    val playNext: (Episode) -> Unit,
    val enqueue: (Episode) -> Unit,
    val setPlayed: (Episode, Boolean) -> Unit,
    val openShow: (String) -> Unit,
    val openEpisode: (Episode) -> Unit,
    val watch: (Episode, Int) -> Unit,
    /** YouTube episodes opened from here. */
    val opened: Set<String> = emptySet(),
)

/** Opens a video in the YouTube app (the browser if it isn't installed), at [start] seconds. */
fun openYouTube(ctx: android.content.Context, videoId: String, start: Int = 0) {
    val url = "https://www.youtube.com/watch?v=$videoId" + (if (start > 0) "&t=${start}s" else "")
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Web addresses (with or without http, or a bare domain like example.com/offer) and email addresses in plain text. */
private val LINK = Regex(
    "(?i)\\b(?:https?://[^\\s<>\"]+|www\\.[^\\s<>\"]+|[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+" +
        "|(?:[a-z0-9-]+\\.)+(?:com|org|net|io|co|fm|ly|me|tv|app|us|uk|ca|au|gg|dev|show|link|info|biz|edu|gov|ai|to|be|so|xyz|shop|store|news)" +
        "(?:/[^\\s<>\"]*)?\\b/?)")

/** [text] with its web and email addresses made tappable; they open in the browser or mail app. */
fun linkify(text: String, color: Color): AnnotatedString = buildAnnotatedString {
    val style = TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))
    var at = 0
    for (m in LINK.findAll(text)) {
        // Sentence punctuation and an unmatched closing bracket belong to the text, not the address.
        var link = m.value.trimEnd('.', ',', ';', ':', '!', '?', '\'', '"', '…')
        if (link.endsWith(')') && link.count { it == '(' } < link.count { it == ')' }) link = link.dropLast(1)
        if (link.isEmpty()) continue
        append(text, at, m.range.first)
        val url = when {
            link.contains("://") -> link
            '@' in link && '/' !in link -> "mailto:$link"
            else -> "https://$link"
        }
        withLink(LinkAnnotation.Url(url, style)) { append(link) }
        at = m.range.first + link.length
    }
    append(text, at, text.length)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(repo: Repo, news: News, controller: MediaController?) {
    val scope = rememberCoroutineScope()
    val shows by repo.shows.collectAsStateWithLifecycle()
    val episodes by repo.episodes.collectAsStateWithLifecycle()
    val progress by repo.progress.collectAsStateWithLifecycle()
    val status by repo.status.collectAsStateWithLifecycle()
    val refreshing by repo.refreshing.collectAsStateWithLifecycle()
    val opened by repo.opened.collectAsStateWithLifecycle()
    val playing = rememberPlaying(controller)
    var tab by rememberSaveable { mutableIntStateOf(1) }
    if (tab == 3 || tab == 0) tab = 1   // Discover and Latest are both part of Shows now
    var openShowId by rememberSaveable { mutableStateOf<String?>(null) }
    /** An episode's page, opened from Latest or Search; a show opened from it goes on top. */
    var openEpisodeId by rememberSaveable { mutableStateOf<String?>(null) }
    /** Each tab's scroll position, kept while another tab or a page is open; tapping the tab again goes back to the top. */
    val latestList = rememberLazyListState()
    val showsGrid = rememberLazyGridState()
    val discoverList = rememberLazyListState()
    val booksGrid = rememberLazyGridState()
    val radioList = rememberLazyListState()
    val newsList = rememberLazyListState()
    val watchList = rememberLazyListState()
    var openBookId by rememberSaveable { mutableStateOf<String?>(null) }
    /** A movie or show from the Watch tab. */
    var openWatchId by rememberSaveable { mutableStateOf<String?>(null) }
    /** The Bible reader, when open: "book:chapter" to open at, or "" for where it was left. */
    var bibleAt by rememberSaveable { mutableStateOf<String?>(null) }
    var fullPlayer by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("player", 0)
    /** The Shows tab's view: the newest episodes (what Latest was) or the shows. */
    var episodesView by rememberSaveable { mutableStateOf(prefs.getBoolean("episodesView", true)) }
    val snack = remember { SnackbarHostState() }

    val updated by repo.updated.collectAsStateWithLifecycle()

    // New lists on opening, then every 15 minutes while the app is showing; the clock for "5 min ago" ticks meanwhile.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                Clock.now = System.currentTimeMillis()
                if (Clock.now - repo.lastRefresh > 15 * 60_000L) repo.refresh(everything = false)
                delay(30_000)
            }
        }
    }

    // The queue from last time, back in the player (not playing) once the episodes are known.
    LaunchedEffect(controller, episodes.isNotEmpty()) {
        val c = controller ?: return@LaunchedEffect
        if (c.mediaItemCount > 0 || episodes.isEmpty() || voiceRequested) return@LaunchedEffect
        val (eps, cur) = repo.loadQueue()
        if (eps.isEmpty()) return@LaunchedEffect
        val p = progress[eps[cur].id]
        c.setMediaItems(eps.map { PlaybackService.item(it, repo.show(it.showId)) }, cur, AdFree.startMs(eps[cur].id, p, back = 0))
        c.setPlaybackSpeed(prefs.getFloat("speed", 1f))
    }

    fun start(c: MediaController) {
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    // Radio picks up live, not where it was paused.
    fun resume(c: MediaController, live: Boolean) {
        if (live) c.seekToDefaultPosition()
        start(c)
    }

    val radio = remember { (ctx.applicationContext as PodcastApp).radio }
    val bible = remember { Bible(ctx.applicationContext) }
    val watch = remember { (ctx.applicationContext as PodcastApp).watch }
    val watchTitles by watch.titles.collectAsStateWithLifecycle()

    // A station takes the place of what's playing now, like an episode; the rest of the queue stays.
    fun playStation(st: Station) {
        val c = controller ?: return
        if (c.currentMediaItem?.mediaId == st.id) { if (!c.isPlaying) resume(c, true); fullPlayer = true; return }
        val item = PlaybackService.station(st)
        if (c.mediaItemCount == 0) c.setMediaItem(item) else {
            val cur = c.currentMediaItemIndex
            c.addMediaItem(cur, item)
            c.seekTo(cur, C.TIME_UNSET)
            c.removeMediaItem(cur + 1)
        }
        c.setPlaybackSpeed(1f)
        start(c)
        scope.launch { radio.played(st) }
    }

    fun indexOf(c: MediaController, id: String) = (0 until c.mediaItemCount).firstOrNull { c.getMediaItemAt(it).mediaId == id }

    val actions = Actions(
        play = play@{ e ->
            if (e.youtubeOnly) { repo.markOpened(e.id); openYouTube(ctx, e.videoId); return@play }
            val c = controller ?: return@play
            if (c.currentMediaItem?.mediaId == e.id) { start(c); fullPlayer = true; return@play }
            val p = progress[e.id]
            val startMs = AdFree.startMs(e.id, p)
            val queued = indexOf(c, e.id)
            if (queued != null) {
                c.seekTo(queued, startMs)
            } else if (c.mediaItemCount == 0) {
                c.setMediaItem(PlaybackService.item(e, repo.show(e.showId)), startMs)
            } else {
                // Takes the place of the episode playing now; the rest of the queue stays.
                val cur = c.currentMediaItemIndex
                c.addMediaItem(cur, PlaybackService.item(e, repo.show(e.showId)))
                c.seekTo(cur, startMs)
                c.removeMediaItem(cur + 1)
            }
            c.setPlaybackSpeed(prefs.getFloat("speed", 1f))
            start(c)
            if (p == null || p.done) repo.setProgress(e.id, e.showId, 0, p?.dur ?: e.duration, false)
            scope.launch { repo.requestAdFree(e) }
        },
        playNext = { e ->
            controller?.let { c ->
                indexOf(c, e.id)?.let { c.removeMediaItem(it) }
                val at = if (c.mediaItemCount == 0) 0 else c.currentMediaItemIndex + 1
                c.addMediaItem(at, PlaybackService.item(e, repo.show(e.showId)))
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                scope.launch { snack.showSnackbar("Playing next") }
                scope.launch { repo.requestAdFree(e) }
            }
        },
        enqueue = { e ->
            controller?.let { c ->
                if (indexOf(c, e.id) == null) {
                    c.addMediaItem(PlaybackService.item(e, repo.show(e.showId)))
                    if (c.playbackState == Player.STATE_IDLE) c.prepare()
                }
                scope.launch { snack.showSnackbar("Added to Up next") }
                scope.launch { repo.requestAdFree(e) }
            }
        },
        setPlayed = { e, played -> repo.setPlayed(e, played) },
        openShow = { id -> openShowId = id; openBookId = null },
        openEpisode = { e -> openEpisodeId = e.id; openShowId = null; openBookId = null },
        watch = { e, start ->
            controller?.let { c -> if (c.isPlaying) c.pause() }
            repo.markOpened(e.id)
            openYouTube(ctx, e.videoId, start)
        },
        opened = opened,
    )

    // A book plays from part [from] to the end, in place of whatever was queued.
    fun playBook(b: Book, from: Int) {
        val c = controller ?: return
        val e = b.chapters.getOrNull(from) ?: return
        if (c.currentMediaItem?.mediaId == e.id) { start(c); fullPlayer = true; return }
        val p = progress[e.id]
        val startMs = if (p != null && !p.done) ((p.t - 5).coerceAtLeast(0) * 1000L) else 0L
        c.setMediaItems(b.chapters.drop(from).map { PlaybackService.item(it, b.show) }, 0, startMs)
        c.setPlaybackSpeed(prefs.getFloat("speed", 1f))
        start(c)
        if (p == null || p.done) repo.setProgress(e.id, b.id, 0, p?.dur ?: e.duration, false)
    }

    // Undo puts it back: Home Assistant's shows are only hidden on this phone.
    fun unsubscribe(s: Show) {
        repo.unsubscribe(s.id)
        scope.launch {
            if (snack.showSnackbar("Unsubscribed from ${s.title}", "Undo", duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) repo.subscribe(s)
        }
    }

    BackHandler(enabled = fullPlayer || bibleAt != null || openWatchId != null || openShowId != null || openEpisodeId != null || openBookId != null || searching) {
        when {
            fullPlayer -> fullPlayer = false
            bibleAt != null -> bibleAt = null
            openWatchId != null -> openWatchId = null
            openShowId != null -> openShowId = null
            openEpisodeId != null -> openEpisodeId = null
            openBookId != null -> openBookId = null
            else -> { searching = false; query = "" }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            val show = openShowId?.let { repo.show(it) }
            val book = openBookId?.let { repo.book(it) }
            val episode = openEpisodeId.takeIf { show == null }?.let { repo.episode(it) }
            val title = openWatchId?.let { id -> watchTitles.firstOrNull { it.id == id } }
            TopAppBar(
                title = {
                    when {
                        bibleAt != null -> Text("Bible")
                        title != null -> Text(title.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        show != null -> Text(show.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        episode != null -> Text(repo.show(episode.showId)?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        book != null -> Text(book.show.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        searching -> SearchField(query, if (tab == 6) "Search movies, shows, actors" else "Search shows, episodes, all podcasts") { query = it }
                        else -> Image(painterResource(R.drawable.logo), "Brookstream", Modifier.size(40.dp))
                    }
                },
                navigationIcon = {
                    if (bibleAt != null) IconButton(onClick = { bibleAt = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    else if (title != null) IconButton(onClick = { openWatchId = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    else if (show != null) IconButton(onClick = { openShowId = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    else if (episode != null) IconButton(onClick = { openEpisodeId = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    else if (book != null) IconButton(onClick = { openBookId = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    else if (searching) IconButton(onClick = { searching = false; query = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") }
                },
                actions = {
                    if (bibleAt == null && title == null && show == null && episode == null && book == null && !searching) IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "Search") }
                    if (searching && query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear") }
                },
            )
        },
        bottomBar = {
            // Without the tab bar, keep the mini player clear of the gesture bar.
            val tabs = bibleAt == null && openWatchId == null && openShowId == null && openEpisodeId == null && openBookId == null && !searching
            Column(if (tabs) Modifier else Modifier.navigationBarsPadding()) {
                if (playing != null) MiniPlayer(playing, onOpen = { fullPlayer = true }, onToggle = { controller?.let { c -> if (c.isPlaying) c.pause() else resume(c, playing.live) } })
                // The tab you're on, tapped again: back to its top.
                fun select(t: Int) {
                    if (t != tab) { tab = t; return }
                    scope.launch {
                        when (t) {
                            1 -> when {
                                episodesView -> latestList.animateScrollToItem(0)
                                category in DISCOVER -> discoverList.animateScrollToItem(0)
                                else -> showsGrid.animateScrollToItem(0)
                            }
                            4 -> booksGrid.animateScrollToItem(0)
                            5 -> radioList.animateScrollToItem(0)
                            2 -> newsList.animateScrollToItem(0)
                            6 -> watchList.animateScrollToItem(0)
                        }
                    }
                }
                if (tabs) NavigationBar {
                    NavigationBarItem(selected = tab == 1, onClick = { select(1) }, icon = { Icon(Icons.Filled.GridView, null) }, label = { Text("Shows") })
                    NavigationBarItem(selected = tab == 4, onClick = { select(4) }, icon = { Icon(Icons.Filled.AutoStories, null) }, label = { Text("Books") })
                    NavigationBarItem(selected = tab == 5, onClick = { select(5) }, icon = { Icon(Icons.Filled.Radio, null) }, label = { Text("Radio") })
                    NavigationBarItem(selected = tab == 2, onClick = { select(2) }, icon = { Icon(Icons.Filled.Newspaper, null) }, label = { Text("News") })
                    // Home build only for now (the shareable one has no list to show).
                    if (BuildConfig.HA_URL.isNotEmpty()) NavigationBarItem(selected = tab == 6, onClick = { select(6) }, icon = { Icon(Icons.Filled.Movie, null) }, label = { Text("Watch") })
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val show = openShowId?.let { repo.show(it) }
            val book = openBookId?.let { repo.book(it) }
            val episode = openEpisodeId.takeIf { show == null }?.let { id -> episodes.values.firstNotNullOfOrNull { l -> l.firstOrNull { it.id == id } } ?: repo.episode(id) }
            when {
                openWatchId != null && watchTitles.any { it.id == openWatchId } -> TitleScreen(watch, watchTitles.first { it.id == openWatchId })
                tab == 6 -> WatchScreen(watch, if (searching) query else "", watchList) { openWatchId = it.id }
                bibleAt != null -> BibleScreen(bible, bibleAt?.split(":")?.takeIf { it.size == 2 }?.let { (b, c) -> b.toInt() to c.toInt() })
                book != null && show == null -> BookScreen(book, repo.hasText(book), progress, playing?.id, actions, ::playBook,
                    read = if (book.show.category == "Bible" && bible.available) ({
                        // The text of the part you're on (or the first).
                        val ref = bible.refOf(book.chapters[resumeAt(book, progress) ?: 0].title)
                        bibleAt = ref?.let { "${it.first}:${it.second}" } ?: ""
                    }) else null)
                episode != null -> EpisodeScreen(repo, episode, progress[episode.id], episode.id == playing?.id, actions)
                show != null -> ShowScreen(repo, show, shows.any { it.id == show.id }, episodes[show.id].orEmpty(), progress, playing?.id, prefs, actions, ::unsubscribe)
                searching -> SearchResults(repo, query, shows, episodes, progress, playing?.id, actions,
                    open = { s -> repo.preview(s); openShowId = s.id }, unsubscribe = ::unsubscribe)
                tab == 2 -> NewsScreen(news, newsList)
                tab == 4 -> BooksScreen(repo.books, progress, readBible = if (bible.available) ({ bibleAt = "" }) else null, listState = booksGrid) { openBookId = it.id }
                tab == 5 -> RadioScreen(radio, playing?.id, playing?.playing == true, radioList, ::playStation)
                else -> PullToRefreshBox(isRefreshing = refreshing, onRefresh = { scope.launch { repo.refresh(everything = true) } }) {
                    // Episodes offer In Progress, New and Complete among the categories; Shows offer For you and Top charts.
                    val cat = category.takeIf { if (!episodesView) it !in STATUS else it !in DISCOVER }
                    // The Episodes / Shows switch and the chips scroll away with the list, rather than staying pinned.
                    val header: @Composable () -> Unit = {
                        Column {
                            SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 16.dp, vertical = 2.dp).width(220.dp).height(32.dp)) {
                                listOf(true to "Episodes", false to "Shows").forEachIndexed { i, (v, label) ->
                                    SegmentedButton(selected = episodesView == v, onClick = { episodesView = v; prefs.edit { putBoolean("episodesView", v) } },
                                        shape = SegmentedButtonDefaults.itemShape(i, 2), icon = {},
                                        label = { Text(label, style = MaterialTheme.typography.labelMedium) })
                                }
                            }
                            CategoryChips(shows, cat, extra = if (!episodesView) DISCOVER else STATUS) { category = it }
                        }
                    }
                    when {
                        episodesView -> LatestScreen(shows, episodes, progress, status, updated, playing?.id, cat, actions, latestList, header)
                        cat in DISCOVER -> DiscoverList(repo, shows, charts = cat == TOP, discoverList, header,
                            open = { s -> repo.preview(s); openShowId = s.id }, unsubscribe = ::unsubscribe)
                        else -> ShowsGrid(shows, cat, showsGrid, header, open = { openShowId = it.id }, unsubscribe = ::unsubscribe)
                    }
                }
            }
        }
    }

    if (fullPlayer && playing != null && controller != null) {
        ModalBottomSheet(onDismissRequest = { fullPlayer = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            if (playing.live) LivePlayer(radio, playing, controller) { resume(controller, true) }
            else FullPlayer(repo, playing, controller, onSpeed = { s -> prefs.edit { putFloat("speed", s) } },
                onWatch = { e -> actions.watch(e, (playing.position / 1000).toInt()) })
        }
    }
}

@Composable
fun SearchField(query: String, hint: String = "Search shows, episodes, all podcasts", onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TextField(query, onChange, Modifier.fillMaxWidth().focusRequester(focus), placeholder = { Text(hint) }, singleLine = true,
        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
}

// ---------- lists ----------

@Composable
fun CategoryChips(shows: List<Show>, selected: String?, extra: List<String> = emptyList(), onSelect: (String?) -> Unit) {
    val cats = remember(shows) { shows.map { it.category }.distinct().sortedBy { catRank(it) } }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected == null, { onSelect(null) }, { Text("All") })
        extra.forEach { c ->
            FilterChip(selected == c, { onSelect(if (selected == c) null else c) }, { Text(c) },
                leadingIcon = { Icon(chipIcon(c), null, Modifier.size(16.dp)) })
        }
        cats.forEach { c -> FilterChip(selected == c, { onSelect(if (selected == c) null else c) }, { Text(c) }) }
    }
}

private fun chipIcon(c: String) = when (c) {
    TOP -> Icons.AutoMirrored.Filled.TrendingUp
    IN_PROGRESS -> Icons.Filled.Timelapse
    NEW -> Icons.Filled.FiberNew
    COMPLETE -> Icons.Filled.CheckCircle
    else -> Icons.Filled.AutoAwesome
}

/** Latest, newest first; played and half-played episodes stay in their place. [category] may be a show category or one of [STATUS]. */
@Composable
fun LatestScreen(shows: List<Show>, episodes: Map<String, List<Episode>>, progress: Map<String, Progress>,
                 status: String, updated: Long, current: String?, category: String?, actions: Actions, listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
                 header: @Composable () -> Unit = {}) {
    val showById = remember(shows) { shows.associateBy { it.id } }
    val byId = remember(episodes, showById) { episodes.filterKeys { it in showById }.values.flatten().associateBy { it.id } }
    val state = category.takeIf { it in STATUS }
    val cat = category.takeIf { it !in STATUS }
    fun started(e: Episode) = progress[e.id].let { it != null && !it.done && it.t > 0 }
    fun keep(e: Episode) = e.showId in showById && (cat == null || showById[e.showId]?.category == cat) && when (state) {
        IN_PROGRESS -> started(e)
        NEW -> progress[e.id].let { it == null || (!it.done && it.t == 0) } && e.id !in actions.opened
        COMPLETE -> progress[e.id]?.done == true
        else -> true
    }
    val latest = remember(episodes, progress, category, showById, actions.opened) {
        val newest = episodes.values.flatMap { list -> list.filter { !it.youtubeOnly }.take(3) + list.filter { it.youtubeOnly }.take(2) }
        // In Progress and Complete look through every loaded episode; any half-played one also joins the rest in date order.
        val pool = if (state == IN_PROGRESS || state == COMPLETE) byId.values else newest + byId.values.filter(::started)
        pool.distinctBy { it.id }.filter(::keep).sortedByDescending { it.date }.take(if (state == null) 60 else 100)
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item(key = "header") { header() }
        if (status.isNotEmpty()) item { Text(status, Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item {
            Row(verticalAlignment = Alignment.Bottom) {
                Header(state ?: "Latest")
                if (updated > 0) Text("Updated " + ago(updated).replace("Just now", "just now").replace("Yesterday", "yesterday"), Modifier.padding(bottom = 5.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (latest.isEmpty()) item { Text(if (state == null) "Nothing here. Pull down to refresh." else "Nothing here.", Modifier.padding(16.dp)) }
        items(latest, key = { "l" + it.id }) { e -> EpisodeRow(e, showById[e.showId], progress[e.id], e.id == current, actions) }
    }
}

@Composable
fun Header(text: String) = Text(text, Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp), style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)

@Composable
fun ShowsGrid(shows: List<Show>, category: String?, state: androidx.compose.foundation.lazy.grid.LazyGridState = rememberLazyGridState(),
              header: @Composable () -> Unit = {}, open: (Show) -> Unit, unsubscribe: (Show) -> Unit) {
    val groups = remember(shows, category) {
        shows.filter { category == null || it.category == category }.sortedByDescending { it.newest }
            .groupBy { it.category }.toList().sortedBy { catRank(it.first) }
    }
    LazyVerticalGrid(GridCells.Adaptive(108.dp), Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Full width, past the grid's own padding, so it lines up with the Episodes view.
        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.layout { m, c ->
                val pad = 12.dp.roundToPx()
                val p = m.measure(c.copy(minWidth = 0, maxWidth = c.maxWidth + 2 * pad))
                layout(c.maxWidth, p.height) { p.place(-pad, -pad) }
            }) { header() }
        }
        groups.forEach { (cat, list) ->
            if (category == null) item(key = "h$cat", span = { GridItemSpan(maxLineSpan) }) {
                Text(cat, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            items(list, key = { it.id }) { s -> ShowCard(s, open = { open(s) }, unsubscribe = { unsubscribe(s) }) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShowCard(s: Show, open: () -> Unit, unsubscribe: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(Modifier.combinedClickable(onClick = open, onLongClick = { menu = true })) {
            Art(s.art, Modifier.fillMaxWidth().aspectRatio(1f))
            Text(s.title, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (s.newest > 0) Text(ago(s.newest), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Unsubscribe") }, onClick = { menu = false; unsubscribe() })
        }
    }
}

@Composable
fun SearchResults(repo: Repo, query: String, shows: List<Show>, episodes: Map<String, List<Episode>>, progress: Map<String, Progress>,
                  current: String?, actions: Actions, open: (Show) -> Unit, unsubscribe: (Show) -> Unit) {
    val q = query.trim()
    val scope = rememberCoroutineScope()
    // Apple's directory too, once typing pauses: shows not in the list yet.
    var more by remember { mutableStateOf<List<Show>?>(null) }
    LaunchedEffect(q) {
        more = null
        if (q.length < 2) return@LaunchedEffect
        delay(500)
        more = repo.searchDirectory(q)
    }
    val showById = remember(shows) { shows.associateBy { it.id } }
    val foundShows = remember(q, shows) { if (q.length < 2) emptyList() else shows.filter { it.title.contains(q, true) || it.author.contains(q, true) } }
    val foundEps = remember(q, episodes, showById) {
        if (q.length < 2) emptyList() else episodes.filterKeys { it in showById }.values.flatten().distinctBy { it.id }
            .filter { it.title.contains(q, true) || it.desc.contains(q, true) }.sortedByDescending { it.date }.take(100)
    }
    LazyColumn(Modifier.fillMaxSize()) {
        if (q.length < 2) item { Text("Searches your shows, the episodes loaded so far (the newest of every show, all of each show you've opened) and Apple's podcast directory.",
            Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (foundShows.isNotEmpty()) {
            item { Header("Shows") }
            items(foundShows, key = { "s" + it.id }) { s ->
                Row(Modifier.fillMaxWidth().clickable { actions.openShow(s.id) }.padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Art(s.art, Modifier.size(48.dp))
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(s.title, style = MaterialTheme.typography.bodyLarge)
                        Text(s.category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (foundEps.isNotEmpty()) {
            item { Header("Episodes") }
            items(foundEps, key = { "e" + it.id }) { e -> EpisodeRow(e, showById[e.showId], progress[e.id], e.id == current, actions) }
        }
        val mine = shows.map { it.id }.toSet()
        val others = more.orEmpty().filter { it.id !in mine }
        if (others.isNotEmpty()) {
            item { Header("More podcasts") }
            items(others, key = { "m" + it.id }) { s ->
                DiscoverRow(s, listed = false, open = { open(s) }, subscribe = { scope.launch { repo.subscribe(s) } }, unsubscribe = {})
            }
        }
        if (q.length >= 2 && foundShows.isEmpty() && foundEps.isEmpty() && more != null && others.isEmpty())
            item { Text("Nothing found for “$q”.", Modifier.padding(16.dp)) }
    }
}

@Composable
fun ShowScreen(repo: Repo, show: Show, subscribed: Boolean, eps: List<Episode>, progress: Map<String, Progress>, current: String?, prefs: SharedPreferences, actions: Actions,
               unsubscribe: (Show) -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember(show.id) { mutableStateOf(true) }
    var oldest by remember(show.id) { mutableStateOf(prefs.getBoolean("old_${show.id}", false)) }
    var unplayed by remember(show.id) { mutableStateOf(prefs.getBoolean("unplayed_${show.id}", false)) }
    LaunchedEffect(show.id) { repo.loadShow(show); loading = false }
    val list = remember(eps, oldest, unplayed, progress) {
        eps.filter { !unplayed || progress[it.id]?.done != true }.let { if (oldest) it.sortedBy { e -> e.date } else it }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Art(show.art, Modifier.size(112.dp))
                Column(Modifier.padding(start = 16.dp)) {
                    Text(show.title, style = MaterialTheme.typography.titleLarge)
                    if (show.author.isNotEmpty()) Text(show.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(show.category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    if (!subscribed) FilledTonalButton(onClick = { scope.launch { repo.subscribe(show) } }, Modifier.padding(top = 8.dp)) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Subscribe")
                    } else OutlinedButton(onClick = { unsubscribe(show) }, Modifier.padding(top = 8.dp)) { Text("Unsubscribe") }
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(oldest, { oldest = !oldest; prefs.edit { putBoolean("old_${show.id}", oldest) } }, { Text(if (oldest) "Oldest first" else "Newest first") })
                FilterChip(unplayed, { unplayed = !unplayed; prefs.edit { putBoolean("unplayed_${show.id}", unplayed) } }, { Text("Unplayed only") })
            }
        }
        if (loading && eps.size <= 3) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
        if (!loading && eps.isEmpty()) item { Text("Couldn't load this show's episodes.", Modifier.padding(16.dp)) }
        items(list, key = { it.id }) { e -> EpisodeRow(e, null, progress[e.id], e.id == current, actions) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EpisodeRow(e: Episode, show: Show?, p: Progress?, current: Boolean, actions: Actions) {
    var menu by remember { mutableStateOf(false) }
    val opened = e.id in actions.opened
    Box {
        // In a list of many shows a tap opens the episode's page; on the show's own page it plays.
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = { if (show != null) actions.openEpisode(e) else actions.play(e) }, onLongClick = { menu = true })
            .background(if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            // Big enough that the row's height is set by its text (with room kept for the progress bar).
            if (show != null) Art(show.art, Modifier.size(84.dp).padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(remember(e.title) { Headline.tame(e.title) }, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (p?.done == true || opened) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                // Show name (bold, cut short when long) and tags on the left; date and length always whole on the right.
                val stamp = listOfNotNull(ago(e.date).takeIf { e.date > 0 },
                    when {
                        p?.done == true -> "Played"
                        p != null && p.t > 0 && p.dur > 0 -> "${mins(p.dur - p.t)} left"
                        e.duration > 0 -> mins(e.duration)
                        else -> null
                    })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (show != null) Text(show.title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (e.youtubeOnly) Image(painterResource(R.drawable.youtube), "YouTube", Modifier.size(17.dp, 12.dp))
                        if (!e.youtubeOnly && e.videoId.isNotEmpty()) Tag("Video")
                        if (e.transcriptUrl.isNotEmpty()) Tag("Transcript")
                        if (AdFree.info(e.id)?.let { it.cuts.isNotEmpty() || it.skip.isNotEmpty() } == true) NoAdsBadge()
                        if (opened && e.youtubeOnly && p?.done != true) Text("Opened", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                    }
                    Text(stamp.joinToString(" · "), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                }
                val desc = remember(e.desc) { Repo.cleanDesc(e.desc) }
                if (desc.isNotEmpty()) Text(desc, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // The bar's room is always kept, so starting an episode doesn't make its row taller.
                if (p != null && !p.done && p.t > 0) LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp),
                    drawStopIndicator = {})
                else Spacer(Modifier.height(9.dp))
            }
            if (p?.done == true) Icon(Icons.Filled.CheckCircle, "Played", Modifier.padding(start = 8.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (show != null && !e.youtubeOnly) DropdownMenuItem(text = { Text("Play") }, onClick = { actions.play(e); menu = false })
            if (e.videoId.isNotEmpty()) DropdownMenuItem(text = { Text("Watch on YouTube") },
                onClick = { actions.watch(e, if (p != null && !p.done) p.t else 0); menu = false })
            if (!e.youtubeOnly) {
                DropdownMenuItem(text = { Text("Play next") }, onClick = { actions.playNext(e); menu = false })
                DropdownMenuItem(text = { Text("Add to Up next") }, onClick = { actions.enqueue(e); menu = false })
            }
            DropdownMenuItem(text = { Text(if (p?.done == true) "Mark as unplayed" else "Mark as played") },
                onClick = { actions.setPlayed(e, p?.done != true); menu = false })
            if (show != null) DropdownMenuItem(text = { Text("Go to show") }, onClick = { actions.openShow(e.showId); menu = false })
        }
    }
}

/** One episode: what it is, its whole description, and Play. */
@Composable
fun EpisodeScreen(repo: Repo, e: Episode, p: Progress?, current: Boolean, actions: Actions) {
    val show = repo.show(e.showId)
    // The lists carry a short description; the show's own file has the longer one.
    LaunchedEffect(e.id) { if (show != null) runCatching { repo.loadShow(show) } }
    var second by remember(e.id) { mutableStateOf<Extras?>(null) }
    LaunchedEffect(e.id) { second = repo.secondPass(e) }
    val started = p != null && !p.done && p.t > 0
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Art(show?.art, Modifier.size(96.dp))
                Column(Modifier.padding(start = 16.dp)) {
                    if (show != null) Text(show.title, Modifier.clickable { actions.openShow(show.id) }, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val stamp = listOfNotNull(ago(e.date).takeIf { e.date > 0 }, mins(e.duration).takeIf { e.duration > 0 })
                    Text(stamp.joinToString(" · "), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (e.youtubeOnly) Image(painterResource(R.drawable.youtube), "YouTube", Modifier.size(17.dp, 12.dp))
                        if (!e.youtubeOnly && e.videoId.isNotEmpty()) Tag("Video")
                        if (e.transcriptUrl.isNotEmpty()) Tag("Transcript")
                    }
                }
            }
        }
        item { Text(Headline.tame(e.title), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge) }
        item {
            Row(Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (e.youtubeOnly) Button(onClick = { actions.watch(e, 0) }) { Text("Watch on YouTube") }
                else Button(onClick = { actions.play(e) }) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp))
                    Text(when {
                        current -> "Playing"
                        started && p!!.dur > 0 -> "Resume · ${mins(p.dur - p.t)} left"
                        started -> "Resume"
                        else -> "Play"
                    })
                }
                if (!e.youtubeOnly && e.videoId.isNotEmpty()) OutlinedButton(onClick = { actions.watch(e, if (started) p!!.t else 0) }) { Text("Watch") }
                OutlinedButton(onClick = { actions.setPlayed(e, p?.done != true) }) {
                    if (p?.done == true) { Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
                    Text(if (p?.done == true) "Played" else "Mark played")
                }
            }
        }
        if (!e.youtubeOnly) item {
            Row(Modifier.padding(horizontal = 8.dp)) {
                TextButton(onClick = { actions.playNext(e) }) { Text("Play next") }
                TextButton(onClick = { actions.enqueue(e) }) { Text("Add to Up next") }
            }
        }
        if (started) item {
            LinearProgressIndicator(progress = { p!!.fraction }, modifier = Modifier.fillMaxWidth().padding(16.dp, 4.dp).height(4.dp), drawStopIndicator = {})
        }
        // The second pass, when the laptop has done it: a summary, the description without its ads, chapter points.
        if (second?.summary?.isNotEmpty() == true) item {
            Text("Summary", Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Text(second!!.summary, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyLarge)
        }
        val desc = second?.description ?: Repo.cleanDesc(e.desc)
        if (desc.isNotEmpty()) item {
            Text(linkify(desc, MaterialTheme.colorScheme.primary), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (second?.chapters?.isNotEmpty() == true) {
            item { Text("Chapters", Modifier.padding(16.dp, 8.dp, 16.dp, 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
            items(second!!.chapters.size) { i -> ChapterBlock(second!!.chapters[i], Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
        }
    }
}

/** A chapter's time, title and points. */
@Composable
fun ChapterBlock(ch: Chapter, modifier: Modifier = Modifier, current: Boolean = false) {
    Column(modifier) {
        Row {
            Text(ch.title.ifEmpty { "Chapter" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (current) FontWeight.Bold else FontWeight.SemiBold)
            Text(clock(ch.start * 1000L), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ch.points.forEach { pt ->
            Row(Modifier.padding(top = 2.dp)) {
                Text("•", Modifier.width(14.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(pt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** "AD" struck through, small: the laptop has taken this episode's ads out (tools/podcasts/adfree.py). */
@Composable
fun NoAdsBadge() {
    val color = MaterialTheme.colorScheme.primary
    Box(Modifier.semantics { contentDescription = "Ads removed" }.border(1.dp, color, RoundedCornerShape(4.dp)).padding(horizontal = 3.dp),
        contentAlignment = Alignment.Center) {
        Text("AD", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color)
        Canvas(Modifier.matchParentSize()) {
            drawLine(color, Offset(0f, size.height), Offset(size.width, 0f), strokeWidth = 1.5.dp.toPx())
        }
    }
}

/** A small outlined label in capitals, so it reads as a tag and not part of the show's name. */
@Composable
fun Tag(text: String) = Text(text.uppercase(),
    Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)

@Composable
fun Art(url: String?, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (!url.isNullOrEmpty()) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

// ---------- discover ----------

/** The Shows tab's Discover categories. */
const val FOR_YOU = "For you"
const val TOP = "Top charts"
val DISCOVER = listOf(FOR_YOU, TOP)
const val IN_PROGRESS = "In Progress"
const val NEW = "New"
const val COMPLETE = "Complete"
val STATUS = listOf(IN_PROGRESS, NEW, COMPLETE)

/** Discover's lists, kept while the app is open. */
private var forYouCache: List<Show>? = null
private var topCache: List<Show>? = null

@Composable
fun DiscoverList(repo: Repo, shows: List<Show>, charts: Boolean, state: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
                 header: @Composable () -> Unit = {}, open: (Show) -> Unit, unsubscribe: (Show) -> Unit) {
    val scope = rememberCoroutineScope()
    var includeMine by rememberSaveable { mutableStateOf(false) }
    var forYou by remember { mutableStateOf(forYouCache) }
    var top by remember { mutableStateOf(topCache) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val mine = remember(shows) { shows.associateBy { it.id } }
    LaunchedEffect(charts) {
        failed = false
        if (charts && top == null) {
            loading = true
            top = repo.topCharts().also { topCache = it }
            failed = top == null
            loading = false
        } else if (!charts && forYou == null) {
            loading = true
            forYou = repo.recommendations().also { forYouCache = it }
            failed = forYou == null
            loading = false
        }
    }
    val found = remember(charts, top, forYou, mine, includeMine) { (if (charts) top else forYou)?.filter { includeMine || it.id !in mine } }
    Column(Modifier.fillMaxSize()) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        LazyColumn(Modifier.fillMaxSize(), state = state) {
            item(key = "header") { header() }
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp, 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (charts) "Apple's top podcasts in the US." else "Ranked by how closely each show's genres match your list.",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FilterChip(includeMine, { includeMine = !includeMine }, { Text("Include mine") })
                }
            }
            if (failed) item { Text("Couldn't reach Apple's podcast directory.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (!loading && !failed && found?.isEmpty() == true) item { Text("Nothing here that isn't already in your list.", Modifier.padding(16.dp)) }
            items(found.orEmpty(), key = { "d" + it.id }) { s ->
                val have = mine[s.id]
                DiscoverRow(s, listed = have != null, open = { open(have ?: s) },
                    subscribe = { scope.launch { repo.subscribe(s) } }, unsubscribe = { have?.let(unsubscribe) })
            }
        }
    }
}

@Composable
fun DiscoverRow(s: Show, listed: Boolean, open: () -> Unit, subscribe: () -> Unit, unsubscribe: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Art(s.art, Modifier.size(56.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(s.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(s.author, s.category).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val pad = PaddingValues(horizontal = 12.dp)
        if (listed) OutlinedButton(onClick = unsubscribe, contentPadding = pad) { Text("Unsubscribe") }
        else FilledTonalButton(onClick = subscribe, contentPadding = pad) {
            Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Subscribe")
        }
    }
}

// ---------- audiobooks ----------

/** How much of [b] has been listened to, by time. */
fun bookFraction(b: Book, progress: Map<String, Progress>): Float {
    if (b.secs <= 0) return 0f
    val heard = b.chapters.sumOf { e -> progress[e.id]?.let { p -> if (p.done) e.duration else p.t.coerceAtMost(e.duration) } ?: 0 }
    return (heard.toFloat() / b.secs).coerceIn(0f, 1f)
}

/** The part to carry on with: the last one touched, or the next after it if that one's done. Null: not started, or finished. */
fun resumeAt(b: Book, progress: Map<String, Progress>): Int? {
    val last = b.chapters.indices.filter { progress[b.chapters[it].id] != null }.maxByOrNull { progress[b.chapters[it].id]!!.at } ?: return null
    return if (progress[b.chapters[last].id]!!.done) (last + 1).takeIf { it < b.chapters.size } else last
}

private val SHELVES = listOf("Bible", "Great Novels", "Adventure & Mystery", "Science Fiction & Horror", "Philosophy & Ideas", "History & Lives",
    "Poetry & Drama", "Children's Classics")

@Composable
fun BooksScreen(books: List<Book>, progress: Map<String, Progress>, readBible: (() -> Unit)? = null,
                listState: androidx.compose.foundation.lazy.grid.LazyGridState = rememberLazyGridState(), open: (Book) -> Unit) {
    var shelf by rememberSaveable { mutableStateOf<String?>(null) }
    val shelves = remember(books) { books.map { it.show.category }.distinct().sortedBy { SHELVES.indexOf(it).let { i -> if (i < 0) 99 else i } } }
    val fractions = remember(books, progress) { books.associate { it.id to bookFraction(it, progress) } }
    val going = remember(books, progress) {
        books.filter { resumeAt(it, progress) != null }
            .sortedByDescending { b -> b.chapters.maxOf { progress[it.id]?.at ?: 0 } }
    }
    LazyVerticalGrid(GridCells.Adaptive(108.dp), Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "chips", span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(shelf == null, { shelf = null }, { Text("All") })
                shelves.forEach { c -> FilterChip(shelf == c, { shelf = if (shelf == c) null else c }, { Text(c) }) }
            }
        }
        if (shelf == null && going.isNotEmpty()) {
            item(key = "hc", span = { GridItemSpan(maxLineSpan) }) { ShelfHeader("Continue listening") }
            items(going, key = { "c" + it.id }) { b -> BookCard(b, fractions[b.id] ?: 0f) { open(b) } }
        }
        shelves.filter { shelf == null || it == shelf }.forEach { sh ->
            if (shelf == null) item(key = "h$sh", span = { GridItemSpan(maxLineSpan) }) { ShelfHeader(sh) }
            if (sh == "Bible" && readBible != null) item(key = "read", span = { GridItemSpan(maxLineSpan) }) {
                OutlinedCard(onClick = readBible, Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.padding(start = 14.dp)) {
                            Text("Read the Bible", style = MaterialTheme.typography.titleMedium)
                            Text("Berean, KJV, WEB, ASV and Young's, the Hebrew and Greek, and links to NIV, ESV and more",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            items(books.filter { it.show.category == sh }, key = { it.id }) { b -> BookCard(b, fractions[b.id] ?: 0f) { open(b) } }
        }
        item(key = "credit", span = { GridItemSpan(maxLineSpan) }) {
            Text("Public-domain recordings by LibriVox volunteers.", Modifier.padding(vertical = 8.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ShelfHeader(text: String) = Text(text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)

@Composable
fun BookCard(b: Book, fraction: Float, open: () -> Unit) {
    Column(Modifier.clickable(onClick = open)) {
        Art(b.show.art, Modifier.fillMaxWidth().aspectRatio(1f))
        Text(b.show.title, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(b.show.author, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (fraction > 0f) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp), drawStopIndicator = {})
        else Text(hours(b.secs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun hours(sec: Int) = if (sec >= 3600) "${(sec + 1800) / 3600} h" else mins(sec)

@Composable
fun BookScreen(b: Book, hasText: Boolean, progress: Map<String, Progress>, current: String?, actions: Actions, play: (Book, Int) -> Unit,
               read: (() -> Unit)? = null) {
    val fraction = bookFraction(b, progress)
    val resume = resumeAt(b, progress)
    var more by remember(b.id) { mutableStateOf(false) }
    val list = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize(), state = list) {
        item {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Art(b.show.art, Modifier.size(128.dp))
                Column(Modifier.padding(start = 16.dp)) {
                    Text(b.show.title, style = MaterialTheme.typography.titleLarge)
                    Text(b.show.author, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${mins(b.secs)} · ${b.chapters.size} parts", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    if (hasText) Text("Read along in the player", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                val label = when {
                    resume != null -> "Resume · " + b.chapters[resume].title
                    fraction > 0.99f -> "Listen again"
                    else -> "Play"
                }
                Button(onClick = { play(b, resume ?: 0) }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp))
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (read != null) OutlinedButton(onClick = read, Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp))
                    Text("Read the text")
                }
                if (fraction > 0f) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(4.dp), drawStopIndicator = {})
                    Text("${(fraction * 100).toInt()}% · ${mins((b.secs * (1 - fraction)).toInt())} left", Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (b.desc.isNotEmpty()) item {
            Text(b.desc, Modifier.fillMaxWidth().clickable { more = !more }.padding(16.dp, 12.dp), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (more) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
        }
        item { Header("Contents") }
        itemsIndexed(b.chapters, key = { _, e -> e.id }) { i, e -> PartRow(i, e, progress[e.id], e.id == current, actions) { play(b, i) } }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PartRow(i: Int, e: Episode, p: Progress?, current: Boolean, actions: Actions, play: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = play, onLongClick = { menu = true })
            .background(if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${i + 1}", Modifier.width(36.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(e.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (p?.done == true) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                Text(when {
                    p?.done == true -> "Played"
                    p != null && p.t > 0 && p.dur > 0 -> "${mins(p.dur - p.t)} left"
                    else -> mins(e.duration)
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (p != null && !p.done && p.t > 0) LinearProgressIndicator(progress = { p.fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp), drawStopIndicator = {})
            }
            if (p?.done == true) Icon(Icons.Filled.CheckCircle, "Played", Modifier.padding(start = 8.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(if (p?.done == true) "Mark as unplayed" else "Mark as played") },
                onClick = { actions.setPlayed(e, p?.done != true); menu = false })
        }
    }
}

// ---------- radio ----------

private val RADIO_GENRES = listOf("Jazz", "Classical", "News", "Talk", "Rock", "Indie", "Electronic", "Ambient", "Hip hop", "Soul",
    "Country", "Oldies", "Reggae", "Latin")
/** Radio lists fetched this run (Radio Browser asks apps not to hammer it). */
private val radioCache = mutableMapOf<String, List<Station>>()

@Composable
fun RadioScreen(radio: Radio, current: String?, playing: Boolean, listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
                play: (Station) -> Unit) {
    val favs by radio.favorites.collectAsStateWithLifecycle()
    val recent by radio.recent.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim()
    var shelf by rememberSaveable { mutableStateOf(if (favs.isNotEmpty()) "Favorites" else "Local") }
    val shelves = listOfNotNull("Favorites".takeIf { favs.isNotEmpty() }, "Recent".takeIf { recent.isNotEmpty() }, "Local", "SomaFM", "Popular") + RADIO_GENRES
    var list by remember { mutableStateOf<List<Station>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(q, shelf, if (shelf == "Favorites") favs else null, if (shelf == "Recent") recent else null) {
        failed = false
        if (q.length >= 2) {
            delay(400)   // waits for typing to pause
            loading = true
            list = radio.search(q)
        } else {
            list = when (shelf) {
                "Favorites" -> favs
                "Recent" -> recent
                else -> radioCache[shelf] ?: run {
                    loading = true
                    when (shelf) {
                        "Local" -> radio.local()
                        "SomaFM" -> radio.somaFm()
                        "Popular" -> radio.popular()
                        else -> radio.genre(shelf.lowercase())
                    }?.also { radioCache[shelf] = it }
                }
            }
        }
        failed = list == null
        loading = false
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(16.dp, 4.dp, 16.dp, 4.dp),
            placeholder = { Text("Search stations") }, singleLine = true, shape = RoundedCornerShape(28.dp),
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = if (query.isEmpty()) null else ({ IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear") } }),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }))
        if (q.length < 2) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            shelves.forEach { c -> FilterChip(shelf == c, { shelf = c }, { Text(c) }) }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            if (q.length < 2 && shelf == "Local") item { Hint("Stations within about 150 km of ${BuildConfig.PLACE}, most played first.") }
            if (q.length < 2 && shelf == "SomaFM") item { Hint("Listener-supported, commercial-free channels from San Francisco.") }
            if (failed) item { Hint("Couldn't reach the station directory. Pull down or try again in a moment.") }
            if (!loading && !failed && list?.isEmpty() == true) item { Hint(if (q.length >= 2) "No stations found for “$q”." else "Nothing here yet.") }
            items(list.orEmpty(), key = { it.id }) { st ->
                StationRow(st, st.id == current, st.id == current && playing, favs.any { it.id == st.id }, { play(st) }) { radio.toggleFavorite(st) }
            }
        }
    }
}

@Composable
private fun Hint(text: String) = Text(text, Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun StationRow(st: Station, current: Boolean, onAir: Boolean, favorite: Boolean, play: () -> Unit, toggleFavorite: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = play)
        .background(if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
        .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            Art(st.logo, Modifier.fillMaxSize())
            if (st.logo.isEmpty()) Icon(Icons.Filled.Radio, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(st.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val tags = st.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(3).joinToString(", ")
            Text(listOf(tags, st.quality).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onAir) Icon(Icons.Filled.GraphicEq, "Playing", tint = MaterialTheme.colorScheme.primary)
        IconButton(onClick = toggleFavorite) {
            Icon(if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder, if (favorite) "Remove from favorites" else "Add to favorites",
                tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The player for a live station: what's on air, play/stop, favorite, sleep timer. No seeking. */
@Composable
fun LivePlayer(radio: Radio, p: Playing, c: MediaController, resume: () -> Unit) {
    val favs by radio.favorites.collectAsStateWithLifecycle()
    val st = p.id?.let { radio.station(it) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            Art(p.art, Modifier.fillMaxSize())
            if (p.art.isNullOrEmpty()) Icon(Icons.Filled.Radio, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(p.title, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = MaterialTheme.colorScheme.error, shape = RoundedCornerShape(4.dp)) {
                Text("LIVE", Modifier.padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold)
            }
            val tags = st?.tags.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(3).joinToString(", ")
            Text(listOf(tags, st?.quality.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · "),
                Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Text(p.song ?: if (p.playing) "On air" else " ", Modifier.padding(top = 16.dp).heightIn(min = 48.dp),
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            val fav = favs.any { it.id == p.id }
            IconButton(onClick = { st?.let { radio.toggleFavorite(it) } }, enabled = st != null) {
                Icon(if (fav) Icons.Filled.Star else Icons.Filled.StarBorder, if (fav) "Remove from favorites" else "Add to favorites",
                    tint = if (fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledIconButton(onClick = { if (c.isPlaying) c.pause() else resume() }, Modifier.size(72.dp)) {
                Icon(if (p.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (p.playing) "Stop" else "Play", Modifier.size(40.dp))
            }
            SleepButton()
        }
        if (!st?.about.isNullOrEmpty()) Text(st!!.about, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

// ---------- player ----------

@Composable
fun MiniPlayer(p: Playing, onOpen: () -> Unit, onToggle: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.clickable(onClick = onOpen)) {
            if (p.duration > 0) LinearProgressIndicator(progress = { p.position.toFloat() / p.duration }, modifier = Modifier.fillMaxWidth().height(2.dp),
                drawStopIndicator = {})
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Art(p.art, Modifier.size(44.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(p.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(p.song ?: if (p.live) "Live radio" else p.show, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onToggle) { Icon(if (p.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (p.playing) "Pause" else "Play") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayer(repo: Repo, p: Playing, c: MediaController, onSpeed: (Float) -> Unit, onWatch: (Episode) -> Unit) {
    val all by repo.episodes.collectAsStateWithLifecycle()
    val episode = remember(p.id, all) { repo.episode(p.id.orEmpty()) }
    var extras by remember(p.id) { mutableStateOf<Extras?>(null) }
    // The second pass can arrive while this plays: then its chapters and transcript replace the feed's.
    val secondPass = AdFree.info(p.id.orEmpty())?.extras == true
    LaunchedEffect(p.id, secondPass) {
        // The Latest list carries episodes without descriptions; the show's file has them.
        if (episode != null && episode.desc.isEmpty()) repo.show(episode.showId)?.let { repo.loadShow(it) }
        extras = episode?.let { repo.extras(it) }
    }
    val chapters = extras?.chapters.orEmpty()
    val lines = extras?.lines.orEmpty()
    val posSec = (p.position / 1000).toInt()
    val chapterNow = chapters.indexOfLast { it.start <= posSec }
    var tab by remember(p.id) { mutableIntStateOf(0) }
    // What the laptop found and removed (tools/podcasts/adfree.py), if it did this episode.
    val ads = AdFree.info(p.id.orEmpty())?.takeIf { it.cuts.isNotEmpty() || it.maybe.isNotEmpty() || it.skip.isNotEmpty() }
    val tabs = listOfNotNull("Episode", "Up next", "Chapters".takeIf { chapters.isNotEmpty() }, (if (extras?.estimated == true) "Read along" else "Transcript").takeIf { lines.isNotEmpty() },
        "Ads".takeIf { ads != null })
    if (tab >= tabs.size) tab = 0
    var dragging by remember { mutableStateOf<Float?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var casting by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Art(p.art, Modifier.size(if (tabs[tab] == "Episode") 96.dp else 56.dp))
            Column(Modifier.padding(start = 14.dp)) {
                Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(p.show, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                if (chapterNow >= 0 && chapters[chapterNow].title.isNotEmpty()) Text(chapters[chapterNow].title, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val dur = p.duration.coerceAtLeast(1)
        // Faint dots where ads were taken out, and a faint band over a sponsor read left in.
        val marks = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        val maybeBand = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)
        Slider(value = dragging ?: (p.position.toFloat() / dur), onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { c.seekTo((it * dur).toLong()) }; dragging = null }, modifier = Modifier.padding(top = 8.dp),
            track = { state ->
                Box {
                    SliderDefaults.Track(sliderState = state)
                    if (ads != null && p.duration > 0) Canvas(Modifier.matchParentSize()) {
                        val len = p.duration / 1000.0
                        val y = size.height / 2
                        fun x(t: Double) = (t / len).toFloat().coerceIn(0f, 1f) * size.width
                        if (p.clean) {
                            for (cut in ads.cuts) {
                                val f = ads.toClean(cut.start) / len
                                if (f in 0.01..0.99) drawCircle(marks, radius = 2.5.dp.toPx(), center = Offset(x(ads.toClean(cut.start)), y))
                            }
                        } else if (!ads.file) {
                            for (cut in ads.cuts) drawLine(marks, Offset(x(cut.start), y), Offset(x(cut.end), y), strokeWidth = 3.dp.toPx())
                        }
                        if (p.clean || !ads.file) {
                            for (m in ads.maybe) drawLine(maybeBand, Offset(x(m.start), y), Offset(x(m.end), y), strokeWidth = 6.dp.toPx())
                            for (k in ads.skip) drawLine(marks, Offset(x(k.start), y), Offset(x(k.end), y), strokeWidth = 3.dp.toPx())
                        }
                    }
                }
            })
        Row(Modifier.fillMaxWidth()) {
            Text(clock(((dragging ?: (p.position.toFloat() / dur)) * dur).toLong()), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            Text("-" + clock(p.duration - p.position), style = MaterialTheme.typography.labelSmall)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
            IconButton(onClick = {
                // Back to the start of this chapter, or the one before within its first 5 s.
                val i = chapterNow
                if (i >= 0) c.seekTo((if (posSec - chapters[i].start > 5 || i == 0) chapters[i].start else chapters[i - 1].start) * 1000L)
            }, enabled = chapters.isNotEmpty()) { Icon(Icons.Filled.SkipPrevious, "Previous chapter") }
            IconButton(onClick = { c.seekBack() }, Modifier.size(52.dp)) { Icon(Icons.Filled.Replay10, "Back 10 seconds", Modifier.size(34.dp)) }
            FilledIconButton(onClick = { if (c.isPlaying) c.pause() else { if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() } }, Modifier.size(68.dp)) {
                Icon(if (p.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (p.playing) "Pause" else "Play", Modifier.size(38.dp))
            }
            IconButton(onClick = { c.seekForward() }, Modifier.size(52.dp)) { Icon(Icons.Filled.Forward30, "Forward 30 seconds", Modifier.size(34.dp)) }
            IconButton(onClick = { chapters.getOrNull(chapterNow + 1)?.let { c.seekTo(it.start * 1000L) } },
                enabled = chapterNow + 1 < chapters.size && chapters.isNotEmpty()) { Icon(Icons.Filled.SkipNext, "Next chapter") }
        }
        // A likely sponsor read the laptop left in (not sure enough to cut): offered as a skip.
        val sponsor = AdFree.info(p.id.orEmpty())?.maybeAt(p.position / 1000.0, p.clean)
        if (sponsor != null && episode != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            FilledTonalButton(onClick = {
                c.seekTo((sponsor.end * 1000).toLong())
                scope.launch { repo.logSkip(episode.id, episode.showId, sponsor.start.toInt(), sponsor.end.toInt(), p.clean) }
            }) {
                Icon(Icons.Filled.Forward30, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Skip sponsor")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            SpeedButton(p.speed) { next -> c.setPlaybackSpeed(next); onSpeed(next) }
            // Hands this episode (and Up next) to Roche Home on the TV, and pauses here.
            if (repo.canCast && episode != null && !episode.youtubeOnly) TextButton(enabled = !casting, onClick = {
                casting = true
                scope.launch {
                    val next = p.queue.drop(p.index + 1).mapNotNull { repo.episode(it) }
                    // The TV plays the ad-free copy when there is one: the place goes in its time.
                    val info = AdFree.info(episode.id)?.takeIf { it.file }
                    val pos = if (info != null && !p.clean) info.toClean(p.position / 1000.0).toInt() else posSec
                    val ok = repo.castToTv(episode, pos, p.speed, next, clean = info != null)
                    casting = false
                    if (ok) c.pause()
                    android.widget.Toast.makeText(ctx, if (ok) "Playing on the TV" else "Couldn't reach Home Assistant", android.widget.Toast.LENGTH_SHORT).show()
                }
            }) {
                Icon(Icons.Filled.Tv, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("TV", fontSize = 15.sp)
            }
            if (episode != null && episode.videoId.isNotEmpty()) TextButton(onClick = { onWatch(episode) }) {
                Icon(Icons.Filled.SmartDisplay, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Watch", fontSize = 15.sp)
            }
            SleepButton()
        }
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tabs[tab]) {
                "Episode" -> LazyColumn {
                    if (extras?.summary?.isNotEmpty() == true) item {
                        Text(extras!!.summary, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                    item {
                        val desc = extras?.description ?: Repo.cleanDesc(episode?.desc.orEmpty())
                        Text(linkify(desc.ifEmpty { if (extras?.summary.isNullOrEmpty()) "No description." else "" }, MaterialTheme.colorScheme.primary),
                            Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                "Up next" -> UpNext(repo, p, c)
                "Chapters" -> LazyColumn {
                    itemsIndexed(chapters) { i, ch ->
                        ChapterBlock(ch.copy(title = ch.title.ifEmpty { "Chapter ${i + 1}" }), Modifier.fillMaxWidth().clickable { c.seekTo(ch.start * 1000L) }
                            .background(if (i == chapterNow) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .padding(12.dp, 10.dp), current = i == chapterNow)
                    }
                }
                "Transcript", "Read along" -> Transcript(lines, extras?.timed == true, posSec, extras?.estimated == true) { c.seekTo(it * 1000L) }
                "Ads" -> if (ads != null) AdsTab(ads, p.clean) { c.seekTo((it * 1000).toLong()) }
            }
        }
    }
}

/** The sleep timer: off, 15–60 minutes, or the end of the episode. */
/** A tap steps to the next speed; a long press lists them all. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpeedButton(speed: Float, set: (Float) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(20.dp))
                .combinedClickable(onClickLabel = "Next speed", onClick = { set(SPEEDS[(SPEEDS.indexOf(speed).coerceAtLeast(0) + 1) % SPEEDS.size]) },
                    onLongClick = { menu = true })
                .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Speed, "Speed", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(speedLabel(speed), color = MaterialTheme.colorScheme.primary, fontSize = 15.sp, style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            SPEEDS.sorted().forEach { sp ->
                DropdownMenuItem(text = { Text(speedLabel(sp), fontWeight = if (sp == speed) FontWeight.Bold else null) },
                    trailingIcon = { if (sp == speed) Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp)) },
                    onClick = { set(sp); menu = false })
            }
        }
    }
}

@Composable
fun SleepButton() {
    val until by Sleep.until.collectAsStateWithLifecycle()
    val endOfEpisode by Sleep.endOfEpisode.collectAsStateWithLifecycle()
    var sleepMenu by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { sleepMenu = true }) {
            Icon(Icons.Filled.Bedtime, "Sleep timer", Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(until) { while (until > 0) { now = System.currentTimeMillis(); delay(1000) } }
            // Minutes until it pauses, rounded up ("14m"); "End" at the end of the episode; "Off".
            Text(when {
                until > 0 -> "${((until - now + 59_999) / 60_000).coerceAtLeast(1)}m"
                endOfEpisode -> "End"
                else -> "Off"
            }, fontSize = 15.sp)
        }
        DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
            DropdownMenuItem(text = { Text("Off") }, onClick = { Sleep.off(); sleepMenu = false })
            listOf(15, 30, 45, 60).forEach { m ->
                DropdownMenuItem(text = { Text("$m minutes") }, onClick = {
                    Sleep.endOfEpisode.value = false
                    Sleep.until.value = System.currentTimeMillis() + m * 60_000L
                    sleepMenu = false
                })
            }
            DropdownMenuItem(text = { Text("End of episode") }, onClick = { Sleep.until.value = 0; Sleep.endOfEpisode.value = true; sleepMenu = false })
        }
    }
}

/** How a stretch was found, most telling first (kinds from tools/podcasts/adfree.py). */
private val AD_KINDS = linkedMapOf(
    "dai" to "Inserted by the host (differs between downloads)",
    "known" to "A known ad (heard before)",
    "shared" to "Also on another show",
    "skip" to "You skipped it in another episode",
    "host" to "Sponsor read (transcript)",
    "transcript" to "Sponsor words in the transcript",
    "bumper" to "Between the show's break jingles",
    "llm-self" to "The show promoting itself (transcript)",
)

private fun adKinds(kind: String) = kind.split('+').filter { it in AD_KINDS }.sortedBy { AD_KINDS.keys.indexOf(it) }

private fun span(sec: Double): String {
    val s = sec.toInt()
    return if (s >= 60) "${s / 60} min ${s % 60} s" else "$s s"
}

/**
 * What the laptop did to this episode: the ads taken out (where they were in what you hear,
 * how long, how they were found) and the sponsor reads left in. A tap goes there.
 */
@Composable
fun AdsTab(ads: AdInfo, clean: Boolean, seek: (Double) -> Unit) {
    val removed = ads.cuts.sumOf { it.length }
    LazyColumn {
        item {
            Column(Modifier.padding(vertical = 12.dp)) {
                Text(when {
                    ads.cuts.isEmpty() && ads.skip.isNotEmpty() -> "${ads.skip.size} sponsor read${if (ads.skip.size == 1) "" else "s"} skipped · ${span(ads.skip.sumOf { it.length })}"
                    ads.cuts.isEmpty() -> "No ads to remove"
                    clean -> "${ads.cuts.size} ad break${if (ads.cuts.size == 1) "" else "s"} removed · ${span(removed)}"
                    else -> "${ads.cuts.size} ad break${if (ads.cuts.size == 1) "" else "s"} found · ${span(removed)}"
                }, style = MaterialTheme.typography.titleSmall)
                Text(when {
                    ads.cuts.isEmpty() -> "No inserted ads, so the original plays."
                    clean -> "Playing the ad-free copy: ${clock((ads.dur * 1000).toLong())} instead of ${clock((ads.orig * 1000).toLong())}."
                    else -> "Playing the original (Home Assistant not reachable), so these ads still play."
                } + " Checked ${ago(ads.at)}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(ads.cuts.size) { i ->
            val cut = ads.cuts[i]
            val at = if (clean) ads.toClean(cut.start) else cut.start
            Row(Modifier.fillMaxWidth().clickable { seek(at) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(clock((at * 1000).toLong()), Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(when {
                        cut.start < 1 -> "Before the show"
                        ads.orig - cut.end < 2 -> "After the show"
                        else -> "Ad break"
                    } + " · " + span(cut.length), style = MaterialTheme.typography.bodyMedium)
                    Text(adKinds(cut.kind).joinToString(" · ") { AD_KINDS.getValue(it) }.ifEmpty { cut.kind },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (ads.skip.isNotEmpty() && (clean || !ads.file)) {
            item {
                Text("Sponsor reads, skipped as they come", Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall)
                Text("Found in the episode's transcript; the player jumps over them.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(ads.skip.size) { i ->
                val k = ads.skip[i]
                Row(Modifier.fillMaxWidth().clickable { seek(k.start) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(clock((k.start * 1000).toLong()), Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(when (k.kind) { "llm-produced" -> "Produced ad"; "llm-promo" -> "Promo"; else -> "Sponsor read" } + " · " + span(k.length),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (ads.maybe.isNotEmpty() && (clean || !ads.file)) {
            item {
                Text("Possible sponsor reads, left in", Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall)
                Text("Not sure enough to cut. The player offers Skip sponsor while one plays; skipping teaches the laptop.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(ads.maybe.size) { i ->
                val m = ads.maybe[i]
                Row(Modifier.fillMaxWidth().clickable { seek(m.start) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(clock((m.start * 1000).toLong()), Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                    Column(Modifier.weight(1f)) {
                        Text("Sponsor read? · " + span(m.length), style = MaterialTheme.typography.bodyMedium)
                        Text(adKinds(m.kind).joinToString(" · ") { AD_KINDS.getValue(it) }.ifEmpty { m.kind },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun UpNext(repo: Repo, p: Playing, c: MediaController) {
    val after = p.queue.drop(p.index + 1)
    if (after.isEmpty()) {
        Text("Nothing queued. Long-press an episode and choose Play next or Add to Up next.", Modifier.padding(vertical = 16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    LazyColumn {
        itemsIndexed(after, key = { _, id -> id }) { i, id ->
            val e = repo.episode(id)
            val show = e?.let { repo.show(it.showId) }
            Row(Modifier.fillMaxWidth().clickable { c.seekTo(p.index + 1 + i, 0); c.play() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Art(show?.art, Modifier.size(44.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(e?.title ?: "Episode", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(show?.title.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                IconButton(onClick = { c.removeMediaItem(p.index + 1 + i) }) { Icon(Icons.Filled.Close, "Remove from Up next") }
            }
        }
    }
}

@Composable
fun Transcript(lines: List<Line>, timed: Boolean, posSec: Int, estimated: Boolean = false, seek: (Int) -> Unit) {
    val state = rememberLazyListState()
    val now = if (timed) lines.indexOfLast { it.start <= posSec } else -1
    var touched by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.isScrollInProgress) { if (state.isScrollInProgress) touched = System.currentTimeMillis() }
    // Follows the audio, except for 8 s after you scroll it yourself.
    // A book's paragraph goes to the top (item 0 is the note above it); a podcast keeps the line before in view.
    LaunchedEffect(now) {
        if (now >= 0 && System.currentTimeMillis() - touched > 8000)
            state.animateScrollToItem(if (estimated) now + 1 else (now - 1).coerceAtLeast(0))
    }
    LazyColumn(state = state) {
        if (!timed) item { Text("This show's transcript has no timings, so it doesn't follow along.", Modifier.padding(vertical = 8.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (estimated) item { Text("The book's text. The highlight follows an estimate of where the reader is; tap a paragraph to jump there.",
            Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        itemsIndexed(lines) { i, ln ->
            val speakerChanged = ln.speaker.isNotEmpty() && (i == 0 || lines[i - 1].speaker != ln.speaker)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(if (i == now) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .clickable(enabled = timed) { seek(ln.start) }.padding(10.dp, 6.dp)) {
                if (speakerChanged) Text(ln.speaker, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                Text(ln.text, style = MaterialTheme.typography.bodyLarge,
                    color = if (i == now || !timed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------- formatting ----------

fun speedLabel(s: Float) = (if (s % 1f == 0f) s.toInt().toString() else s.toString()) + "×"

fun mins(sec: Int): String {
    val m = (sec + 30) / 60
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** The time now for "… ago", ticked by [App] every 30 s so those labels stay current. */
object Clock { var now by mutableLongStateOf(System.currentTimeMillis()) }

/** "12 min ago" and "5 h ago" within a day; "Yesterday", "3 days ago", "Oct 12" after that. */
fun ago(unix: Long): String {
    val ms = Clock.now - unix * 1000
    return when {
        ms < 60_000 -> "Just now"
        ms < 3_600_000 -> "${ms / 60_000} min ago"
        ms < 86_400_000 -> "${ms / 3_600_000} h ago"
        else -> DateUtils.getRelativeTimeSpanString(unix * 1000, Clock.now, DateUtils.DAY_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE or DateUtils.FORMAT_ABBREV_MONTH).toString()
    }
}

// ---------- news ----------

private val NEWS_CATEGORIES = listOf("Conejo Valley", "News & Politics", "Santa Barbara", "Ideas & Culture", "Science", "Tech", "Mysteries & UFOs",
    "History & Legends", "Giants", "Comedy & Entertainment")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsScreen(news: News, listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState()) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val articles by news.articles.collectAsStateWithLifecycle()
    val read by news.read.collectAsStateWithLifecycle()
    val refreshing by news.refreshing.collectAsStateWithLifecycle()
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { news.refresh(force = false) }
    val cats = remember(news.feeds) { news.feeds.map { it.category }.distinct().sortedBy { NEWS_CATEGORIES.indexOf(it).let { i -> if (i < 0) 99 else i } } }
    val sources = remember(category) { news.feeds.filter { it.category == category }.map { it.name } }
    val shown = remember(articles, category, source) {
        articles.filter { (category == null || it.category == category) && (source == null || it.source == source) }.take(150)
    }
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { scope.launch { news.refresh(force = true) } }) {
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(category == null, { category = null; source = null }, { Text("All") })
                    cats.forEach { c -> FilterChip(category == c, { category = if (category == c) null else c; source = null }, { Text(c) }) }
                }
            }
            if (category != null && sources.size > 1) item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sources.forEach { n -> FilterChip(source == n, { source = if (source == n) null else n }, { Text(n) }) }
                }
            }
            if (shown.isEmpty()) item { Text(if (refreshing) "Loading the news…" else "Nothing yet. Pull down to refresh.", Modifier.padding(16.dp)) }
            items(shown, key = { it.id + it.source }) { a ->
                val seen = a.id in read
                Row(Modifier.fillMaxWidth().clickable {
                    news.markRead(a)
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(a.link))) }
                }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(listOfNotNull(a.publisher.ifEmpty { a.source }, ago(a.date).takeIf { a.date > 0 }).joinToString(" · "), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = if (seen) 0.6f else 1f))
                        Text(Headline.tame(a.title), style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            color = if (seen) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                        if (a.summary.isNotEmpty()) Text(a.summary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (a.image.isNotEmpty()) AsyncImage(a.image, null, Modifier.padding(start = 12.dp).size(76.dp).clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Crop)
                }
            }
        }
    }
}
