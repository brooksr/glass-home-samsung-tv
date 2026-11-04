/*
 * Roche Home - Podcasts: the household's podcast subscriptions, streamed.
 *
 * The feeds are read every 30 minutes on the Home Assistant laptop by
 * tools/podcasts/build.py, which writes podcasts.json (shows and their newest
 * episodes), shows/<id>.json (one show's episodes) and art/<id>.jpg; Home
 * Assistant serves them (podcasts.base in config.js). The audio streams from
 * each podcast's own host.
 *
 * Listening progress is kept in localStorage and shared with the Android app
 * through a Home Assistant entity (podcasts.syncEntity): its `progress`
 * attribute maps episode ID to [seconds, duration, played 0/1, updated (unix
 * s), show ID]. Each side reads it, keeps the newer entry per episode and
 * writes back. States set this way don't survive a Home Assistant restart;
 * the next device to sync puts them back.
 *
 * Views and keys (Back always goes up one level, then closes):
 *   browse   rows (Continue, Latest, then the shows by category): arrows
 *            move, OK opens an episode (plays, resuming) or a show
 *   show     Up/Down episodes, OK play; the first two rows switch the order
 *            (newest or oldest first) and hide played episodes, per show
 *   audio    OK pause, Left/Right -15 s / +30 s, Down the menu (speed, sleep
 *            timer, chapters, transcript), Rew/FF previous/next chapter
 *   menu     Up/Down move, Left/Right change speed or sleep timer, OK choose
 *   transcript (beside the player) follows along when the transcript is timed;
 *            Up/Down pick a line, OK jumps there
 * Chapters and transcripts come from extras/<episode id>.json, which build.py
 * makes for the newest 10 episodes of shows whose feeds publish them.
 *
 * Cast from the phone: the Android app writes what it's playing to a second
 * entity (podcasts.castEntity): attributes { at (unix s), ep { id, t, u, s, x },
 * show { id, title, art }, pos, speed, queue [{ ep, show }] }. While Roche Home
 * is on screen this checks it every 4 s; a cast newer than the last one handled
 * (and under 90 s old) opens Podcasts and plays from the phone's place, then
 * carries on through the phone's Up next (an audiobook's next parts).
 * Nothing from the network is inserted as HTML.
 */
(function () {
  'use strict';
  var GH = window.GH || {};
  var cfg = (GH.config && GH.config.podcasts) || {};
  var root = document.getElementById('podcasts');
  if (!root || !cfg.base) return;
  var BASE = cfg.base.replace(/\/*$/, '/');
  var haCfg = (GH.config && GH.config.homeAssistant) || {};
  var HA = haCfg.url && GH.haToken ? haCfg.url.replace(/\/+$/, '') : '';
  var ENTITY = cfg.syncEntity || 'sensor.roche_podcasts';
  var CAST = cfg.castEntity || 'sensor.roche_podcasts_cast';
  var tz = typeof window.tizen !== 'undefined' ? window.tizen : null;

  var K = { LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, ENTER: 13, BACK: 10009, ESC: 27, BACKSPACE: 8,
    PLAYPAUSE: 10252, PLAY: 415, PAUSE: 19, STOP: 413, FF: 417, REW: 412 };
  (function registerMediaKeys() {
    if (!tz || !tz.tvinputdevice) return;
    var map = { MediaPlayPause: 'PLAYPAUSE', MediaPlay: 'PLAY', MediaPause: 'PAUSE', MediaStop: 'STOP',
      MediaFastForward: 'FF', MediaRewind: 'REW' };
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
  function request(method, url, body, headers, cb) {
    var x = new XMLHttpRequest();
    x.timeout = 20000;
    x.open(method, url);
    Object.keys(headers || {}).forEach(function (h) { x.setRequestHeader(h, headers[h]); });
    x.onload = function () { var j = null; try { j = x.status >= 200 && x.status < 300 ? JSON.parse(x.responseText) : null; } catch (e) { /* null */ } cb(j); };
    x.onerror = x.ontimeout = function () { cb(null); };
    x.send(body == null ? null : JSON.stringify(body));
  }
  function get(url, cb) { request('GET', url, null, null, cb); }
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
  var MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  function day(unix) {
    if (!unix) return '';
    var d = new Date(unix * 1000);
    var today = new Date();
    today.setHours(0, 0, 0, 0);
    var days = Math.floor((today.getTime() - new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()) / 86400000);
    if (days <= 0) return 'Today';
    if (days === 1) return 'Yesterday';
    if (days < 7) return days + ' days ago';
    return MONTHS[d.getMonth()] + ' ' + d.getDate() + (d.getFullYear() !== today.getFullYear() ? ', ' + d.getFullYear() : '');
  }
  // Cast shows (added on the phone, audiobooks) carry their own full art URL.
  function artUrl(show) { return show && show.art ? (/^https?:/.test(show.art) ? show.art : BASE + show.art) : ''; }

  // ---------- progress ----------
  // id -> [t, dur, done, at, showId]
  var PKEY = 'gh.podcasts.v1';
  var progress = {};
  try { progress = JSON.parse(localStorage.getItem(PKEY)) || {}; } catch (e) { progress = {}; }
  function persist() { try { localStorage.setItem(PKEY, JSON.stringify(progress)); } catch (e) { /* full or off */ } }
  function now() { return Math.floor(Date.now() / 1000); }
  function setProgress(ep, show, t, dur, done) {
    progress[ep.id] = [Math.floor(t), Math.floor(dur || ep.s || 0), done ? 1 : 0, now(), show.id];
    persist();
  }
  function prune(map) {
    // The 400 most recent, none older than 180 days: the entity stays small.
    var cutoff = now() - 180 * 86400;
    var ids = Object.keys(map).filter(function (id) { return map[id] && map[id][3] > cutoff; })
      .sort(function (a, b) { return map[b][3] - map[a][3]; }).slice(0, 400);
    var out = {};
    ids.forEach(function (id) { out[id] = map[id]; });
    return out;
  }

  // Pull the shared progress, keep the newer entry per episode, push back if ours had news.
  var syncing = false;
  function sync(cb) {
    if (!HA || syncing) { if (cb) cb(); return; }
    syncing = true;
    var auth = { Authorization: 'Bearer ' + GH.haToken, 'Content-Type': 'application/json' };
    request('GET', HA + '/api/states/' + ENTITY, null, auth, function (j) {
      var theirs = (j && j.attributes && j.attributes.progress) || {};
      var changed = false;
      var push = false;
      Object.keys(theirs).forEach(function (id) {
        var a = theirs[id];
        if (!a || a.length < 4) return;
        if (!progress[id] || progress[id][3] < a[3]) { progress[id] = a; changed = true; }
      });
      Object.keys(progress).forEach(function (id) {
        if (!theirs[id] || theirs[id][3] < progress[id][3]) push = true;
      });
      progress = prune(progress);
      if (changed) persist();
      if (!push) { syncing = false; if (cb) cb(changed); return; }
      request('POST', HA + '/api/states/' + ENTITY, {
        state: String(Object.keys(progress).length),
        attributes: { friendly_name: 'Podcast progress', icon: 'mdi:podcast', progress: progress }
      }, auth, function () { syncing = false; if (cb) cb(changed); });
    });
  }

  // Per show: { old: oldest first, unplayed: hide played }
  var VKEY = 'gh.podcasts.view.v1';
  var viewPrefs = {};
  try { viewPrefs = JSON.parse(localStorage.getItem(VKEY)) || {}; } catch (e) { viewPrefs = {}; }
  function savePrefs() { try { localStorage.setItem(VKEY, JSON.stringify(viewPrefs)); } catch (e) { /* off */ } }

  // ---------- data ----------
  var index = null;          // podcasts.json
  var showById = {};
  var episodes = {};         // id -> { ep, show }
  var showFiles = {};        // id -> shows/<id>.json once loaded

  function learn(show, eps) {
    eps.forEach(function (ep) { episodes[ep.id] = { ep: ep, show: show }; });
  }
  function loadIndex(cb) {
    get(BASE + 'podcasts.json?t=' + Math.floor(Date.now() / 600000), function (j) {
      if (j && j.shows) {
        index = j;
        showById = {};
        j.shows.forEach(function (s) { showById[s.id] = s; learn(s, s.eps); });
      }
      cb(!!index);
    });
  }
  function loadShow(show, cb) {
    if (showFiles[show.id]) { cb(showFiles[show.id]); return; }
    get(BASE + 'shows/' + show.id + '.json?t=' + Math.floor(Date.now() / 600000), function (j) {
      if (j && j.eps) { showFiles[show.id] = j; learn(show, j.eps); }
      cb(j);
    });
  }
  // Episodes in progress may be older than the 3 per show in podcasts.json.
  function loadContinuing(cb) {
    var want = {};
    Object.keys(progress).forEach(function (id) {
      var p = progress[id];
      if (!p[2] && !episodes[id] && p[4] && showById[p[4]] && !showFiles[p[4]]) want[p[4]] = true;
    });
    var ids = Object.keys(want).slice(0, 8);
    var left = ids.length;
    if (!left) { cb(); return; }
    ids.forEach(function (sid) { loadShow(showById[sid], function () { if (--left === 0) cb(); }); });
  }

  // ---------- state ----------
  var rows = [];             // [{ label, kind: 'eps'|'shows', items }]
  var view = null;           // browse | show | audio
  var rowFocus = 0;
  var colFocus = [];
  var current = null;        // show open in the show view
  var listFocus = 0;
  var media = null;
  var playing = null;        // { ep, show, from }
  var speed = 1;
  var SPEEDS = [0.8, 1, 1.2, 1.5, 1.75, 2];
  var saveTimer = null;
  var syncTimer = null;
  var COLS = 6;

  var CATS = ['News & Politics', 'Ideas & Culture', 'Science', 'History & Legends', 'Mysteries & UFOs', 'Tech',
    'Comedy & Entertainment', 'Giants', 'Learning'];
  function catRank(c) { var i = CATS.indexOf(c); return i < 0 ? CATS.length : i; }

  function buildRows() {
    rows = [];
    var cont = Object.keys(progress)
      .filter(function (id) { return !progress[id][2] && progress[id][0] > 0 && episodes[id]; })
      .sort(function (a, b) { return progress[b][3] - progress[a][3]; })
      .slice(0, 12)
      .map(function (id) { return episodes[id]; });
    if (cont.length) rows.push({ label: 'Continue', kind: 'eps', items: cont });
    var latest = [];
    index.shows.forEach(function (s) {
      // Not played, and not already in Continue.
      s.eps.forEach(function (ep) { var p = progress[ep.id]; if (!p || (!p[2] && !(p[0] > 0))) latest.push({ ep: ep, show: s }); });
    });
    latest.sort(function (a, b) { return b.ep.d - a.ep.d; });
    rows.push({ label: 'Latest', kind: 'eps', items: latest.slice(0, 30) });
    var shows = index.shows.slice().sort(function (a, b) { return b.newest - a.newest; });
    var cats = [];
    var byCat = {};
    shows.forEach(function (s) {
      var c = s.category || 'Other';
      if (!byCat[c]) { byCat[c] = []; cats.push(c); }
      byCat[c].push(s);
    });
    cats.sort(function (a, b) { return catRank(a) - catRank(b); });
    cats.forEach(function (c) {
      var list = byCat[c];
      for (var i = 0; i < list.length; i += COLS) {
        rows.push({ label: i === 0 ? c : '', kind: 'shows', items: list.slice(i, i + COLS) });
      }
    });
    while (colFocus.length < rows.length) colFocus.push(0);
    rowFocus = Math.min(rowFocus, rows.length - 1);
    for (var r = 0; r < rows.length; r++) colFocus[r] = Math.min(colFocus[r], rows[r].items.length - 1);
  }

  // ---------- browse ----------
  function card(art, name, sub, focused, near, pct) {
    var c = el('div', 'lib-card pod-card' + (focused ? ' focused' : ''));
    var a = el('div', 'lib-art pod-art');
    if (near && art) {
      var img = new Image();
      img.alt = '';
      img.onerror = function () { img.style.display = 'none'; };
      img.src = art;
      a.appendChild(img);
    }
    if (pct != null) {
      var bar = el('div', 'lib-progress');
      var fill = el('div', 'lib-fill');
      fill.style.width = pct + '%';
      bar.appendChild(fill);
      a.appendChild(bar);
    }
    c.appendChild(a);
    c.appendChild(el('div', 'lib-name pod-name', name));
    if (sub) c.appendChild(el('div', 'pod-sub', sub));
    return c;
  }

  function renderBrowse() {
    clear(root);
    var page = el('div', 'lib-browse');
    var title = el('div', 'lib-title pod-title');
    var logo = new Image();
    logo.alt = 'Podcasts';
    logo.src = 'img/podcasts.svg';
    title.appendChild(logo);
    page.appendChild(title);
    var track = el('div', 'lib-rows');
    var ROW_H = 480;            // .pod-row in tv.css; rows without a heading are 60 px shorter
    rows.forEach(function (r, ri) {
      var row = el('div', 'lib-row pod-row' + (ri === rowFocus ? ' focused' : '') + (r.label ? '' : ' pod-gridrow'));
      if (r.label) row.appendChild(el('div', 'lib-rowlabel', r.label));
      var strip = el('div', 'lib-strip');
      var c = colFocus[ri];
      var near = Math.abs(ri - rowFocus) <= 2;
      r.items.forEach(function (x, ci) {
        var focused = ri === rowFocus && ci === c;
        var show = near && Math.abs(ci - c) <= 7;
        if (r.kind === 'shows') {
          strip.appendChild(card(artUrl(x), x.title, day(x.newest), focused, show, null));
        } else {
          var p = progress[x.ep.id];
          var pct = p && p[1] ? Math.min(100, Math.round(100 * p[0] / p[1])) : null;
          strip.appendChild(card(artUrl(x.show), x.ep.t, x.show.title, focused, show, pct));
        }
      });
      if (r.kind === 'eps') strip.style.transform = 'translateX(' + (-Math.max(0, c - 2) * 290) + 'px)';
      row.appendChild(strip);
      track.appendChild(row);
    });
    var y = 0;
    for (var i = 0; i < Math.max(0, rowFocus - 1); i++) y += rows[i].label ? ROW_H : ROW_H - 60;
    track.style.transform = 'translateY(' + (-y) + 'px)';
    page.appendChild(track);
    var r0 = rows[rowFocus];
    var x0 = r0 && r0.items[colFocus[rowFocus]];
    if (x0) {
      var info = el('div', 'lib-info');
      if (r0.kind === 'shows') {
        info.appendChild(el('div', 'lib-info-title', x0.title));
        info.appendChild(el('div', 'lib-info-sub', [x0.author, x0.count + ' episodes'].filter(Boolean).join(' · ')));
      } else {
        info.appendChild(el('div', 'lib-info-title', x0.show.title));
        info.appendChild(el('div', 'lib-info-sub', epSub(x0.ep)));
      }
      page.appendChild(info);
    }
    root.appendChild(page);
  }

  // In the YouTube app (Roche Home's own launcher, as for YouTube shortcuts).
  function openYouTube(videoId, start) {
    var P = GH.platform;
    if (!P || !P.openYouTube) return;
    var mode = 'uri';
    try { mode = localStorage.getItem('gh.ytMode') || (GH.config && GH.config.youtubeMode) || 'uri'; } catch (e) { /* default */ }
    try { mode = JSON.parse(mode); } catch (e) { /* stored as plain text */ }
    P.openYouTube({ videoId: videoId, start: Math.floor(start || 0) }, mode);
  }

  function epSub(ep) {
    if (ep.yt) return day(ep.d) + ' · ▶ YouTube';
    var p = progress[ep.id];
    var bits = [day(ep.d)];
    if (p && p[2]) bits.push('Played');
    else if (p && p[0] > 0 && p[1]) bits.push(length(p[1] - p[0]) + ' left');
    else if (ep.s) bits.push(length(ep.s));
    return bits.filter(Boolean).join(' · ');
  }

  // ---------- show ----------
  function extrasLabel(e) { return e.tr ? (e.c ? 'chapters, transcript' : 'transcript') : 'chapters'; }
  function openShow(show, focusId) {
    current = show;
    view = 'show';
    listFocus = 2;
    renderShow();
    loadShow(show, function (j) {
      if (view !== 'show' || current !== show) return;
      if (!j) { renderShow('Couldn’t load the episodes. Is Home Assistant reachable?'); return; }
      if (focusId) showList(show).forEach(function (r, i) { if (r.ep && r.ep.id === focusId) listFocus = i; });
      else listFocus = 2;
      renderShow();
    });
  }

  function showPrefs(show) { return viewPrefs[show.id] || {}; }
  // The rows of the show view: two settings, then the episodes in the chosen order.
  function showList(show) {
    var data = showFiles[show.id];
    var pr = showPrefs(show);
    var eps = data ? data.eps.slice() : [];
    if (pr.unplayed) eps = eps.filter(function (e) { var p = progress[e.id]; return !(p && p[2]); });
    if (pr.old) eps.reverse();
    return [
      { setting: 'old', label: pr.old ? 'Oldest first' : 'Newest first', sub: 'OK to switch' },
      { setting: 'unplayed', label: pr.unplayed ? 'Unplayed only' : 'All episodes', sub: 'OK to switch' }
    ].concat(eps.map(function (e) { return { ep: e }; }));
  }

  function renderShow(error) {
    var show = current;
    var data = showFiles[show.id];
    var rowsList = showList(show);
    clear(root);
    var page = el('div', 'lib-detail');
    var art = el('div', 'lib-detail-art pod-detail-art');
    if (show.art) { var img = new Image(); img.alt = ''; img.src = artUrl(show); art.appendChild(img); }
    page.appendChild(art);
    var side = el('div', 'lib-detail-side');
    side.appendChild(el('div', 'lib-detail-title', show.title));
    side.appendChild(el('div', 'lib-detail-sub', show.author || ''));
    var ep = rowsList[listFocus] && rowsList[listFocus].ep;
    side.appendChild(el('div', 'lib-detail-desc pod-desc', ep ? ep.x : (data ? data.desc : '')));
    var list = el('div', 'lib-list');
    if (!data) list.appendChild(el('div', 'lib-more', error || 'Loading…'));
    var first = Math.max(0, Math.min(listFocus - 3, rowsList.length - 8));
    rowsList.slice(first, first + 8).forEach(function (r, i) {
      var e = r.ep;
      var p = e && progress[e.id];
      var row = el('div', 'lib-item' + (first + i === listFocus ? ' focused' : '') + (p && p[2] ? ' pod-played' : '') + (e ? '' : ' pod-setting'));
      row.appendChild(el('span', 'lib-item-label', e ? e.t : r.label));
      row.appendChild(el('span', 'lib-item-sub', e ? epSub(e) + (e.e ? ' · ' + extrasLabel(e) : '') : r.sub));
      list.appendChild(row);
    });
    if (rowsList.length > 8) list.appendChild(el('div', 'lib-more', Math.max(0, listFocus - 1) + ' of ' + (rowsList.length - 2)));
    side.appendChild(list);
    page.appendChild(side);
    root.appendChild(page);
  }

  // ---------- audio ----------
  var extras = null;         // { chapters, transcript } of the episode playing
  var menu = null;           // { focus } while the player menu is open
  var trOpen = false;        // transcript beside the player
  var trCursor = -1;         // line picked with Up/Down; -1 follows the audio
  var trCursorTimer = null;
  var sleepAt = 0;           // ms timestamp; 0 off
  var sleepEnd = false;      // pause at the end of this episode
  var SLEEPS = [0, 15, 30, 45, 60, -1];   // minutes; -1 end of episode

  function stopMedia() {
    clearInterval(saveTimer);
    clearInterval(syncTimer);
    if (media) {
      savePosition();
      try { media.pause(); media.removeAttribute('src'); media.load(); } catch (e) { /* gone */ }
      media = null;
      sync();
    }
    playing = null;
    extras = null;
    menu = null;
    trOpen = false;
  }

  function savePosition() {
    if (!media || !playing || !isFinite(media.duration) || !media.duration) return;
    var t = media.currentTime;
    if (t < 10) return;
    setProgress(playing.ep, playing.show, t, media.duration, media.duration - t < 45);
  }

  function finished(ep, show) {
    var queue = playing && playing.queue;
    setProgress(ep, show, ep.s || 0, ep.s || 0, true);
    media = null;
    stopMedia();
    sync();
    // Cast from the phone with more queued (a book's next part): on to it.
    if (queue && queue.length) { play(queue[0].ep, queue[0].show, 'cast', queue.slice(1)); return; }
    back();
  }

  function play(ep, show, from, queue) {
    stopMedia();
    view = 'audio';
    playing = { ep: ep, show: show, from: from, queue: queue || null };
    var p = progress[ep.id];
    var at = p && !p[2] ? p[0] : 0;
    clear(root);
    var page = el('div', 'lib-audio');
    media = document.createElement('audio');
    media.className = 'lib-media';
    media.preload = 'auto';
    media.src = ep.u;
    media.addEventListener('loadedmetadata', function once() {
      media.removeEventListener('loadedmetadata', once);
      try { if (at > 5) media.currentTime = Math.max(0, at - 5); media.playbackRate = speed; } catch (e) { /* from the start */ }
    });
    media.addEventListener('ended', function () {
      if (sleepEnd) { sleepEnd = false; }
      finished(ep, show);
    });
    media.addEventListener('error', function () { status('Couldn’t play this episode. Its host may be down; try again later.'); });
    media.addEventListener('waiting', function () { status('Loading…'); });
    media.addEventListener('playing', function () { status(''); try { media.playbackRate = speed; } catch (e) { /* 1x */ } });
    media.addEventListener('timeupdate', tick);
    page.appendChild(media);
    var art = el('div', 'lib-audio-art');
    if (show.art) { var img = new Image(); img.alt = ''; img.src = artUrl(show); art.appendChild(img); }
    page.appendChild(art);
    page.appendChild(el('div', 'pod-tr'));
    page.appendChild(el('div', 'pod-menu'));
    var hud = el('div', 'lib-hud on');
    hud.appendChild(el('div', 'lib-hud-title', ep.t));
    hud.appendChild(el('div', 'lib-hud-sub pod-hud-sub'));
    var bar = el('div', 'lib-bar');
    bar.appendChild(el('div', 'lib-bar-fill'));
    hud.appendChild(bar);
    hud.appendChild(el('div', 'lib-hud-time'));
    hud.appendChild(el('div', 'lib-hud-status'));
    hud.appendChild(el('div', 'lib-hud-keys'));
    page.appendChild(hud);
    root.appendChild(page);
    renderSub();
    renderKeys();
    var pr = media.play();
    if (pr && pr.catch) pr.catch(function () { /* autoplay refused: OK starts it */ });
    saveTimer = setInterval(savePosition, 10000);
    syncTimer = setInterval(function () { savePosition(); sync(); }, 120000);
    if (ep.e) {
      get(BASE + 'extras/' + ep.id + '.json', function (x) {
        if (!playing || playing.ep !== ep || !x) return;
        extras = x;
        renderSub();
        renderKeys();
      });
    }
  }

  function status(text) {
    var n = root.querySelector('.lib-hud-status');
    if (n) n.textContent = text;
  }
  function chapterAt(t) {
    var cs = extras && extras.chapters;
    if (!cs) return -1;
    var i = -1;
    for (var k = 0; k < cs.length; k++) if (cs[k].t <= t + 0.5) i = k;
    return i;
  }
  function renderSub() {
    var n = root.querySelector('.pod-hud-sub');
    if (!n || !playing) return;
    var bits = [playing.show.title, day(playing.ep.d)];
    var ci = media ? chapterAt(media.currentTime) : -1;
    if (ci >= 0 && extras.chapters[ci].n) bits.push(extras.chapters[ci].n);
    n.textContent = bits.join(' · ');
  }
  function renderKeys() {
    var n = root.querySelector('.lib-hud-keys');
    if (!n) return;
    n.textContent = menu ? '▲▼ choose · ◀▶ change · OK select · Back close'
      : trOpen ? 'OK pause · ◀ 15 s · ▶ 30 s · ▲▼ pick a line, OK jump there · Back hide transcript'
      : 'OK pause · ◀ 15 s · ▶ 30 s · ▼ menu' + (extras && extras.chapters ? ' · ⏪⏩ chapter' : '') + ' · Back stop';
  }
  var lastSub = -2;
  function tick() {
    if (!media) return;
    var d = media.duration;
    var fill = root.querySelector('.lib-bar-fill');
    if (fill && isFinite(d) && d) fill.style.width = (100 * media.currentTime / d) + '%';
    var t = root.querySelector('.lib-hud-time');
    var left = sleepAt ? Math.max(0, Math.ceil((sleepAt - Date.now()) / 60000)) : 0;
    if (t) t.textContent = hms(media.currentTime) + (isFinite(d) ? ' / ' + hms(d) : '') +
      (speed !== 1 ? '  ·  ' + speed + '×' : '') + (sleepAt ? '  ·  sleep in ' + left + ' min' : sleepEnd ? '  ·  sleep at the end' : '') +
      (media.paused ? '  ·  paused' : '');
    if (sleepAt && Date.now() >= sleepAt) {
      sleepAt = 0;
      media.pause();
      savePosition();
      sync();
    }
    var ci = chapterAt(media.currentTime);
    if (ci !== lastSub) { lastSub = ci; renderSub(); }
    if (trOpen) renderTranscript();
  }
  function seek(d) {
    if (!media || !isFinite(media.duration)) return;
    media.currentTime = Math.max(0, Math.min(media.duration - 1, media.currentTime + d));
    tick();
  }
  function seekTo(t) {
    if (!media) return;
    try { media.currentTime = t; } catch (e) { /* not seekable yet */ }
    if (media.paused) { var p = media.play(); if (p && p.catch) p.catch(function () {}); }
    tick();
  }
  function togglePause() {
    if (!media) return;
    if (media.paused) { var p = media.play(); if (p && p.catch) p.catch(function () {}); } else { media.pause(); savePosition(); sync(); }
    tick();
  }
  function chapterJump(dir) {
    var cs = extras && extras.chapters;
    if (!cs || !media) return;
    var i = chapterAt(media.currentTime);
    // Back within the first 5 s of a chapter goes to the one before.
    if (dir < 0 && i >= 0 && media.currentTime - cs[i].t > 5) seekTo(cs[i].t);
    else if (cs[i + dir]) seekTo(cs[i + dir].t);
  }

  // ---------- player menu ----------
  function menuItems() {
    var items = [
      { kind: 'speed', label: 'Speed', value: speed + '×' },
      { kind: 'sleep', label: 'Sleep timer', value: sleepAt ? Math.max(1, Math.ceil((sleepAt - Date.now()) / 60000)) + ' min left' : sleepEnd ? 'End of episode' : 'Off' }
    ];
    if (playing && playing.ep.v) items.push({ kind: 'watch', label: 'Watch on YouTube', value: 'from here' });
    if (extras && extras.transcript) items.push({ kind: 'transcript', label: trOpen ? 'Hide transcript' : 'Show transcript', value: extras.transcript.timed ? 'follows along' : '' });
    if (extras && extras.chapters) {
      var cur = media ? chapterAt(media.currentTime) : -1;
      extras.chapters.forEach(function (c, i) { items.push({ kind: 'chapter', label: c.n || 'Chapter ' + (i + 1), value: hms(c.t), t: c.t, current: i === cur }); });
    }
    return items;
  }
  function renderMenu() {
    var box = root.querySelector('.pod-menu');
    if (!box) return;
    clear(box);
    box.className = 'pod-menu' + (menu ? ' on' : '');
    if (!menu) { renderKeys(); return; }
    var items = menuItems();
    menu.focus = Math.min(menu.focus, items.length - 1);
    var first = Math.max(0, Math.min(menu.focus - 4, items.length - 9));
    items.slice(first, first + 9).forEach(function (it, i) {
      var row = el('div', 'lib-item' + (first + i === menu.focus ? ' focused' : '') + (it.current ? ' pod-current' : ''));
      row.appendChild(el('span', 'lib-item-label', it.label));
      row.appendChild(el('span', 'lib-item-sub', it.kind === 'speed' || it.kind === 'sleep' ? '◀ ' + it.value + ' ▶' : it.value));
      box.appendChild(row);
    });
    renderKeys();
  }
  function changeSpeed(dir) {
    var i = SPEEDS.indexOf(speed);
    speed = SPEEDS[Math.max(0, Math.min(SPEEDS.length - 1, (i < 0 ? 1 : i) + dir))];
    try { if (media) media.playbackRate = speed; } catch (e) { /* unsupported */ }
  }
  function changeSleep(dir) {
    var curMin = sleepAt ? Math.ceil((sleepAt - Date.now()) / 60000) : sleepEnd ? -1 : 0;
    var i = 0;
    SLEEPS.forEach(function (m, k) { if (m === curMin) i = k; });
    if (sleepAt) { i = 0; SLEEPS.forEach(function (m, k) { if (m > 0 && m <= curMin) i = k; }); }
    var next = SLEEPS[(i + dir + SLEEPS.length) % SLEEPS.length];
    sleepAt = next > 0 ? Date.now() + next * 60000 : 0;
    sleepEnd = next === -1;
  }
  function menuKey(k) {
    var items = menuItems();
    var it = items[menu.focus];
    if (k === K.UP) menu.focus = Math.max(0, menu.focus - 1);
    else if (k === K.DOWN) menu.focus = Math.min(items.length - 1, menu.focus + 1);
    else if ((k === K.LEFT || k === K.RIGHT) && it && it.kind === 'speed') changeSpeed(k === K.LEFT ? -1 : 1);
    else if ((k === K.LEFT || k === K.RIGHT) && it && it.kind === 'sleep') changeSleep(k === K.LEFT ? -1 : 1);
    else if (k === K.ENTER && it) {
      if (it.kind === 'speed') changeSpeed(1);
      else if (it.kind === 'sleep') changeSleep(1);
      else if (it.kind === 'watch') {
        var at = media ? media.currentTime : 0;
        if (media && !media.paused) { media.pause(); savePosition(); sync(); }
        menu = null;
        openYouTube(playing.ep.v, at);
      }
      else if (it.kind === 'transcript') { trOpen = !trOpen; trCursor = -1; menu = null; renderTranscript(); }
      else if (it.kind === 'chapter') { seekTo(it.t); menu = null; }
    }
    renderMenu();
    tick();
  }

  // ---------- transcript ----------
  function lineAt(t) {
    var ls = extras.transcript.lines;
    var i = 0;
    for (var k = 0; k < ls.length; k++) if (ls[k].t <= t + 0.5) i = k;
    return i;
  }
  var trShown = -2;
  function renderTranscript() {
    var box = root.querySelector('.pod-tr');
    var page = root.querySelector('.lib-audio');
    if (!box || !page) return;
    page.className = 'lib-audio' + (trOpen ? ' pod-with-tr' : '');
    if (!trOpen || !extras || !extras.transcript) { clear(box); trShown = -2; return; }
    var tr = extras.transcript;
    var playingLine = tr.timed && media ? lineAt(media.currentTime) : -1;
    var focus = trCursor >= 0 ? trCursor : Math.max(0, playingLine);
    var key = focus * 100000 + playingLine;
    if (key === trShown) return;
    trShown = key;
    clear(box);
    var first = Math.max(0, focus - 2);
    var speaker = '';
    for (var k = first - 1; k >= 0 && !speaker; k--) if (tr.lines[k].s) speaker = tr.lines[k].s;
    tr.lines.slice(first, first + 9).forEach(function (ln, i) {
      var n = first + i;
      var row = el('div', 'pod-line' + (n === playingLine ? ' now' : '') + (n === trCursor ? ' picked' : ''));
      if (ln.s && ln.s !== speaker) { row.appendChild(el('div', 'pod-speaker', ln.s)); speaker = ln.s; }
      row.appendChild(el('div', '', ln.x));
      box.appendChild(row);
    });
  }
  function transcriptKey(k) {
    var ls = extras.transcript.lines;
    var playingLine = extras.transcript.timed && media ? lineAt(media.currentTime) : 0;
    var from = trCursor >= 0 ? trCursor : playingLine;
    if (k === K.UP) trCursor = Math.max(0, from - 1);
    else if (k === K.DOWN) trCursor = Math.min(ls.length - 1, from + 1);
    else if (k === K.ENTER && trCursor >= 0 && extras.transcript.timed) { seekTo(ls[trCursor].t); trCursor = -1; }
    else if (k === K.ENTER) togglePause();
    // Back to following the audio 8 s after the last move.
    clearTimeout(trCursorTimer);
    if (trCursor >= 0 && extras.transcript.timed) trCursorTimer = setTimeout(function () { trCursor = -1; renderTranscript(); }, 8000);
    renderTranscript();
  }

  // ---------- navigation ----------
  function showBrowse() {
    view = 'browse';
    buildRows();
    renderBrowse();
  }
  function open() {
    root.classList.add('open');
    view = 'browse';
    clear(root);
    root.appendChild(el('div', 'lib-loading', 'Loading podcasts…'));
    var pending = 2;
    function done() {
      if (--pending > 0 || view !== 'browse') return;
      if (!index) { clear(root); root.appendChild(el('div', 'lib-loading', 'Couldn’t load the podcasts from Home Assistant.')); return; }
      loadContinuing(function () { if (view === 'browse') showBrowse(); });
    }
    loadIndex(done);
    sync(done);
  }
  function close() {
    stopMedia();
    view = null;
    root.classList.remove('open');
    clear(root);
  }
  function back() {
    if (view === 'audio' && menu) { menu = null; renderMenu(); return; }
    if (view === 'audio' && trOpen) { trOpen = false; trCursor = -1; renderTranscript(); renderKeys(); return; }
    if (view === 'audio') {
      var from = playing && playing.from;
      stopMedia();
      if (!index) { open(); return; }   // cast before the lists had loaded
      if (from === 'show' && current) { view = 'show'; renderShow(); } else showBrowse();
      return;
    }
    if (view === 'show') { showBrowse(); return; }
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
      if (k === K.UP || k === K.DOWN) {
        var was = rows[rowFocus];
        var to = Math.max(0, Math.min(rows.length - 1, rowFocus + (k === K.UP ? -1 : 1)));
        // Moving within the shows grid keeps the column.
        if (to !== rowFocus && was.kind === 'shows' && rows[to].kind === 'shows') colFocus[to] = Math.min(colFocus[rowFocus], rows[to].items.length - 1);
        rowFocus = to;
      } else if (k === K.LEFT) colFocus[rowFocus] = Math.max(0, colFocus[rowFocus] - 1);
      else if (k === K.RIGHT) colFocus[rowFocus] = Math.min(r.items.length - 1, colFocus[rowFocus] + 1);
      else if ((k === K.ENTER || k === K.PLAY || k === K.PLAYPAUSE) && !e.repeat) {
        var x = r.items[colFocus[rowFocus]];
        if (r.kind === 'shows') openShow(x);
        else if (x.ep.yt) openYouTube(x.ep.v, 0);
        else play(x.ep, x.show, 'browse');
        return;
      }
      renderBrowse();
      return;
    }
    if (view === 'show') {
      var list = showList(current);
      if (k === K.UP) listFocus = Math.max(0, listFocus - 1);
      else if (k === K.DOWN) listFocus = Math.min(list.length - 1, listFocus + 1);
      else if ((k === K.ENTER || k === K.PLAY || k === K.PLAYPAUSE) && !e.repeat && list[listFocus]) {
        var r = list[listFocus];
        if (r.ep && r.ep.yt) { openYouTube(r.ep.v, 0); return; }
        if (r.ep) { play(r.ep, current, 'show'); return; }
        var pr = viewPrefs[current.id] = viewPrefs[current.id] || {};
        pr[r.setting] = !pr[r.setting];
        savePrefs();
      }
      renderShow();
      return;
    }
    if (view === 'audio') {
      if (k === K.PLAYPAUSE || k === K.PLAY || k === K.PAUSE) { if (!e.repeat) togglePause(); return; }
      if (k === K.REW || k === K.FF) { if (!e.repeat) { if (extras && extras.chapters) chapterJump(k === K.REW ? -1 : 1); else seek(k === K.REW ? -15 : 30); } return; }
      if (menu) { menuKey(k); return; }
      if (k === K.LEFT) { seek(-15); return; }
      if (k === K.RIGHT) { seek(30); return; }
      if (trOpen && (k === K.UP || k === K.DOWN || k === K.ENTER)) { transcriptKey(k); return; }
      if (k === K.ENTER) { if (!e.repeat) togglePause(); return; }
      if (k === K.DOWN || k === K.UP) { menu = { focus: 0 }; renderMenu(); }
    }
  }
  window.addEventListener('keydown', onKey, true);
  window.addEventListener('keyup', onKey, true);

  // Leaving Roche Home (Home button, another app): pause and keep the place.
  document.addEventListener('visibilitychange', function () {
    if (!document.hidden || !view) return;
    if (media) { savePosition(); media.pause(); sync(); }
  });

  // ---------- cast from the phone ----------
  var CKEY = 'gh.podcasts.cast.v1';
  var lastCast = 0;
  try { lastCast = Number(localStorage.getItem(CKEY)) || 0; } catch (e) { lastCast = 0; }
  function castItem(c) {
    if (!c || !c.ep || !c.ep.u || !c.ep.id) return null;
    var show = (c.show && showById[c.show.id]) || c.show || { id: c.ep.showId || '', title: '' };
    return { ep: c.ep, show: show };
  }
  function checkCast() {
    if (!HA || document.hidden) return;
    request('GET', HA + '/api/states/' + CAST, null, { Authorization: 'Bearer ' + GH.haToken }, function (j) {
      var c = j && j.attributes;
      if (!c || !c.at || c.at <= lastCast) return;
      lastCast = c.at;
      try { localStorage.setItem(CKEY, String(lastCast)); } catch (e) { /* off */ }
      if (now() - c.at > 90) return;   // an old cast, from before Roche Home was opened
      var first = castItem(c);
      if (!first) return;
      var queue = (c.queue || []).map(castItem).filter(Boolean);
      // Start where the phone was (play() backs up 5 s from the saved place).
      progress[first.ep.id] = [Math.floor(c.pos || 0) + 5, Math.floor(first.ep.s || 0), 0, now(), first.show.id];
      persist();
      if (c.speed && SPEEDS.indexOf(c.speed) >= 0) speed = c.speed;
      if (!view) open();
      play(first.ep, first.show, 'cast', queue);
    });
  }
  if (HA) setInterval(checkCast, 4000);

  GH.podcasts = { open: open, isOpen: function () { return !!view; } };

  // Preview (desktop browser): ?podcasts=1 opens it.
  if (GH.config && GH.config.preview && GH.query && GH.query.podcasts) setTimeout(open, 300);
}());
