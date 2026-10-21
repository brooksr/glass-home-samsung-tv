/*
 * Roche Home - Library: free public-domain movies, TV, cartoons, audiobooks
 * and books, streamed (nothing is stored on the TV but resume points).
 *
 * The catalogue (catalog.json) and the books (books/<id>.json, Project
 * Gutenberg text in chapters, with covers) are built by tools/library/build.py
 * and served from the Pi (library.base in config.js). Video and audio stream
 * from the Internet Archive; LibriVox recordings are human-read audiobooks.
 *
 * Views and keys (Back always goes up one level, then closes):
 *   browse   rows of posters: Up/Down row, Left/Right item, OK details
 *   detail   Up/Down list (Play / Resume / episodes / chapters), OK choose
 *   video    OK or Play/Pause pauses, Left/Right or Rew/FF -10 s / +30 s,
 *            Up shows the bar, Down next episode
 *   audio    OK pause, Left/Right -15 s / +30 s, Up/Down chapter
 *   reader   Up/Down scroll, Left/Right chapter, OK auto-scroll on/off
 *            (Up/Down then change its speed), Play/Pause or the red button:
 *            read aloud with Home Assistant's text-to-speech
 * Resume points (position per item, chapter and paragraph per book) are kept
 * in localStorage. Nothing from the network is inserted as HTML.
 */
(function () {
  'use strict';
  var GH = window.GH || {};
  var cfg = (GH.config && GH.config.library) || {};
  var root = document.getElementById('library');
  if (!root || !cfg.base) return;
  var BASE = cfg.base.replace(/\/*$/, '/');
  var html = document.documentElement;
  var tz = typeof window.tizen !== 'undefined' ? window.tizen : null;

  // ---------- keys ----------
  var K = { LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, ENTER: 13, BACK: 10009, ESC: 27, BACKSPACE: 8,
    PLAYPAUSE: 10252, PLAY: 415, PAUSE: 19, STOP: 413, FF: 417, REW: 412, RED: 403 };
  (function registerMediaKeys() {
    if (!tz || !tz.tvinputdevice) return;
    var map = { MediaPlayPause: 'PLAYPAUSE', MediaPlay: 'PLAY', MediaPause: 'PAUSE', MediaStop: 'STOP',
      MediaFastForward: 'FF', MediaRewind: 'REW', ColorF0Red: 'RED' };
    Object.keys(map).forEach(function (name) {
      try { tz.tvinputdevice.registerKey(name); K[map[name]] = tz.tvinputdevice.getKey(name).code; } catch (e) { /* not on this remote */ }
    });
  }());

  // ---------- helpers ----------
  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }
  function clear(n) { while (n.firstChild) n.removeChild(n.firstChild); }
  function get(url, cb) {
    var x = new XMLHttpRequest();
    x.timeout = 20000;
    x.open('GET', url);
    x.onload = function () { var j = null; try { j = x.status === 200 ? JSON.parse(x.responseText) : null; } catch (e) { /* null */ } cb(j); };
    x.onerror = x.ontimeout = function () { cb(null); };
    x.send();
  }
  function hms(s) {
    s = Math.max(0, Math.floor(s || 0));
    var h = Math.floor(s / 3600);
    var m = Math.floor(s % 3600 / 60);
    var sec = s % 60;
    return (h ? h + ':' + (m < 10 ? '0' : '') : '') + m + ':' + (sec < 10 ? '0' : '') + sec;
  }
  function length(s) {
    if (!s) return '';
    var m = Math.round(s / 60);
    return m >= 60 ? Math.floor(m / 60) + ' h ' + (m % 60) + ' min' : m + ' min';
  }
  function fileUrl(id, f) {
    return 'https://archive.org/download/' + encodeURIComponent(id) + '/' + f.split('/').map(encodeURIComponent).join('/');
  }
  function thumb(it, kind) {
    return kind === 'books' ? BASE + 'books/' + it.id + '.jpg' : 'https://archive.org/services/img/' + encodeURIComponent(it.id);
  }

  // ---------- resume points ----------
  var RKEY = 'gh.library.v1';
  var saved = {};
  try { saved = JSON.parse(localStorage.getItem(RKEY)) || {}; } catch (e) { saved = {}; }
  function persist() { try { localStorage.setItem(RKEY, JSON.stringify(saved)); } catch (e) { /* full or off */ } }
  function remember(kind, it, data) {
    data.kind = kind;
    data.title = it.title;
    data.at = Date.now();
    saved[it.id] = data;
    persist();
  }
  function forget(id) { delete saved[id]; persist(); }

  // ---------- state ----------
  var catalog = null;
  var rows = [];             // [{ kind, label, items }], with "Continue" first when there is any
  var view = null;           // browse | detail | video | audio | reader
  var rowFocus = 0;
  var colFocus = [];
  var current = null;        // { kind, it }
  var listFocus = 0;
  var listItems = [];        // detail actions
  var media = null;          // <video> or <audio>
  var playing = null;        // { kind, it, index }
  var hudTimer = null;
  var saveTimer = null;
  var readTimer = null;

  function byId(id) {
    var found = null;
    (catalog ? catalog.rows : []).forEach(function (r) { r.items.forEach(function (it) { if (it.id === id) found = { kind: r.kind, it: it }; }); });
    return found;
  }

  function buildRows() {
    rows = [];
    var cont = Object.keys(saved).map(function (id) { return { id: id, at: saved[id].at }; })
      .sort(function (a, b) { return b.at - a.at; })
      .map(function (s) { var f = byId(s.id); return f ? { kind: f.kind, it: f.it } : null; })
      .filter(Boolean).slice(0, 12);
    if (cont.length) rows.push({ kind: 'continue', label: 'Continue', items: cont });
    catalog.rows.forEach(function (r) {
      rows.push({ kind: r.kind, label: r.label, items: r.items.map(function (it) { return { kind: r.kind, it: it }; }) });
    });
    while (colFocus.length < rows.length) colFocus.push(0);
    rowFocus = Math.min(rowFocus, rows.length - 1);
  }

  // ---------- browse ----------
  function renderBrowse() {
    clear(root);
    var page = el('div', 'lib-browse');
    page.appendChild(el('div', 'lib-title', 'Library'));
    var track = el('div', 'lib-rows');
    rows.forEach(function (r, ri) {
      var row = el('div', 'lib-row' + (ri === rowFocus ? ' focused' : ''));
      row.appendChild(el('div', 'lib-rowlabel', r.label));
      var strip = el('div', 'lib-strip');
      var c = colFocus[ri];
      var near = Math.abs(ri - rowFocus) <= 1;
      r.items.forEach(function (x, ci) {
        var card = el('div', 'lib-card ' + x.kind + (ri === rowFocus && ci === c ? ' focused' : ''));
        var art = el('div', 'lib-art');
        // Only nearby posters load: the TV is short of memory.
        if (near && Math.abs(ci - c) <= 7) {
          var img = new Image();
          img.alt = '';
          img.onerror = function () { img.style.display = 'none'; };
          img.src = thumb(x.it, x.kind);
          art.appendChild(img);
        }
        var s = saved[x.it.id];
        if (s && s.dur) {
          var bar = el('div', 'lib-progress');
          var fill = el('div', 'lib-fill');
          fill.style.width = Math.min(100, Math.round(100 * s.t / s.dur)) + '%';
          bar.appendChild(fill);
          art.appendChild(bar);
        }
        card.appendChild(art);
        card.appendChild(el('div', 'lib-name', x.it.title));
        strip.appendChild(card);
      });
      strip.style.transform = 'translateX(' + (-Math.max(0, c - 2) * 330) + 'px)';
      row.appendChild(strip);
      track.appendChild(row);
    });
    track.style.transform = 'translateY(' + (-Math.max(0, rowFocus - 1) * 360) + 'px)';
    page.appendChild(track);
    var x = rows[rowFocus] && rows[rowFocus].items[colFocus[rowFocus]];
    if (x) {
      var info = el('div', 'lib-info');
      info.appendChild(el('div', 'lib-info-title', x.it.title + (x.it.year ? ' (' + x.it.year + ')' : '')));
      var sub = x.kind === 'books' ? x.it.author + ' · ' + x.it.chapters + ' chapters · about ' + length(x.it.minutes * 60) + ' to read'
        : (x.it.files.length > 1 ? x.it.files.length + (x.kind === 'audiobooks' ? ' chapters' : ' episodes') : length(x.it.files[0].s));
      info.appendChild(el('div', 'lib-info-sub', sub));
      page.appendChild(info);
    }
    root.appendChild(page);
  }

  // ---------- detail ----------
  function openDetail(x) {
    current = x;
    listFocus = 0;
    view = 'detail';
    var it = x.it;
    var s = saved[it.id];
    listItems = [];
    if (x.kind === 'books') {
      if (s) listItems.push({ label: 'Continue reading', sub: (s.chTitle || 'Chapter ' + (s.ch + 1)), run: function () { openReader(it, s.ch, s.p); } });
      listItems.push({ label: s ? 'Start over' : 'Start reading', run: function () { openReader(it, 0, 0); } });
      listItems.push({ label: 'Read aloud', sub: 'robot voice from Home Assistant', run: function () { openReader(it, s ? s.ch : 0, s ? s.p : 0, true); } });
      var audio = it.audio && byId(it.audio);
      if (audio) listItems.push({ label: 'Listen to the audiobook', sub: 'LibriVox, read by volunteers', run: function () { openDetail(audio); } });
      renderDetail();
      // Chapter list comes with the book itself.
      get(BASE + 'books/' + it.id + '.json', function (book) {
        if (!book || view !== 'detail' || current.it !== it) return;
        book.chapters.forEach(function (c, i) { listItems.push({ label: c.title, run: function () { openReader(it, i, 0); } }); });
        renderDetail();
      });
      return;
    }
    if (s && s.dur) {
      var ep = it.files[s.i];
      listItems.push({ label: 'Resume', sub: (it.files.length > 1 ? (ep.t || 'Part ' + (s.i + 1)) + ' · ' : '') + hms(s.t) + ' of ' + hms(s.dur), run: function () { play(x, s.i, s.t); } });
    }
    if (it.files.length === 1) {
      listItems.push({ label: s ? 'Play from the start' : 'Play', sub: length(it.files[0].s), run: function () { play(x, 0, 0); } });
    } else {
      it.files.forEach(function (f, i) {
        listItems.push({ label: f.t || (x.kind === 'audiobooks' ? 'Chapter ' : 'Part ') + (i + 1), sub: length(f.s), run: function () { play(x, i, 0); } });
      });
    }
    if (s) listItems.push({ label: 'Remove from Continue', run: function () { forget(it.id); openDetail(x); } });
    renderDetail();
  }

  function renderDetail() {
    var x = current;
    var it = x.it;
    clear(root);
    var page = el('div', 'lib-detail');
    var art = el('div', 'lib-detail-art ' + x.kind);
    var img = new Image();
    img.alt = '';
    img.src = thumb(it, x.kind);
    art.appendChild(img);
    page.appendChild(art);
    var side = el('div', 'lib-detail-side');
    side.appendChild(el('div', 'lib-detail-title', it.title));
    side.appendChild(el('div', 'lib-detail-sub', [it.year, it.author, x.kind === 'books' ? 'Project Gutenberg' : 'Internet Archive'].filter(Boolean).join(' · ')));
    if (it.desc) side.appendChild(el('div', 'lib-detail-desc', it.desc));
    var list = el('div', 'lib-list');
    var first = Math.max(0, Math.min(listFocus - 3, listItems.length - 8));
    listItems.slice(first, first + 8).forEach(function (a, i) {
      var row = el('div', 'lib-item' + (first + i === listFocus ? ' focused' : ''));
      row.appendChild(el('span', 'lib-item-label', a.label));
      if (a.sub) row.appendChild(el('span', 'lib-item-sub', a.sub));
      list.appendChild(row);
    });
    if (listItems.length > 8) list.appendChild(el('div', 'lib-more', (listFocus + 1) + ' of ' + listItems.length));
    side.appendChild(list);
    page.appendChild(side);
    root.appendChild(page);
  }

  // ---------- video and audio ----------
  function stopMedia() {
    clearInterval(saveTimer);
    clearTimeout(hudTimer);
    if (media) {
      savePosition();
      try { media.pause(); media.removeAttribute('src'); media.load(); } catch (e) { /* gone */ }
      media = null;
    }
    html.classList.remove('lib-playing');
    playing = null;
  }

  function savePosition() {
    if (!media || !playing || !isFinite(media.duration) || !media.duration) return;
    var t = media.currentTime;
    var last = playing.index === playing.it.files.length - 1;
    if (last && media.duration - t < 60) { forget(playing.it.id); return; }   // finished
    if (t < 15 && playing.index === 0) return;
    remember(playing.kind, playing.it, { i: playing.index, t: Math.floor(t), dur: Math.floor(media.duration) });
  }

  function play(x, index, at) {
    stopMedia();
    var kind = x.kind;
    var it = x.it;
    var video = kind !== 'audiobooks';
    view = video ? 'video' : 'audio';
    playing = { kind: kind, it: it, index: index };
    clear(root);
    var page = el('div', video ? 'lib-video' : 'lib-audio');
    media = document.createElement(video ? 'video' : 'audio');
    media.className = 'lib-media';
    media.preload = 'auto';
    media.src = fileUrl(it.id, it.files[index].f);
    if (at) media.addEventListener('loadedmetadata', function once() { media.removeEventListener('loadedmetadata', once); try { media.currentTime = at; } catch (e) { /* from the start */ } });
    media.addEventListener('ended', function () {
      if (playing && playing.index + 1 < it.files.length) play(x, playing.index + 1, 0);
      else { forget(it.id); back(); }
    });
    media.addEventListener('error', function () { status('Couldn’t play this. The Internet Archive may be busy; try again or another title.'); });
    media.addEventListener('waiting', function () { status('Loading…'); });
    media.addEventListener('playing', function () { status(''); });
    media.addEventListener('timeupdate', renderHud);
    page.appendChild(media);
    if (!video) {
      var art = el('div', 'lib-audio-art');
      var img = new Image();
      img.alt = '';
      img.src = thumb(it, kind);
      art.appendChild(img);
      page.appendChild(art);
    }
    var hud = el('div', 'lib-hud on');
    hud.appendChild(el('div', 'lib-hud-title', it.title));
    hud.appendChild(el('div', 'lib-hud-sub', it.files.length > 1 ? (it.files[index].t || (video ? 'Part ' : 'Chapter ') + (index + 1)) + ' · ' + (index + 1) + ' of ' + it.files.length : ''));
    var bar = el('div', 'lib-bar');
    bar.appendChild(el('div', 'lib-bar-fill'));
    hud.appendChild(bar);
    hud.appendChild(el('div', 'lib-hud-time'));
    hud.appendChild(el('div', 'lib-hud-status'));
    hud.appendChild(el('div', 'lib-hud-keys', video ? 'OK pause · ◀ 10 s · ▶ 30 s · ▼ next part · Back stop'
      : 'OK pause · ◀ 15 s · ▶ 30 s · ▲▼ chapter · Back stop'));
    page.appendChild(hud);
    root.appendChild(page);
    if (video) html.classList.add('lib-playing');
    var p = media.play();
    if (p && p.catch) p.catch(function () { /* autoplay refused: OK starts it */ });
    saveTimer = setInterval(savePosition, 10000);
    showHud();
  }

  function status(text) {
    var n = root.querySelector('.lib-hud-status');
    if (n) n.textContent = text;
    if (text) showHud();
  }
  function renderHud() {
    if (!media) return;
    var d = media.duration;
    var fill = root.querySelector('.lib-bar-fill');
    if (fill && isFinite(d) && d) fill.style.width = (100 * media.currentTime / d) + '%';
    var t = root.querySelector('.lib-hud-time');
    if (t) t.textContent = hms(media.currentTime) + (isFinite(d) ? ' / ' + hms(d) : '') + (media.paused ? '  ·  paused' : '');
  }
  function showHud() {
    var hud = root.querySelector('.lib-hud');
    if (!hud) return;
    hud.classList.add('on');
    renderHud();
    clearTimeout(hudTimer);
    if (view === 'video') hudTimer = setTimeout(function () { if (media && !media.paused) hud.classList.remove('on'); }, 4000);
  }
  function seek(d) {
    if (!media || !isFinite(media.duration)) return;
    media.currentTime = Math.max(0, Math.min(media.duration - 1, media.currentTime + d));
    showHud();
  }
  function togglePause() {
    if (!media) return;
    if (media.paused) { var p = media.play(); if (p && p.catch) p.catch(function () {}); } else { media.pause(); savePosition(); }
    showHud();
  }

  // ---------- reader ----------
  var book = null;
  var chapter = 0;
  var auto = null;           // auto-scroll timer
  var speed = 2;             // 1..6, pixels per tick
  var voice = null;          // { audio, para, next: { para, url } }

  function openReader(it, ch, para, aloud) {
    view = 'reader';
    clear(root);
    root.appendChild(el('div', 'lib-loading', 'Opening ' + it.title + '…'));
    get(BASE + 'books/' + it.id + '.json', function (b) {
      if (view !== 'reader') return;
      if (!b) { clear(root); root.appendChild(el('div', 'lib-loading', 'Couldn’t open the book. Is the Pi reachable?')); return; }
      book = { it: it, data: b };
      showChapter(Math.min(ch || 0, b.chapters.length - 1), para || 0);
      if (aloud) startVoice();
    });
  }

  function showChapter(ch, para) {
    stopAuto();
    chapter = ch;
    var c = book.data.chapters[ch];
    clear(root);
    var page = el('div', 'lib-reader');
    var col = el('div', 'lib-page');
    col.appendChild(el('div', 'lib-book', book.data.title + ' · ' + book.data.author));
    col.appendChild(el('div', 'lib-chapter', c.title));
    c.paras.forEach(function (p, i) { var n = el('p', 'lib-para', p); n.setAttribute('data-i', i); col.appendChild(n); });
    col.appendChild(el('div', 'lib-end', ch + 1 < book.data.chapters.length ? '▶ next chapter' : 'The end'));
    page.appendChild(col);
    page.appendChild(el('div', 'lib-reader-keys'));
    root.appendChild(page);
    var target = col.querySelector('[data-i="' + (para || 0) + '"]');
    page.scrollTop = para && target ? target.offsetTop - 120 : 0;
    readerKeys();
    saveReading();
  }

  function scroller() { return root.querySelector('.lib-reader'); }
  function topPara() {
    var s = scroller();
    if (!s) return 0;
    var ps = s.querySelectorAll('.lib-para');
    for (var i = 0; i < ps.length; i++) if (ps[i].offsetTop + ps[i].offsetHeight > s.scrollTop + 100) return i;
    return 0;
  }
  function saveReading() {
    if (!book) return;
    var c = book.data.chapters[chapter];
    remember('books', book.it, { ch: chapter, p: voice ? voice.para : topPara(), chTitle: c.title });
  }
  function readerKeys() {
    var n = root.querySelector('.lib-reader-keys');
    if (!n) return;
    n.textContent = voice ? 'Reading aloud · OK or Play stop · ◀▶ chapter'
      : auto ? 'Auto-scroll · speed ' + speed + ' · ▲▼ speed · OK stop'
      : '▲▼ scroll · ◀▶ chapter · OK auto-scroll · Play or red: read aloud · Back close';
  }
  function scrollBy(d) {
    var s = scroller();
    if (s) s.scrollTop += d;
  }
  function stopAuto() { clearInterval(auto); auto = null; }
  function toggleAuto() {
    if (auto) { stopAuto(); saveReading(); } else {
      auto = setInterval(function () {
        var s = scroller();
        if (!s) return stopAuto();
        var before = s.scrollTop;
        s.scrollTop += speed;
        if (s.scrollTop === before) { stopAuto(); readerKeys(); }   // end of chapter
      }, 50);
    }
    readerKeys();
  }

  // Read aloud: one paragraph at a time (long ones split at sentences),
  // highlighted and kept in view; the next one is fetched while this one plays.
  function pieces(text) {
    if (text.length <= 900) return [text];
    var out = [];
    var cur = '';
    // (no lookbehind: Tizen 4's engine can't parse it)
    text.replace(/([.!?”"])\s+/g, '$1\u0001').split('\u0001').forEach(function (s) {
      if ((cur + ' ' + s).length > 900 && cur) { out.push(cur); cur = s; } else cur = cur ? cur + ' ' + s : s;
    });
    if (cur) out.push(cur);
    return out;
  }
  function startVoice() {
    if (!GH.ha || !GH.ha.ttsUrl) { readerKeys(); return; }
    stopAuto();
    voice = { audio: new Audio(), para: topPara(), part: 0, next: null, token: {} };
    readerKeys();
    speak();
  }
  function stopVoice() {
    if (!voice) return;
    try { voice.audio.pause(); } catch (e) { /* gone */ }
    var hl = root.querySelector('.lib-para.speaking');
    if (hl) hl.classList.remove('speaking');
    saveReading();
    voice = null;
    readerKeys();
  }
  function fetchSpeech(para, part, cb) {
    var c = book.data.chapters[chapter];
    if (para >= c.paras.length) { cb(null); return; }
    var bits = pieces(c.paras[para]);
    GH.ha.ttsUrl(bits[part], function (url) { cb({ para: para, part: part, last: part === bits.length - 1, url: url }); });
  }
  function speak() {
    var v = voice;
    if (!v) return;
    var c = book.data.chapters[chapter];
    if (v.para >= c.paras.length) {
      if (chapter + 1 < book.data.chapters.length) { stopVoice(); showChapter(chapter + 1, 0); startVoice(); } else stopVoice();
      return;
    }
    var got = v.next && v.next.para === v.para && v.next.part === v.part ? v.next : null;
    function go(item) {
      if (voice !== v) return;
      if (!item || !item.url) { stopVoice(); var k = root.querySelector('.lib-reader-keys'); if (k) k.textContent = 'Couldn’t get the voice from Home Assistant.'; return; }
      var old = root.querySelector('.lib-para.speaking');
      if (old) old.classList.remove('speaking');
      var p = root.querySelector('.lib-para[data-i="' + v.para + '"]');
      if (p) {
        p.classList.add('speaking');
        var s = scroller();
        s.scrollTop = p.offsetTop - 200;
      }
      v.audio.src = item.url;
      v.audio.onended = function () {
        if (voice !== v) return;
        if (item.last) { v.para += 1; v.part = 0; } else v.part += 1;
        speak();
      };
      v.audio.onerror = function () { if (voice === v) { v.para += 1; v.part = 0; speak(); } };
      var pr = v.audio.play();
      if (pr && pr.catch) pr.catch(function () {});
      // Fetch the next piece while this one plays.
      var nextPara = item.last ? v.para + 1 : v.para;
      var nextPart = item.last ? 0 : v.part + 1;
      fetchSpeech(nextPara, nextPart, function (n) { if (voice === v) v.next = n; });
      if (v.part === 0) saveReading();
    }
    if (got) go(got); else fetchSpeech(v.para, v.part, go);
  }

  // ---------- navigation ----------
  function open() {
    root.classList.add('open');
    view = 'browse';
    if (catalog) { buildRows(); renderBrowse(); return; }
    clear(root);
    root.appendChild(el('div', 'lib-loading', 'Loading the library…'));
    get(BASE + 'catalog.json', function (c) {
      if (view !== 'browse') return;
      if (!c) { clear(root); root.appendChild(el('div', 'lib-loading', 'Couldn’t load the library from the Pi.')); return; }
      catalog = c;
      buildRows();
      renderBrowse();
    });
  }
  function close() {
    stopMedia();
    stopVoice();
    stopAuto();
    view = null;
    root.classList.remove('open');
    clear(root);
  }
  function back() {
    if (view === 'video' || view === 'audio') { stopMedia(); openDetail(current); return; }
    if (view === 'reader') { stopVoice(); stopAuto(); saveReading(); book = null; if (current) openDetail(current); else { buildRows(); view = 'browse'; renderBrowse(); } return; }
    if (view === 'detail') { buildRows(); view = 'browse'; renderBrowse(); return; }
    close();
  }

  function onKey(e) {
    if (!view) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    if (e.type !== 'keydown') return;
    var k = e.keyCode;
    if (k === K.BACK || k === K.ESC || k === K.BACKSPACE || k === K.STOP) { back(); return; }
    if (view === 'browse') {
      var r = rows[rowFocus];
      if (!r) return;
      if (k === K.UP) rowFocus = Math.max(0, rowFocus - 1);
      else if (k === K.DOWN) rowFocus = Math.min(rows.length - 1, rowFocus + 1);
      else if (k === K.LEFT) colFocus[rowFocus] = Math.max(0, colFocus[rowFocus] - 1);
      else if (k === K.RIGHT) colFocus[rowFocus] = Math.min(r.items.length - 1, colFocus[rowFocus] + 1);
      else if (k === K.ENTER && !e.repeat) { openDetail(r.items[colFocus[rowFocus]]); return; }
      renderBrowse();
      return;
    }
    if (view === 'detail') {
      if (k === K.UP) listFocus = Math.max(0, listFocus - 1);
      else if (k === K.DOWN) listFocus = Math.min(listItems.length - 1, listFocus + 1);
      else if ((k === K.ENTER || k === K.PLAY || k === K.PLAYPAUSE) && !e.repeat && listItems[listFocus]) { listItems[listFocus].run(); return; }
      renderDetail();
      return;
    }
    if (view === 'video' || view === 'audio') {
      var video = view === 'video';
      if (k === K.ENTER || k === K.PLAYPAUSE || k === K.PLAY || k === K.PAUSE) { if (!e.repeat) togglePause(); }
      else if (k === K.LEFT || k === K.REW) seek(video ? -10 : -15);
      else if (k === K.RIGHT || k === K.FF) seek(30);
      else if (k === K.UP && video) showHud();
      else if (k === K.DOWN) {
        if (playing && playing.index + 1 < playing.it.files.length) play({ kind: playing.kind, it: playing.it }, playing.index + 1, 0);
      } else if (k === K.UP && !video && playing && playing.index > 0) play({ kind: playing.kind, it: playing.it }, playing.index - 1, 0);
      return;
    }
    if (view === 'reader' && book) {
      if (k === K.PLAYPAUSE || k === K.PLAY || k === K.RED || (k === K.ENTER && voice)) {
        if (!e.repeat) { if (voice) stopVoice(); else startVoice(); }
        return;
      }
      if (k === K.ENTER && !e.repeat) { toggleAuto(); return; }
      if (k === K.LEFT && chapter > 0) { var wasV = !!voice; stopVoice(); showChapter(chapter - 1, 0); if (wasV) startVoice(); return; }
      if (k === K.RIGHT && chapter + 1 < book.data.chapters.length) { var wasVoice = !!voice; stopVoice(); showChapter(chapter + 1, 0); if (wasVoice) startVoice(); return; }
      if (voice) return;
      if (auto) {
        if (k === K.UP) speed = Math.max(1, speed - 1);
        else if (k === K.DOWN) speed = Math.min(6, speed + 1);
        readerKeys();
        return;
      }
      if (k === K.UP) scrollBy(-560);
      else if (k === K.DOWN) scrollBy(560);
      clearTimeout(readTimer);
      readTimer = setTimeout(saveReading, 1500);
    }
  }
  window.addEventListener('keydown', onKey, true);
  window.addEventListener('keyup', onKey, true);

  // Leaving Roche Home (Home button, another app): stop and keep the place.
  document.addEventListener('visibilitychange', function () {
    if (!document.hidden || !view) return;
    if (media) { savePosition(); media.pause(); }
    stopVoice();
    stopAuto();
  });

  GH.library = { open: open, isOpen: function () { return !!view; } };

  // Preview (desktop browser): ?library=1 opens it.
  if (GH.config && GH.config.preview && GH.query && GH.query.library) setTimeout(open, 300);
}());
