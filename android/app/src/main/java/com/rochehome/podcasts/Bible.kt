package com.rochehome.podcasts

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Bible reader's texts (assets/bible, from tools/bible/build.py; only in
 * the shareable build). One book of one version is loaded at a time.
 */
class Bible(private val context: Context) {
    class Version(val code: String, val name: String, val lang: String)

    private val index = runCatching { JSONObject(context.assets.open("bible/books.json").bufferedReader().readText()) }.getOrNull()
    val available = index != null
    val versions: List<Version> = index?.getJSONArray("versions")?.let { a ->
        (0 until a.length()).map { a.getJSONArray(it).let { v -> Version(v.getString(0), v.getString(1), v.getString(2)) } }
    }.orEmpty()
    /** The 66 books (names) and how many chapters each has. */
    val books: List<Pair<String, Int>> = index?.getJSONArray("books")?.let { a ->
        (0 until a.length()).map { a.getJSONArray(it).let { b -> b.getString(0) to b.getInt(1) } }
    }.orEmpty()
    val english get() = versions.filter { it.lang == "en" }

    /** "John 3", but "Psalm 23". */
    fun label(book: Int, chapter: Int) = (if (books[book].first == "Psalms") "Psalm" else books[book].first) + " $chapter"

    /** The original-language text for a book: Hebrew for the Old Testament, Greek for the New. */
    fun original(book: Int) = if (book < 39) "WLC" else "TR"

    private val cache = object : LinkedHashMap<String, List<List<String>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<List<String>>>) = size > 12
    }

    /** A book's chapters (verse n at index n-1, "" where this version has none); empty if the version doesn't have it. */
    suspend fun book(code: String, book: Int): List<List<String>> = withContext(Dispatchers.IO) {
        val key = "$code/$book"
        synchronized(cache) { cache[key] }?.let { return@withContext it }
        val chapters = runCatching {
            val a = JSONArray(context.assets.open("bible/$key.json").bufferedReader().readText())
            (0 until a.length()).map { c -> a.getJSONArray(c).let { v -> (0 until v.length()).map { v.getString(it) } } }
        }.getOrDefault(emptyList())
        synchronized(cache) { cache[key] = chapters }
        chapters
    }

    /** Book and chapter (0-based book, 1-based chapter) at the start of an audio part's title, e.g. "First Samuel 17-27". */
    fun refOf(title: String): Pair<Int, Int>? {
        val t = title.replace(Regex("^First "), "1 ").replace(Regex("^Second "), "2 ").replace(Regex("^Third "), "3 ")
            .replace(Regex("^Psalm "), "Psalms ")
        val i = books.indices.filter { t.startsWith(books[it].first + " ") }.maxByOrNull { books[it].first.length } ?: return null
        val ch = Regex("\\d+").find(t.substring(books[i].first.length))?.value?.toIntOrNull() ?: 1
        return i to ch.coerceIn(1, books[i].second)
    }
}

/** Copyrighted versions: read on BibleGateway, which is licensed to show them. */
private val LINKED = listOf("NIV", "ESV", "NLT", "NASB", "CSB", "NKJV")

private fun gateway(book: String, chapter: Int, verse: Int?, version: String) =
    "https://www.biblegateway.com/passage/?search=" + Uri.encode("$book $chapter" + (verse?.let { ":$it" } ?: "")) + "&version=$version"

/** Bible Hub's interlinear: the Hebrew or Greek word by word, with English under each. */
private fun interlinear(book: String, chapter: Int, verse: Int?): String {
    val slug = if (book == "Song of Solomon") "songs" else book.lowercase().replace(' ', '_')
    return "https://biblehub.com/interlinear/$slug/$chapter" + (verse?.let { "-$it" } ?: "") + ".htm"
}

private fun browse(ctx: Context, url: String) = runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

private val SIZES = listOf(16, 18, 20, 23, 26)

/**
 * A reader: one chapter at a time in one version, with book, chapter and verse
 * navigation; tap a verse to see it in every version and the original.
 * [start] is a book and chapter to open at (else where the reader left off).
 */
@Composable
fun BibleScreen(bible: Bible, start: Pair<Int, Int>?) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("bible", 0) }
    var version by rememberSaveable { mutableStateOf(prefs.getString("version", "BSB")!!) }
    var book by rememberSaveable { mutableIntStateOf(start?.first ?: prefs.getInt("book", 42)) }        // John
    var chapter by rememberSaveable { mutableIntStateOf(start?.second ?: prefs.getInt("chapter", 1)) }
    var size by rememberSaveable { mutableIntStateOf(prefs.getInt("size", 1)) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var goTo by remember { mutableStateOf<Int?>(null) }          // a verse to scroll to
    var selected by remember { mutableStateOf<Int?>(null) }      // a verse whose versions are showing
    if (book !in bible.books.indices) { book = 42; chapter = 1 }
    if (chapter !in 1..bible.books[book].second) chapter = 1
    // "Original" is Hebrew or Greek, whichever this book was written in.
    val code = if (version == "ORIG") bible.original(book) else version
    val name = bible.books[book].first

    LaunchedEffect(version, book, chapter, size) {
        prefs.edit { putString("version", version); putInt("book", book); putInt("chapter", chapter); putInt("size", size) }
    }
    var verses by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(code, book, chapter) {
        verses = null
        verses = bible.book(code, book).getOrNull(chapter - 1).orEmpty()
    }
    val list = rememberLazyListState()
    // A new chapter (or version) opens at the top, or at the verse asked for: verse n is item n (item 0 is the heading).
    LaunchedEffect(verses) {
        val v = verses ?: return@LaunchedEffect
        val target = goTo
        list.scrollToItem(if (target != null && target <= v.size) target else 0)
        goTo = null
    }

    BackHandler(enabled = picking) { picking = false }

    Column(Modifier.fillMaxSize()) {
        // Where you are, and which version.
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { picking = !picking }) {
                Text(bible.label(book, chapter), style = MaterialTheme.typography.titleMedium)
                Icon(Icons.Filled.ArrowDropDown, null)
            }
            Spacer(Modifier.weight(1f))
            // Another version keeps your place.
            VersionMenu(bible, version, book, name, chapter) { goTo = list.firstVisibleItemIndex.takeIf { i -> i > 0 }; version = it }
            IconButton(onClick = { size = (size + 1) % SIZES.size }) { Icon(Icons.Filled.FormatSize, "Text size") }
        }
        HorizontalDivider()
        if (picking) {
            Navigator(bible, book, chapter) { b, c, v -> book = b; chapter = c; goTo = v; picking = false }
            return@Column
        }
        val v = verses
        if (v == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        val rtl = code == "WLC"
        val style = TextStyle(fontFamily = FontFamily.Serif, fontSize = SIZES[size].sp, lineHeight = (SIZES[size] * 1.6f).sp,
            textDirection = if (rtl) TextDirection.Rtl else TextDirection.Content, color = MaterialTheme.colorScheme.onSurface)
        val numberColor = MaterialTheme.colorScheme.primary
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "head") {
                Column(Modifier.padding(20.dp, 20.dp, 20.dp, 8.dp)) {
                    Text(bible.label(book, chapter), style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Serif)
                    Text(bible.versions.firstOrNull { it.code == code }?.name.orEmpty(), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (v.all { it.isEmpty() }) item(key = "none") {
                Text(if (code == "WLC") "The Hebrew Bible numbers this passage differently; it's in the chapter before."
                    else "This version doesn't have this chapter.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            itemsIndexed(v, key = { i, _ -> "v$i" }) { i, text ->
                if (text.isEmpty()) return@itemsIndexed
                val n = i + 1
                Text(buildAnnotatedString {
                    withStyle(SpanStyle(color = numberColor, fontSize = 0.62.em, baselineShift = BaselineShift(0.35f), fontWeight = FontWeight.SemiBold)) { append("$n ") }
                    append(text)
                }, Modifier.fillMaxWidth().clickable { selected = n }
                    .background(if (selected == n) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .padding(horizontal = 20.dp, vertical = 3.dp), style = style)
            }
            item(key = "nav") {
                val prev = if (chapter > 1) book to chapter - 1 else if (book > 0) (book - 1) to bible.books[book - 1].second else null
                val next = if (chapter < bible.books[book].second) book to chapter + 1 else if (book < bible.books.lastIndex) (book + 1) to 1 else null
                Row(Modifier.fillMaxWidth().padding(12.dp, 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (prev != null) OutlinedButton(onClick = { book = prev.first; chapter = prev.second }) {
                        Icon(Icons.Filled.ChevronLeft, null); Text(bible.label(prev.first, prev.second))
                    } else Spacer(Modifier)
                    if (next != null) OutlinedButton(onClick = { book = next.first; chapter = next.second }) {
                        Text(bible.label(next.first, next.second)); Icon(Icons.Filled.ChevronRight, null)
                    }
                }
                Text("Berean Standard Bible, KJV, WEB, ASV, Young's, the Westminster Leningrad Codex and the Textus Receptus are public domain. " +
                    "NIV, ESV and the others open on BibleGateway.", Modifier.padding(20.dp, 0.dp), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    selected?.let { n ->
        VerseSheet(bible, book, chapter, n, onDismiss = { selected = null }, read = { c -> version = c; selected = null; goTo = n })
    }
}

@Composable
private fun VersionMenu(bible: Bible, version: String, book: Int, name: String, chapter: Int, set: (String) -> Unit) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text(if (version == "ORIG") (if (book < 39) "Hebrew" else "Greek") else version)
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            bible.english.forEach { v ->
                DropdownMenuItem(text = { Text("${v.code} · ${v.name}") }, onClick = { set(v.code); open = false },
                    leadingIcon = if (v.code == version) ({ RadioButton(true, null) }) else null)
            }
            DropdownMenuItem(text = { Text("Original · Hebrew (Old Testament), Greek (New)") }, onClick = { set("ORIG"); open = false },
                leadingIcon = if (version == "ORIG") ({ RadioButton(true, null) }) else null)
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Interlinear (Bible Hub)") }, onClick = { browse(ctx, interlinear(name, chapter, null)); open = false },
                trailingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) })
            LINKED.forEach { v ->
                DropdownMenuItem(text = { Text("$v on BibleGateway") }, onClick = { browse(ctx, gateway(name, chapter, null, v)); open = false },
                    trailingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) })
            }
        }
    }
}

/** Book, then chapter, then verse. */
@Composable
private fun Navigator(bible: Bible, book: Int, chapter: Int, go: (Int, Int, Int?) -> Unit) {
    var b by rememberSaveable { mutableStateOf<Int?>(null) }
    var c by rememberSaveable { mutableStateOf<Int?>(null) }
    BackHandler(enabled = b != null) { if (c != null) c = null else b = null }
    val pb = b
    val pc = c
    when {
        pb == null -> LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Old Testament" to (0 until 39), "New Testament" to (39 until 66)).forEach { (title, range) ->
                item(key = title, span = { GridItemSpan(maxLineSpan) }) {
                    Text(title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
                items(range.toList(), key = { "b$it" }) { i ->
                    Cell(bible.books[i].first, i == book) { b = i }
                }
            }
        }
        pc == null -> Numbers("${bible.books[pb].first}: chapter", bible.books[pb].second, if (pb == book) chapter else null) { c = it }
        else -> {
            var count by remember(pb, pc) { mutableStateOf<Int?>(null) }
            LaunchedEffect(pb, pc) { count = bible.book("KJV", pb).getOrNull(pc - 1)?.size ?: 0 }
            count?.let { n ->
                Column {
                    TextButton(onClick = { go(pb, pc, null) }, Modifier.padding(horizontal = 8.dp)) { Text("Read ${bible.label(pb, pc)} from the start") }
                    Numbers("Verse", n, null) { go(pb, pc, it) }
                }
            }
        }
    }
}

@Composable
private fun Numbers(title: String, n: Int, current: Int?, pick: (Int) -> Unit) {
    LazyVerticalGrid(GridCells.Adaptive(56.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
        items((1..n).toList()) { i -> Cell("$i", i == current) { pick(i) } }
    }
}

@Composable
private fun Cell(text: String, current: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(8.dp))
        .background(if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
        .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
}

/** One verse in every version, the original, and links to the rest. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun VerseSheet(bible: Bible, book: Int, chapter: Int, verse: Int, onDismiss: () -> Unit, read: (String) -> Unit) {
    val ctx = LocalContext.current
    val name = bible.books[book].first
    val codes = bible.english.map { it.code } + bible.original(book)
    var texts by remember(book, chapter, verse) { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(book, chapter, verse) {
        texts = codes.associateWith { c -> bible.book(c, book).getOrNull(chapter - 1)?.getOrNull(verse - 1).orEmpty() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Text("${bible.label(book, chapter)}:$verse", Modifier.padding(20.dp, 0.dp, 20.dp, 8.dp), style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif) }
            codes.forEach { c ->
                item(key = c) {
                    val v = bible.versions.first { it.code == c }
                    Column(Modifier.fillMaxWidth().clickable { read(if (v.lang == "en") c else "ORIG") }.padding(20.dp, 10.dp)) {
                        Text(if (v.lang == "en") v.code else v.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(texts[c].orEmpty().ifEmpty { if (v.lang == "en") "—" else "Numbered differently in the original; see the interlinear." },
                            style = TextStyle(fontFamily = FontFamily.Serif, fontSize = if (v.lang == "he") 22.sp else 17.sp, lineHeight = 26.sp,
                                textDirection = if (v.lang == "he") TextDirection.Rtl else TextDirection.Content),
                            color = if (texts[c].isNullOrEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            item {
                Column(Modifier.padding(20.dp, 12.dp)) {
                    Text("More", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = { browse(ctx, interlinear(name, chapter, verse)) }) {
                        Text("Word by word: ${if (book < 39) "Hebrew" else "Greek"} with English (Bible Hub)")
                        Spacer(Modifier.width(6.dp)); Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(16.dp))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LINKED.forEach { v -> AssistChip(onClick = { browse(ctx, gateway(name, chapter, verse, v)) }, label = { Text(v) },
                            trailingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(14.dp)) }) }
                    }
                }
            }
        }
    }
}
