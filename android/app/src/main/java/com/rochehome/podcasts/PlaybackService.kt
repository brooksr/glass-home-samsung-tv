package com.rochehome.podcasts

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import com.google.common.util.concurrent.SettableFuture
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The sleep timer, set from the player screen and run by the service (same process). */
object Sleep {
    /** When to pause, in System.currentTimeMillis(); 0 for no timer. */
    val until = MutableStateFlow(0L)
    /** Pause when this episode ends. */
    val endOfEpisode = MutableStateFlow(false)
    fun off() { until.value = 0; endOfEpisode.value = false }
}

/**
 * Plays in the background with the lock-screen and notification controls
 * (-10 s, play/pause, +30 s), headphone buttons, and pauses when headphones
 * are unplugged. The queue ("Up next") is the player's playlist: a finished
 * episode is marked played and drops off, and the next one starts where it
 * was left. Saves the place every 10 s, on pause and when switching episode;
 * shares it with Home Assistant on pause and every 2 minutes while playing.
 *
 * Ads: an episode with an ad-free copy on Home Assistant plays it at home ([AdFree]); one
 * whose copy appears while it plays (the laptop starts on an episode when it's started)
 * switches to it at the same place. Away, the original plays as it is. Runs of forward seeks (45 s or more, with any
 * small step back after) are reported for tools/podcasts/adfree.py to learn from.
 *
 * Also a media library for Android Auto (Ford SYNC 3 and 4 run it): Continue,
 * Latest, Shows (each show's episodes) and Books (shelves, books, parts). A
 * book part picked in the car queues the rest of the book; a car connecting
 * can pick up the last queue.
 */
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val repo get() = (application as PodcastApp).repo
    private val radio get() = (application as PodcastApp).radio
    private var ticks = 0
    /** The episode and place as of the last tick, to save when the item changes. */
    private var last: Triple<MediaItem, Long, Long>? = null
    /** Seeks made here (resuming, skipping a cut ad), not by the listener: not reported as skips. */
    private var ownSeeks = 0
    /** When the current item started, to look for its ad-free copy for a while. */
    private var itemStarted = 0L
    private var startedId: String? = null
    private var lookingForCopy = false

    /** A run of forward seeks being made by the listener. */
    private class SkipRun(val id: String, val showId: String, val clean: Boolean, val from: Long, var to: Long, var last: Long)
    private var skipRun: SkipRun? = null

    private fun seekOwn(p: Player, ms: Long) { ownSeeks++; p.seekTo(ms) }

    private val ticker = object : Runnable {
        override fun run() {
            val p = session?.player ?: return
            if (p.isPlaying) {
                p.currentMediaItem?.let { last = Triple(it, p.currentPosition, p.duration) }
                if (++ticks % 10 == 0) save(p)
                if (ticks % 120 == 0) scope.launch { repo.sync() }
                // Early on, so the laptop sees what's playing and starts on its ads.
                if (System.currentTimeMillis() - itemStarted in 20_000L..20_999L) { save(p); scope.launch { repo.sync() } }
                skipRead(p)
                if (ticks % 30 == 0) lookForCopy(p)
            }
            skipRun?.let { if (System.currentTimeMillis() - it.last > 10_000) flushSkip() }
            val until = Sleep.until.value
            if (until > 0 && System.currentTimeMillis() >= until) {
                Sleep.off()
                p.pause()
            }
            (p as? ExoPlayer)?.pauseAtEndOfMediaItems = Sleep.endOfEpisode.value
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) toCopy(player, fromStart = System.currentTimeMillis() - itemStarted < 5000)
                if (!isPlaying) {
                    save(player)
                    saveQueue(player)
                    scope.launch { repo.sync() }
                    // "End of episode" sleep has done its job once playback stops at the end.
                    if (Sleep.endOfEpisode.value && player.playbackState != Player.STATE_BUFFERING) {
                        val d = player.duration
                        if (d > 0 && d - player.currentPosition < 2000) Sleep.off()
                    }
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    player.currentMediaItem?.let { markDone(it, player.duration) }
                    if (player.mediaItemCount > 0) player.removeMediaItem(player.currentMediaItemIndex)
                    Sleep.endOfEpisode.value = false
                    saveQueue(player)
                    scope.launch { repo.sync() }
                }
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                val prev = last
                last = null
                if (item?.mediaId != startedId) {
                    flushSkip()
                    startedId = item?.mediaId
                    itemStarted = System.currentTimeMillis()
                    ownSeeks = 0
                    // Queued as the original before its copy was known (or by an older version): the copy now.
                    handler.post { toCopy(player, fromStart = true) }
                }
                if (prev != null && prev.first.mediaId != item?.mediaId) {
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                        // Played to the end and moved on: mark it, drop it from the queue.
                        markDone(prev.first, prev.third)
                        val i = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == prev.first.mediaId }
                        if (i != null) handler.post { player.removeMediaItem(i) }
                        if (Sleep.endOfEpisode.value) { Sleep.off(); player.pause() }
                    } else {
                        saveItem(prev.first, prev.second, prev.third)
                    }
                }
                // Start the new one where it was left.
                if (item != null && reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    val ms = AdFree.startMs(item.mediaId, repo.progress.value[item.mediaId], cleanOf(item))
                    if (ms > 0 && player.currentPosition < 2000) seekOwn(player, ms)
                }
                saveQueue(player)
            }

            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason != Player.DISCONTINUITY_REASON_SEEK) return
                if (ownSeeks > 0) { ownSeeks--; return }
                val item = player.currentMediaItem ?: return
                if (old.mediaItem?.mediaId != item.mediaId || new.mediaItem?.mediaId != item.mediaId) return
                noteSeek(item, old.positionMs, new.positionMs)
            }

            // The ad-free copy unreachable (left home mid-episode): the original, at the same place.
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val item = player.currentMediaItem ?: return
                if (!cleanOf(item)) return
                val e = repo.episode(item.mediaId) ?: return
                val at = AdFree.info(e.id)?.toOrig(player.currentPosition / 1000.0) ?: 0.0
                AdFree.markAway()
                player.replaceMediaItem(player.currentMediaItemIndex, item(e, repo.show(e.showId), original = true))
                seekOwn(player, (at * 1000).toLong())
                player.prepare()
                player.play()
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) { saveQueue(player) }

            // Radio: the song on air arrives in the stream (ICY StreamTitle). The item's own metadata would
            // hide it, so it goes onto the item: the app, the notification and the car show it.
            override fun onMetadata(metadata: androidx.media3.common.Metadata) {
                val song = (0 until metadata.length()).map { metadata[it] }.filterIsInstance<androidx.media3.extractor.metadata.icy.IcyInfo>()
                    .firstNotNullOfOrNull { Station.song(it.title) } ?: return
                val item = player.currentMediaItem ?: return
                if (!item.mediaId.startsWith(Station.PREFIX) || item.mediaMetadata.extras?.getString("song") == song) return
                val extras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply { putString("song", song) }
                player.replaceMediaItem(player.currentMediaItemIndex,
                    item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setArtist(song).setExtras(extras).build()).build())
            }
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val back = CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
            .setDisplayName("Back 10 seconds").setPlayerCommand(Player.COMMAND_SEEK_BACK).setSlots(CommandButton.SLOT_BACK).build()
        val forward = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
            .setDisplayName("Forward 30 seconds").setPlayerCommand(Player.COMMAND_SEEK_FORWARD).setSlots(CommandButton.SLOT_FORWARD).build()
        session = MediaLibrarySession.Builder(this, player, Library())
            .setSessionActivity(open)
            .setMediaButtonPreferences(ImmutableList.of(back, forward))
            .build()
        handler.postDelayed(ticker, 1000)
    }

    private fun showIdOf(item: MediaItem) = item.mediaMetadata.extras?.getString("showId").orEmpty()

    private fun saveItem(item: MediaItem, pos: Long, dur: Long) {
        if (item.mediaId.startsWith(Station.PREFIX)) return   // live radio has no place to keep
        if (dur == C.TIME_UNSET || dur <= 0 || pos < 10_000) return
        val done = dur - pos < 45_000
        val info = AdFree.info(item.mediaId)
        if (!cleanOf(item) && info?.file == true) {
            // The original, with an ad-free copy known: the place is kept in the copy's time.
            repo.setProgress(item.mediaId, showIdOf(item), info.toClean(pos / 1000.0).toInt(), info.dur.toInt(), done, clean = true)
            return
        }
        repo.setProgress(item.mediaId, showIdOf(item), (pos / 1000).toInt(), (dur / 1000).toInt(), done, cleanOf(item))
    }

    // ---------- ads ----------

    /**
     * The original playing while its ad-free copy can be played: switch to the copy. [fromStart]: the
     * episode has only just started, at its saved place, so the copy starts at the saved place too
     * (exact); later, the place is converted from the original's time (close: its ads differ).
     */
    private fun toCopy(p: Player, fromStart: Boolean) {
        val item = p.currentMediaItem ?: return
        if (cleanOf(item) || !AdFree.plays(item.mediaId)) return
        val e = repo.episode(item.mediaId) ?: return
        val info = AdFree.info(e.id) ?: return
        val saved = repo.progress.value[e.id]
        val at = if (fromStart && (p.currentPosition < 2000 || kotlin.math.abs(p.currentPosition - AdFree.startMs(e.id, saved, clean = false)) < 15_000)) {
            AdFree.startMs(e.id, saved, clean = true)
        } else {
            ((info.toClean(p.currentPosition / 1000.0) - 3).coerceAtLeast(0.0) * 1000).toLong()
        }
        flushSkip()
        p.replaceMediaItem(p.currentMediaItemIndex, item(e, repo.show(e.showId)))
        seekOwn(p, at)
    }

    /** Over a sponsor read the second pass found (not reported as your skip: it's known already). */
    private fun skipRead(p: Player) {
        val item = p.currentMediaItem ?: return
        val cut = AdFree.info(item.mediaId)?.skipAt(p.currentPosition / 1000.0, cleanOf(item)) ?: return
        seekOwn(p, (cut.end * 1000).toLong())
    }

    /**
     * Playing at home without the ad-free copy or its second pass yet (the laptop starts on an episode
     * when it starts): check the index every 30 s for 40 minutes. A copy is switched to at the same
     * place; a second pass brings skips, chapters and the transcript. Away, try now and then.
     */
    private fun lookForCopy(p: Player) {
        val item = p.currentMediaItem ?: return
        if (lookingForCopy || item.mediaId.startsWith(Station.PREFIX)) return
        val e = repo.episode(item.mediaId) ?: return
        if (e.showId.startsWith(Repo.BOOK)) return
        val info = AdFree.info(e.id)
        val clean = cleanOf(item)
        if (info?.extras == true && (clean || !info.file)) return                     // nothing more to come
        if (info != null && !clean && !AdFree.plays(e.id) && System.currentTimeMillis() % 300_000 > 30_000) return   // away
        if (System.currentTimeMillis() - itemStarted > 40 * 60_000L && (info == null || clean)) return
        lookingForCopy = true
        scope.launch {
            val home = withContext(Dispatchers.IO) { AdFree.refresh() }
            lookingForCopy = false
            val now = p.currentMediaItem ?: return@launch
            if (home && now.mediaId == e.id) toCopy(p, fromStart = false)
        }
    }

    // A run of forward seeks within 10 s of each other (and any small step back after), 45 s or more in all.
    private fun noteSeek(item: MediaItem, from: Long, to: Long) {
        val now = System.currentTimeMillis()
        val run = skipRun
        if (run != null && run.id == item.mediaId && now - run.last < 10_000) {
            run.to = to
            run.last = now
        } else if (to > from) {
            flushSkip()
            skipRun = SkipRun(item.mediaId, showIdOf(item), cleanOf(item), from, to, now)
        }
    }

    private fun flushSkip() {
        val r = skipRun ?: return
        skipRun = null
        if (r.to - r.from < 45_000 || r.to - r.from > 300_000 || r.id.startsWith(Station.PREFIX)) return
        scope.launch { repo.logSkip(r.id, r.showId, (r.from / 1000).toInt(), (r.to / 1000).toInt(), r.clean) }
    }

    private fun save(p: Player) {
        val item = p.currentMediaItem ?: return
        saveItem(item, p.currentPosition, p.duration)
    }

    private fun markDone(item: MediaItem, durMs: Long) {
        if (item.mediaId.startsWith(Station.PREFIX)) return
        val dur = (durMs / 1000).toInt().coerceAtLeast(0)
        repo.setProgress(item.mediaId, showIdOf(item), dur, dur, true)
    }

    private fun saveQueue(p: Player) {
        repo.saveQueue((0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }, p.currentMediaItemIndex)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    // ---------- the library (Android Auto) ----------

    private fun folder(id: String, title: String, subtitle: String? = null, art: String? = null) = MediaItem.Builder().setMediaId(id)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle)
            .setArtworkUri(art?.takeIf { it.isNotEmpty() }?.let { android.net.Uri.parse(it) })
            .setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build())
        .build()

    private fun playable(e: Episode, subtitle: String? = null): MediaItem {
        val show = repo.show(e.showId)
        val item = item(e, show)
        // Android Auto's rows show the title and the artist line, so the second line goes in both.
        return item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon()
            .setSubtitle(subtitle ?: show?.title).setArtist(subtitle ?: show?.title).setIsBrowsable(false).setIsPlayable(true).build()).build()
    }

    /** "Resume" for a book: the part to carry on with (or the first). */
    private fun resumeItem(b: Book): MediaItem {
        val i = resumeAt(b, repo.progress.value)
        val part = b.chapters[i ?: 0]
        return MediaItem.Builder().setMediaId("resume:${b.id}")
            // Title: the book (the car shows it first); second line: where it carries on.
            .setMediaMetadata(MediaMetadata.Builder().setTitle(b.show.title)
                .setArtist((if (i != null) "Resume · ${part.title}" else "Play from the start") + " · ${b.show.author}")
                .setSubtitle(if (i != null) "Resume · ${part.title}" else "Play from the start")
                .setArtworkUri(b.show.art.takeIf { it.isNotEmpty() }?.let { android.net.Uri.parse(it) })
                .setIsBrowsable(false).setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER).build())
            .build()
    }

    private suspend fun children(id: String): List<MediaItem> {
        val progress = repo.progress.value
        return when {
            id == ROOT -> listOf(folder("continue", "Continue"), folder("latest", "Latest"), folder("shows", "Shows"), folder("books", "Books"),
                folder("radio", "Radio"))
            id == "radio" -> listOfNotNull(folder("radio-fav", "Favorites").takeIf { radio.favorites.value.isNotEmpty() },
                folder("radio-recent", "Recent").takeIf { radio.recent.value.isNotEmpty() },
                folder("radio-local", "Local"), folder("radio-soma", "SomaFM"))
            id == "radio-fav" -> radio.favorites.value.map { station(it) }
            id == "radio-recent" -> radio.recent.value.map { station(it) }
            id == "radio-local" -> radio.local().orEmpty().map { station(it) }
            id == "radio-soma" -> radio.somaFm().orEmpty().map { station(it) }
            id == "continue" -> {
                val eps = progress.entries.filter { !it.value.done && it.value.t > 0 && !it.value.showId.startsWith(Repo.BOOK) }
                    .sortedByDescending { it.value.at }.mapNotNull { repo.episode(it.key) }.filter { repo.subscribed(it.showId) }.take(15)
                val books = repo.books.filter { resumeAt(it, progress) != null }
                    .sortedByDescending { b -> b.chapters.maxOf { progress[it.id]?.at ?: 0 } }.take(5)
                books.map(::resumeItem) + eps.map { e -> playable(e, progress[e.id]?.let { p -> "${repo.show(e.showId)?.title.orEmpty()} · ${mins(p.dur - p.t)} left" }) }
            }
            id == "latest" -> {
                val shows = repo.shows.value.map { it.id }.toSet()
                repo.episodes.value.filterKeys { it in shows }.values
                    .flatMap { list -> list.filter { !it.youtubeOnly }.take(3) }.filter { progress[it.id]?.done != true }
                    .sortedByDescending { it.date }.take(40).map { playable(it) }
            }
            id == "shows" -> repo.shows.value.sortedByDescending { it.newest }.map { folder("show:${it.id}", it.title, it.author, it.art) }
            id.startsWith("show:") -> {
                val show = repo.show(id.removePrefix("show:")) ?: return emptyList()
                runCatching { repo.loadShow(show) }
                repo.episodes.value[show.id].orEmpty().filter { !it.youtubeOnly }.take(60).map { playable(it) }
            }
            id == "books" -> repo.books.map { it.show.category }.distinct().map { folder("shelf:$it", it) }
            id.startsWith("shelf:") -> repo.books.filter { it.show.category == id.removePrefix("shelf:") }
                .map { folder("book:${it.id}", it.show.title, it.show.author, it.show.art) }
            id.startsWith("book:") -> {
                val b = repo.book(id.removePrefix("book:")) ?: return emptyList()
                listOf(resumeItem(b)) + b.chapters.map { playable(it, b.show.title) }
            }
            else -> emptyList()
        }
    }

    private val searches = mutableMapOf<String, List<MediaItem>>()

    /** The car's search: books, shows, stations (yours, then the directory's), episodes. */
    private suspend fun search(query: String): List<MediaItem> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        searches[q]?.let { return it }
        fun has(vararg t: String) = t.any { it.contains(q, ignoreCase = true) }
        val books = repo.books.filter { has(it.show.title, it.show.author) }.map { folder("book:${it.id}", it.show.title, it.show.author, it.show.art) }
        val shows = repo.shows.value.filter { has(it.title, it.author) }.map { folder("show:${it.id}", it.title, it.author, it.art) }
        val mine = (radio.favorites.value + radio.recent.value).distinctBy { it.id }.filter { has(it.name, it.tags) }
        val stations = (mine + radio.search(q).orEmpty().take(10)).distinctBy { it.id }.map { station(it) }
        val subscribed = repo.shows.value.map { it.id }.toSet()
        val eps = repo.episodes.value.filterKeys { it in subscribed }.values.flatten().filter { !it.youtubeOnly && has(it.title) }
            .sortedByDescending { it.date }.take(20).map { playable(it) }
        return (books + shows + stations + eps).also { searches[q] = it }
    }

    /** What a spoken request plays: a book (where it was left), a show's newest unplayed episode, a station, an episode. */
    private fun norm(t: String) = t.lowercase().replace("&", " and ").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private suspend fun voice(query: String): Pair<List<MediaItem>, Long> {
        val q = query.trim()
        val progress = repo.progress.value
        if (q.isEmpty()) {
            val (eps, cur) = repo.loadQueue()
            val p = eps.getOrNull(cur)?.let { progress[it.id] }
            return eps.map { item(it, repo.show(it.showId)) }.drop(cur) to eps.getOrNull(cur)?.let { AdFree.startMs(it.id, p, back = 0) }.let { it ?: 0L }
        }
        // "Left Right and Center" finds "Left, Right & Center": compared without case, punctuation or "&".
        val nq = norm(q)
        fun has(vararg t: String) = nq.isNotEmpty() && t.any { norm(it).contains(nq) }
        repo.books.firstOrNull { has(it.show.title) }?.let { return expand("resume:${it.id}") }
        // "KCRW": the station, not "KCRW's Left, Right & Center". A show wins when the request is most of its name.
        val show = repo.shows.value.filter { has(it.title) }.maxByOrNull { nq.length.toFloat() / norm(it.title).length }
        if (show == null || nq.length < norm(show.title).length * 0.6) {
            ((radio.favorites.value + radio.recent.value) + radio.search(q).orEmpty().take(5))
                .firstOrNull { norm(it.name).startsWith(nq) }?.let { return expand(it.id) }
        }
        show?.let { show ->
            runCatching { repo.loadShow(show) }
            val eps = repo.episodes.value[show.id].orEmpty().filter { !it.youtubeOnly }
            (eps.firstOrNull { progress[it.id]?.let { p -> !p.done && p.t > 0 } == true } ?: eps.firstOrNull { progress[it.id]?.done != true })
                ?.let { return expand(it.id) }
        }
        ((radio.favorites.value + radio.recent.value).firstOrNull { has(it.name) } ?: radio.search(q)?.firstOrNull())
            ?.let { return expand(it.id) }
        repo.books.firstOrNull { has(it.show.author) }?.let { return expand("resume:${it.id}") }
        return loose(q.lowercase())
    }

    /**
     * Requests that name nothing ("play some news", "something mellow", "that episode about octopuses"):
     * a kind of show gets its newest unplayed episode; a mood or genre gets a radio station of that kind;
     * otherwise the episode or book whose words match best.
     */
    private suspend fun loose(q: String): Pair<List<MediaItem>, Long> {
        val progress = repo.progress.value
        fun says(vararg w: String) = w.any { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(q) }
        val kind = when {
            says("news", "politics", "political", "headlines") -> "News & Politics"
            says("science", "scientific") -> "Science"
            says("history", "historical", "legends") -> "History & Legends"
            says("tech", "technology", "programming") -> "Tech"
            says("ufo", "ufos", "mystery", "mysteries", "paranormal") -> "Mysteries & UFOs"
            says("comedy", "funny", "laugh") -> "Comedy & Entertainment"
            says("giants", "baseball") -> "Giants"
            else -> null
        }
        val wantsMusic = says("music", "station", "radio", "song", "songs", "tunes", "playlist")
        if (kind != null && !wantsMusic) {
            val shows = repo.shows.value.filter { it.category == kind }.map { it.id }.toSet()
            repo.episodes.value.filterKeys { it in shows }.values.flatten()
                .filter { !it.youtubeOnly && progress[it.id]?.done != true }.maxByOrNull { it.date }
                ?.let { return expand(it.id) }
        }
        val moods = linkedMapOf(
            "mellow" to "chillout", "chill" to "chillout", "relaxing" to "ambient", "relax" to "ambient", "calm" to "ambient",
            "sleep" to "ambient", "focus" to "ambient", "study" to "ambient", "upbeat" to "pop", "happy" to "pop",
            "party" to "dance", "workout" to "dance", "energetic" to "dance", "road trip" to "classic rock",
            "jazz" to "jazz", "classical" to "classical", "rock" to "rock", "country" to "country", "blues" to "blues",
            "soul" to "soul", "funk" to "funk", "hip hop" to "hip hop", "rap" to "hip hop", "reggae" to "reggae",
            "latin" to "latin", "electronic" to "electronic", "ambient" to "ambient", "oldies" to "oldies", "pop" to "pop",
            "metal" to "metal", "folk" to "folk", "indie" to "indie", "christmas" to "christmas", "news" to "news", "talk" to "talk")
        // Moods: SomaFM's hand-run channels fit better than the most-played station with the tag.
        val soma = linkedMapOf("mellow" to "groovesalad", "chill" to "groovesalad", "relaxing" to "lush", "relax" to "lush",
            "calm" to "dronezone", "sleep" to "dronezone", "focus" to "deepspaceone", "study" to "groovesalad",
            "ambient" to "dronezone", "lounge" to "secretagent", "christmas" to "christmas")
        soma.entries.firstOrNull { says(it.key) }?.let { (_, ch) ->
            radio.somaFm()?.firstOrNull { it.id == "radio:soma:$ch" }?.let { return expand(it.id) }
        }
        moods.entries.firstOrNull { says(it.key) }?.let { (_, tag) ->
            radio.genre(tag)?.firstOrNull()?.let { return expand(it.id) }
        }
        // Words: the episode (of a show in the list) or book that shares the most of them.
        val stop = setOf("play", "some", "something", "about", "the", "that", "this", "with", "episode", "podcast", "podcasts",
            "brookstream", "please", "listen", "from", "latest", "newest", "new", "and", "for", "one")
        val words = Regex("[a-z0-9']+").findAll(q).map { it.value }.filter { it.length > 2 && it !in stop }.toList()
        if (words.isEmpty()) return emptyList<MediaItem>() to 0L
        fun score(text: String) = words.count { text.contains(it, ignoreCase = true) }
        val subscribed = repo.shows.value.map { it.id }.toSet()
        val ep = repo.episodes.value.filterKeys { it in subscribed }.values.flatten().filter { !it.youtubeOnly }
            .map { it to score(it.title) * 2 + score(it.desc) }.filter { it.second > 0 }
            .maxWithOrNull(compareBy<Pair<Episode, Int>> { it.second }.thenBy { it.first.date })
        val book = repo.books.map { it to score(it.show.title + " " + it.show.author) * 2 + score(it.desc) }.filter { it.second > 0 }
            .maxByOrNull { it.second }
        return when {
            ep != null && (book == null || ep.second >= book.second) -> expand(ep.first.id)
            book != null -> expand("resume:${book.first.id}")
            else -> emptyList<MediaItem>() to 0L
        }
    }

    /** What a picked item plays: a book part (or Resume) brings the rest of the book with it. */
    private fun expand(id: String): Pair<List<MediaItem>, Long> {
        if (id.startsWith(Station.PREFIX)) {
            val st = radio.station(id) ?: return emptyList<MediaItem>() to 0L
            scope.launch { radio.played(st) }
            return listOf(station(st)) to C.TIME_UNSET
        }
        val progress = repo.progress.value
        fun startOf(e: Episode) = AdFree.startMs(e.id, progress[e.id])
        if (id.startsWith("resume:")) {
            val b = repo.book(id.removePrefix("resume:")) ?: return emptyList<MediaItem>() to 0L
            val from = resumeAt(b, progress) ?: 0
            return b.chapters.drop(from).map { item(it, b.show) } to startOf(b.chapters[from])
        }
        val e = repo.episode(id) ?: return emptyList<MediaItem>() to 0L
        repo.book(e.showId)?.let { b ->
            val from = b.chapters.indexOfFirst { it.id == e.id }.coerceAtLeast(0)
            return b.chapters.drop(from).map { item(it, b.show) } to startOf(e)
        }
        return listOf(item(e, repo.show(e.showId))) to startOf(e)
    }

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        scope.launch { runCatching { block() }.onSuccess { f.set(it) }.onFailure { f.setException(it) } }
        return f
    }

    private inner class Library : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(s: MediaLibrarySession, c: MediaSession.ControllerInfo, params: LibraryParams?) =
            Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, getString(R.string.app_name)), params))

        override fun onGetChildren(s: MediaLibrarySession, c: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int,
                                   params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            val all = children(parentId)
            val slice = if (pageSize in 1 until Int.MAX_VALUE) all.drop(page * pageSize).take(pageSize) else all
            LibraryResult.ofItemList(ImmutableList.copyOf(slice), params)
        }

        override fun onGetItem(s: MediaLibrarySession, c: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> {
            val item = repo.episode(mediaId)?.let { playable(it) } ?: radio.station(mediaId)?.let { station(it) }
                ?: repo.book(mediaId.removePrefix("resume:"))?.let { resumeItem(it) }
            return Futures.immediateFuture(if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
        }

        // The app passes the audio URL as request metadata; the car passes only an ID.
        override fun onAddMediaItems(s: MediaSession, c: MediaSession.ControllerInfo, items: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(items.flatMap { it ->
                if (it.requestMetadata.mediaUri != null) listOf(it.buildUpon().setUri(it.requestMetadata.mediaUri).build())
                else expand(it.mediaId).first.take(1)
            }.toMutableList())

        override fun onSetMediaItems(s: MediaSession, c: MediaSession.ControllerInfo, items: MutableList<MediaItem>, startIndex: Int,
                                     startPositionMs: Long): ListenableFuture<MediaItemsWithStartPosition> {
            // A voice request ("play Pride and Prejudice on Brookstream"): the best match, or the last queue for a bare "play".
            val query = items.singleOrNull()?.requestMetadata?.searchQuery
            if (items.size == 1 && items[0].mediaId.isEmpty() && query != null) return future {
                val (list, start) = voice(query)
                if (list.isEmpty()) throw UnsupportedOperationException("nothing for \"$query\"")
                MediaItemsWithStartPosition(list, 0, start)
            }
            // One item from the car: play it where it was left, with the rest of its book.
            if (items.size == 1 && items[0].requestMetadata.mediaUri == null) {
                val (list, start) = expand(items[0].mediaId)
                return Futures.immediateFuture(MediaItemsWithStartPosition(list, 0, if (startPositionMs == C.TIME_UNSET) start else startPositionMs))
            }
            val list = items.map { it.buildUpon().setUri(it.requestMetadata.mediaUri).build() }
            return Futures.immediateFuture(MediaItemsWithStartPosition(list, startIndex, startPositionMs))
        }

        // The car's search button.
        override fun onSearch(s: MediaLibrarySession, c: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
            scope.launch { s.notifySearchResultChanged(c, query, search(query).size, params) }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(s: MediaLibrarySession, c: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int,
                                       params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            val all = search(query)
            val slice = if (pageSize in 1 until Int.MAX_VALUE) all.drop(page * pageSize).take(pageSize) else all
            LibraryResult.ofItemList(ImmutableList.copyOf(slice), params)
        }

        // A car (or headphones) asking to resume after the app was closed: the last queue, where it was left.
        override fun onPlaybackResumption(s: MediaSession, c: MediaSession.ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaItemsWithStartPosition> {
            val (eps, cur) = repo.loadQueue()
            if (eps.isEmpty()) return Futures.immediateFailedFuture(UnsupportedOperationException("nothing queued"))
            val p = repo.progress.value[eps[cur].id]
            return Futures.immediateFuture(MediaItemsWithStartPosition(eps.map { item(it, repo.show(it.showId)) }, cur,
                AdFree.startMs(eps[cur].id, p, back = 0)))
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        session?.run {
            save(player)
            saveQueue(player)
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ROOT = "root"

        /** A live station: no duration, no saved place; the song playing comes from the stream (ICY) as the title. */
        fun station(st: Station): MediaItem = MediaItem.Builder()
            .setMediaId(st.id)
            .setUri(st.url)
            .apply { if (st.hls) setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8) }
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(android.net.Uri.parse(st.url)).build())
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(st.name)
                .setStation(st.name)
                .setArtist(st.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.take(3).joinToString(", ").ifEmpty { "Radio" })
                .setArtworkUri(st.logo.takeIf { it.startsWith("http") }?.let { android.net.Uri.parse(it) })
                .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                .setIsBrowsable(false).setIsPlayable(true)
                .setExtras(Bundle().apply { putString("showId", "radio") })
                .build())
            .build()

        /** Played from the ad-free copy (see [AdFree]). */
        fun cleanOf(item: MediaItem) = item.mediaMetadata.extras?.getBoolean("clean") == true

        /** [e] to play: its ad-free copy when there is one and Home Assistant can be reached, unless [original]. */
        fun item(e: Episode, show: Show?, original: Boolean = false): MediaItem {
            val clean = !original && AdFree.plays(e.id)
            val url = (if (clean) AdFree.url(e.id) else null) ?: e.url
            return itemFrom(e, show, url, clean && url != e.url)
        }

        private fun itemFrom(e: Episode, show: Show?, url: String, clean: Boolean): MediaItem = MediaItem.Builder()
            .setMediaId(e.id)
            .setUri(url)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(android.net.Uri.parse(url)).build())
            .setMediaMetadata(MediaMetadata.Builder()
                .setTitle(Headline.tame(e.title))
                .setArtist(show?.title)
                .setAlbumTitle(show?.title)
                .setArtworkUri(show?.art?.takeIf { it.isNotEmpty() }?.let { android.net.Uri.parse(it) })
                .setMediaType(if (e.showId.startsWith(Repo.BOOK)) MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER else MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                .setIsBrowsable(false).setIsPlayable(true)
                .setExtras(Bundle().apply { putString("showId", e.showId); putBoolean("clean", clean) })
                .build())
            .build()
    }
}
