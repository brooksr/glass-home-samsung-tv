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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.NewReleases
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
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
              val position: Long, val duration: Long, val speed: Float, val queue: List<String>, val index: Int)

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
                    (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }, c.currentMediaItemIndex)
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
    val watch: (Episode, Int) -> Unit,
    /** YouTube episodes opened from here. */
    val opened: Set<String> = emptySet(),
)

/** Opens a video in the YouTube app (the browser if it isn't installed), at [start] seconds. */
fun openYouTube(ctx: android.content.Context, videoId: String, start: Int = 0) {
    val url = "https://www.youtube.com/watch?v=$videoId" + (if (start > 0) "&t=${start}s" else "")
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
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
    var tab by rememberSaveable { mutableIntStateOf(0) }
    if (tab == 3) tab = 1   // Discover is part of Shows now
    var openShowId by rememberSaveable { mutableStateOf<String?>(null) }
    var openBookId by rememberSaveable { mutableStateOf<String?>(null) }
    var fullPlayer by rememberSaveable { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("player", 0)
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { repo.refresh(everything = false) }

    // The queue from last time, back in the player (not playing) once the episodes are known.
    LaunchedEffect(controller, episodes.isNotEmpty()) {
        val c = controller ?: return@LaunchedEffect
        if (c.mediaItemCount > 0 || episodes.isEmpty() || voiceRequested) return@LaunchedEffect
        val (eps, cur) = repo.loadQueue()
        if (eps.isEmpty()) return@LaunchedEffect
        val p = progress[eps[cur].id]
        c.setMediaItems(eps.map { PlaybackService.item(it, repo.show(it.showId)) }, cur, if (p != null && !p.done) p.t * 1000L else 0L)
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
            val startMs = if (p != null && !p.done) ((p.t - 5).coerceAtLeast(0) * 1000L) else 0L
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
        },
        playNext = { e ->
            controller?.let { c ->
                indexOf(c, e.id)?.let { c.removeMediaItem(it) }
                val at = if (c.mediaItemCount == 0) 0 else c.currentMediaItemIndex + 1
                c.addMediaItem(at, PlaybackService.item(e, repo.show(e.showId)))
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                scope.launch { snack.showSnackbar("Playing next") }
            }
        },
        enqueue = { e ->
            controller?.let { c ->
                if (indexOf(c, e.id) == null) {
                    c.addMediaItem(PlaybackService.item(e, repo.show(e.showId)))
                    if (c.playbackState == Player.STATE_IDLE) c.prepare()
                }
                scope.launch { snack.showSnackbar("Added to Up next") }
            }
        },
        setPlayed = { e, played -> repo.setPlayed(e, played) },
        openShow = { id -> openShowId = id; openBookId = null; searching = false },
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

    BackHandler(enabled = fullPlayer || openShowId != null || openBookId != null || searching) {
        when {
            fullPlayer -> fullPlayer = false
            openShowId != null -> openShowId = null
            openBookId != null -> openBookId = null
            else -> { searching = false; query = "" }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            val show = openShowId?.let { repo.show(it) }
            val book = openBookId?.let { repo.book(it) }
            TopAppBar(
                title = {
                    when {
                        show != null -> Text(show.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        book != null -> Text(book.show.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        searching -> SearchField(query) { query = it }
                        else -> Image(painterResource(R.drawable.logo), "Brookstream", Modifier.size(40.dp))
                    }
                },
                navigationIcon = {
                    if (show != null) IconButton(onClick = { openShowId = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    else if (book != null) IconButton(onClick = { openBookId = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    else if (searching) IconButton(onClick = { searching = false; query = "" }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") }
                },
                actions = {
                    if (show == null && book == null && !searching) IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "Search") }
                    if (searching && query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Clear") }
                },
            )
        },
        bottomBar = {
            // Without the tab bar, keep the mini player clear of the gesture bar.
            val tabs = openShowId == null && openBookId == null && !searching
            Column(if (tabs) Modifier else Modifier.navigationBarsPadding()) {
                if (playing != null) MiniPlayer(playing, onOpen = { fullPlayer = true }, onToggle = { controller?.let { c -> if (c.isPlaying) c.pause() else resume(c, playing.live) } })
                if (tabs) NavigationBar {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(Icons.Filled.NewReleases, null) }, label = { Text("Latest") })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(Icons.Filled.GridView, null) }, label = { Text("Shows") })
                    NavigationBarItem(selected = tab == 4, onClick = { tab = 4 }, icon = { Icon(Icons.Filled.AutoStories, null) }, label = { Text("Books") })
                    NavigationBarItem(selected = tab == 5, onClick = { tab = 5 }, icon = { Icon(Icons.Filled.Radio, null) }, label = { Text("Radio") })
                    NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(Icons.Filled.Newspaper, null) }, label = { Text("News") })
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val show = openShowId?.let { repo.show(it) }
            val book = openBookId?.let { repo.book(it) }
            when {
                book != null && show == null -> BookScreen(book, repo.hasText(book), progress, playing?.id, actions, ::playBook)
                show != null -> ShowScreen(repo, show, shows.any { it.id == show.id }, episodes[show.id].orEmpty(), progress, playing?.id, prefs, actions, ::unsubscribe)
                searching -> SearchResults(repo, query, shows, episodes, progress, playing?.id, actions,
                    open = { s -> repo.preview(s); openShowId = s.id }, unsubscribe = ::unsubscribe)
                tab == 2 -> NewsScreen(news)
                tab == 4 -> BooksScreen(repo.books, progress) { openBookId = it.id }
                tab == 5 -> RadioScreen(radio, playing?.id, playing?.playing == true, ::playStation)
                else -> PullToRefreshBox(isRefreshing = refreshing, onRefresh = { scope.launch { repo.refresh(everything = true) } }) {
                    // Shows also offers For you and Top charts among its categories; Latest has only the real ones.
                    val cat = category.takeIf { tab == 1 || it !in DISCOVER }
                    Column {
                        CategoryChips(shows, cat, extra = if (tab == 1) DISCOVER else emptyList()) { category = it }
                        when {
                            tab == 0 -> LatestScreen(shows, episodes, progress, status, playing?.id, cat, actions)
                            cat in DISCOVER -> DiscoverList(repo, shows, charts = cat == TOP,
                                open = { s -> repo.preview(s); openShowId = s.id }, unsubscribe = ::unsubscribe)
                            else -> ShowsGrid(shows, cat, open = { openShowId = it.id }, unsubscribe = ::unsubscribe)
                        }
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
fun SearchField(query: String, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TextField(query, onChange, Modifier.fillMaxWidth().focusRequester(focus), placeholder = { Text("Search shows, episodes, all podcasts") }, singleLine = true,
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
                leadingIcon = { Icon(if (c == TOP) Icons.AutoMirrored.Filled.TrendingUp else Icons.Filled.AutoAwesome, null, Modifier.size(16.dp)) })
        }
        cats.forEach { c -> FilterChip(selected == c, { onSelect(if (selected == c) null else c) }, { Text(c) }) }
    }
}

@Composable
fun LatestScreen(shows: List<Show>, episodes: Map<String, List<Episode>>, progress: Map<String, Progress>,
                 status: String, current: String?, category: String?, actions: Actions) {
    val showById = remember(shows) { shows.associateBy { it.id } }
    val byId = remember(episodes, showById) { episodes.filterKeys { it in showById }.values.flatten().associateBy { it.id } }
    fun keep(e: Episode) = e.showId in showById && (category == null || showById[e.showId]?.category == category)
    val cont = remember(progress, byId, category) {
        progress.entries.filter { !it.value.done && it.value.t > 0 && it.key in byId }
            .sortedByDescending { it.value.at }.mapNotNull { byId[it.key] }.filter(::keep).take(10)
    }
    val latest = remember(episodes, progress, category, showById) {
        episodes.values.flatMap { list -> list.filter { !it.youtubeOnly }.take(3) + list.filter { it.youtubeOnly }.take(2) }.filter { progress[it.id]?.done != true && keep(it) }.sortedByDescending { it.date }.take(60)
    }
    LazyColumn(Modifier.fillMaxSize()) {
        if (status.isNotEmpty()) item { Text(status, Modifier.padding(16.dp, 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (cont.isNotEmpty()) {
            item { Header("Continue") }
            items(cont, key = { "c" + it.id }) { e -> EpisodeRow(e, showById[e.showId], progress[e.id], e.id == current, actions) }
        }
        item { Header("Latest") }
        if (latest.isEmpty()) item { Text("Nothing here. Pull down to refresh.", Modifier.padding(16.dp)) }
        items(latest, key = { "l" + it.id }) { e -> EpisodeRow(e, showById[e.showId], progress[e.id], e.id == current, actions) }
    }
}

@Composable
fun Header(text: String) = Text(text, Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp), style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)

@Composable
fun ShowsGrid(shows: List<Show>, category: String?, open: (Show) -> Unit, unsubscribe: (Show) -> Unit) {
    val groups = remember(shows, category) {
        shows.filter { category == null || it.category == category }.sortedByDescending { it.newest }
            .groupBy { it.category }.toList().sortedBy { catRank(it.first) }
    }
    LazyVerticalGrid(GridCells.Adaptive(108.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
        items(list, key = { it.id }) { e -> EpisodeRow(e, null, progress[e.id], e.id == current, actions, showDesc = true) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EpisodeRow(e: Episode, show: Show?, p: Progress?, current: Boolean, actions: Actions, showDesc: Boolean = false) {
    var menu by remember { mutableStateOf(false) }
    val opened = e.id in actions.opened
    Box {
        Row(Modifier.fillMaxWidth().combinedClickable(onClick = { actions.play(e) }, onLongClick = { menu = true })
            .background(if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (show != null) Art(show.art, Modifier.size(56.dp).padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (p?.done == true || opened) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                val bits = listOfNotNull(show?.title, ago(e.date).takeIf { e.date > 0 },
                    when {
                        p?.done == true -> "Played"
                        p != null && p.t > 0 && p.dur > 0 -> "${mins(p.dur - p.t)} left"
                        e.duration > 0 -> mins(e.duration)
                        else -> null
                    },
                    "Transcript".takeIf { e.transcriptUrl.isNotEmpty() },
                    "▶ YouTube".takeIf { e.youtubeOnly }, "Video".takeIf { !e.youtubeOnly && e.videoId.isNotEmpty() },
                    "Opened".takeIf { opened && e.youtubeOnly && p?.done != true })
                Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (showDesc && e.desc.isNotEmpty()) Text(e.desc, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (p != null && !p.done && p.t > 0) LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp),
                    drawStopIndicator = {})
            }
            if (p?.done == true) Icon(Icons.Filled.CheckCircle, "Played", Modifier.padding(start = 8.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
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

/** Discover's lists, kept while the app is open. */
private var forYouCache: List<Show>? = null
private var topCache: List<Show>? = null

@Composable
fun DiscoverList(repo: Repo, shows: List<Show>, charts: Boolean, open: (Show) -> Unit, unsubscribe: (Show) -> Unit) {
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
        LazyColumn(Modifier.fillMaxSize()) {
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

private val SHELVES = listOf("Great Novels", "Adventure & Mystery", "Science Fiction & Horror", "Philosophy & Ideas", "History & Lives",
    "Poetry & Drama", "Children's Classics")

@Composable
fun BooksScreen(books: List<Book>, progress: Map<String, Progress>, open: (Book) -> Unit) {
    var shelf by rememberSaveable { mutableStateOf<String?>(null) }
    val shelves = remember(books) { books.map { it.show.category }.distinct().sortedBy { SHELVES.indexOf(it).let { i -> if (i < 0) 99 else i } } }
    val fractions = remember(books, progress) { books.associate { it.id to bookFraction(it, progress) } }
    val going = remember(books, progress) {
        books.filter { resumeAt(it, progress) != null }
            .sortedByDescending { b -> b.chapters.maxOf { progress[it.id]?.at ?: 0 } }
    }
    LazyVerticalGrid(GridCells.Adaptive(108.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
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
fun BookScreen(b: Book, hasText: Boolean, progress: Map<String, Progress>, current: String?, actions: Actions, play: (Book, Int) -> Unit) {
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
fun RadioScreen(radio: Radio, current: String?, playing: Boolean, play: (Station) -> Unit) {
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
        LazyColumn(Modifier.fillMaxSize()) {
            if (q.length < 2 && shelf == "Local") item { Hint("Stations within about 150 km of home, most played first.") }
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

@Composable
fun FullPlayer(repo: Repo, p: Playing, c: MediaController, onSpeed: (Float) -> Unit, onWatch: (Episode) -> Unit) {
    val all by repo.episodes.collectAsStateWithLifecycle()
    val episode = remember(p.id, all) { repo.episode(p.id.orEmpty()) }
    var extras by remember(p.id) { mutableStateOf<Extras?>(null) }
    LaunchedEffect(p.id) {
        // The Latest list carries episodes without descriptions; the show's file has them.
        if (episode != null && episode.desc.isEmpty()) repo.show(episode.showId)?.let { repo.loadShow(it) }
        extras = episode?.let { repo.extras(it) }
    }
    val chapters = extras?.chapters.orEmpty()
    val lines = extras?.lines.orEmpty()
    val posSec = (p.position / 1000).toInt()
    val chapterNow = chapters.indexOfLast { it.start <= posSec }
    var tab by remember(p.id) { mutableIntStateOf(0) }
    val tabs = listOfNotNull("Episode", "Up next", "Chapters".takeIf { chapters.isNotEmpty() }, (if (extras?.estimated == true) "Read along" else "Transcript").takeIf { lines.isNotEmpty() })
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
        Slider(value = dragging ?: (p.position.toFloat() / dur), onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { c.seekTo((it * dur).toLong()) }; dragging = null }, modifier = Modifier.padding(top = 8.dp))
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = {
                val next = SPEEDS[(SPEEDS.indexOf(p.speed).coerceAtLeast(0) + 1) % SPEEDS.size]
                c.setPlaybackSpeed(next)
                onSpeed(next)
            }) { Text("Speed ${speedLabel(p.speed)}", fontSize = 15.sp) }
            // Hands this episode (and Up next) to Roche Home on the TV, and pauses here.
            if (repo.canCast && episode != null && !episode.youtubeOnly) TextButton(enabled = !casting, onClick = {
                casting = true
                scope.launch {
                    val next = p.queue.drop(p.index + 1).mapNotNull { repo.episode(it) }
                    val ok = repo.castToTv(episode, posSec, p.speed, next)
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
                "Episode" -> LazyColumn { item { Text(episode?.desc.orEmpty().ifEmpty { "No description." }, Modifier.padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                "Up next" -> UpNext(repo, p, c)
                "Chapters" -> LazyColumn {
                    itemsIndexed(chapters) { i, ch ->
                        Row(Modifier.fillMaxWidth().clickable { c.seekTo(ch.start * 1000L) }
                            .background(if (i == chapterNow) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .padding(12.dp, 10.dp)) {
                            Text(ch.title.ifEmpty { "Chapter ${i + 1}" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text(clock(ch.start * 1000L), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                "Transcript", "Read along" -> Transcript(lines, extras?.timed == true, posSec, extras?.estimated == true) { c.seekTo(it * 1000L) }
            }
        }
    }
}

/** The sleep timer: off, 15–60 minutes, or the end of the episode. */
@Composable
fun SleepButton() {
    val until by Sleep.until.collectAsStateWithLifecycle()
    val endOfEpisode by Sleep.endOfEpisode.collectAsStateWithLifecycle()
    var sleepMenu by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { sleepMenu = true }) {
            Icon(Icons.Filled.Bedtime, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(until) { while (until > 0) { now = System.currentTimeMillis(); delay(1000) } }
            Text(when {
                until > 0 -> "Sleep in " + mins(((until - now) / 1000).toInt().coerceAtLeast(0))
                endOfEpisode -> "Sleep at end"
                else -> "Sleep timer"
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

fun ago(unix: Long): String = DateUtils.getRelativeTimeSpanString(unix * 1000, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS,
    DateUtils.FORMAT_ABBREV_RELATIVE or DateUtils.FORMAT_ABBREV_MONTH).toString()

// ---------- news ----------

private val NEWS_CATEGORIES = listOf("Conejo Valley", "News & Politics", "Santa Barbara", "Ideas & Culture", "Science", "Tech", "Mysteries & UFOs",
    "History & Legends", "Giants", "Comedy & Entertainment")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsScreen(news: News) {
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
        LazyColumn(Modifier.fillMaxSize()) {
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
                        Text(a.title, style = MaterialTheme.typography.titleSmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
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
