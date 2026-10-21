/*
 * Glass Home - BabySteps card from Home Assistant, under the brand.
 *
 * Reads one sensor (sensor.babysteps_status by default) from Home Assistant's
 * REST API every minute, with a long-lived access token kept in
 * HA_TOKEN in .env (git-ignored; see .env.example). Shows asleep/awake and for how
 * long, the last feed and diaper change (amber once they are 3 hours old) and
 * "Crying now" from the nursery sensor. The last good answer is kept in
 * localStorage so the card appears at once after a restart. Nothing from the
 * network is inserted as HTML.
 *
 * Also fills "Inside" in the weather card from a temperature sensor
 * (homeAssistant.insideTemperature), and shares the connection as GH.ha for
 * js/alerts.js and js/babylog.js.
 */
(function () {
  'use strict';
  var GH = window.GH || {};
  var cfg = GH.config && GH.config.homeAssistant;
  var root = document.getElementById('ha');
  if (!cfg || !root || cfg.enabled === false || !cfg.url || !GH.haToken) return;

  var KEY = 'gh.ha.v1';
  var HIDE_AFTER = 2 * 3600 * 1000;      // older than this: hide rather than mislead
  var STALE = 15 * 60 * 1000;            // older than this: say when it was updated
  var DUE = 3 * 3600 * 1000;             // feed and diaper turn amber after this
  var REFRESH = cfg.refreshMs || 60 * 1000;
  var last = null;
  var inside = null;                    // { at, value, unit }
  var timers = [];

  var timeFmt = null;
  try { timeFmt = new Intl.DateTimeFormat(GH.config.locale || undefined, { hour: 'numeric', minute: '2-digit' }); } catch (e) { /* fallback below */ }

  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }
  function parse(v) {
    var t = v ? Date.parse(v) : NaN;
    return isNaN(t) ? null : t;
  }
  function clock(t) {
    var d = new Date(t);
    if (timeFmt) return timeFmt.format(d);
    var h = d.getHours();
    var m = d.getMinutes();
    return ((h + 11) % 12 + 1) + ':' + (m < 10 ? '0' : '') + m + (h < 12 ? ' AM' : ' PM');
  }
  function span(ms) {
    var min = Math.max(0, Math.floor(ms / 60000));
    var h = Math.floor(min / 60);
    return h ? h + 'h ' + (min % 60) + 'm' : min + 'm';
  }
  function cap(s) { return s ? s.charAt(0).toUpperCase() + s.slice(1) : ''; }

  function readCache() {
    try { var v = JSON.parse(localStorage.getItem(KEY)); return v && v.at && v.attrs ? v : null; } catch (e) { return null; }
  }
  function writeCache(v) {
    try { localStorage.setItem(KEY, JSON.stringify(v)); } catch (e) { /* ignore */ }
  }

  // One of the two side-by-side stats: label, how long ago, then when and what.
  function stat(label, at, detail, now) {
    var due = now - at >= DUE;
    var c = el('div', 'ha-stat' + (due ? ' due' : ''));
    c.appendChild(el('div', 'ha-label', label));
    c.appendChild(el('div', 'ha-value', span(now - at) + ' ago'));
    c.appendChild(el('div', 'ha-detail', clock(at) + (detail ? ' · ' + detail : '')));
    return c;
  }

  function render() {
    var now = Date.now();
    var a = last && last.attrs;
    while (root.firstChild) root.removeChild(root.firstChild);
    if (!a || now - last.at > HIDE_AFTER || last.state !== 'ok') { root.classList.remove('on'); return; }

    var head = el('div', 'ha-head');
    head.appendChild(el('span', 'ha-name', a.child || 'Baby'));
    if (a.crying_now) head.appendChild(el('span', 'ha-alert', 'Crying now'));
    root.appendChild(head);

    // Headline: asleep or awake, and for how long.
    var sleep = a.lastSleep;
    var sAt = sleep && parse(sleep.at);
    var sEnd = sleep && parse(sleep.endedAt);
    if (sAt && !sEnd) {
      root.appendChild(el('div', 'ha-status asleep', 'Asleep ' + span(now - sAt)));
      root.appendChild(el('div', 'ha-since', 'since ' + clock(sAt)));
    } else if (sEnd) {
      root.appendChild(el('div', 'ha-status', 'Awake ' + span(now - sEnd)));
      root.appendChild(el('div', 'ha-since', 'since ' + clock(sEnd)));
    }

    var stats = el('div', 'ha-stats');
    var feed = a.lastFeed;
    var fAt = feed && parse(feed.at);
    if (fAt) {
      var what = cap(feed.method) || 'Feed';
      if (feed.amountOz) what += ' ' + Math.round(feed.amountOz * 10) / 10 + ' oz';
      else if (feed.durationMinutes) what += ' ' + feed.durationMinutes + ' min';
      stats.appendChild(stat('Last feed', fAt, what, now));
    }
    var diaper = a.lastDiaper;
    var dAt = diaper && parse(diaper.at);
    if (dAt) stats.appendChild(stat('Last diaper', dAt, cap(diaper.kind), now));
    if (stats.firstChild) root.appendChild(stats);

    if (now - last.at > STALE) root.appendChild(el('div', 'ha-foot', 'Updated ' + clock(last.at)));
    root.classList.add('on');
  }

  // GETs one entity's state; calls back with the parsed JSON or null.
  function getState(entity, cb) {
    var x = new XMLHttpRequest();
    x.timeout = 15000;
    x.open('GET', cfg.url.replace(/\/+$/, '') + '/api/states/' + encodeURIComponent(entity));
    x.setRequestHeader('Authorization', 'Bearer ' + GH.haToken);
    x.onload = function () {
      var j = null;
      try { j = x.status === 200 ? JSON.parse(x.responseText) : null; } catch (e) { /* null */ }
      cb(j);
    };
    x.onerror = x.ontimeout = function () { cb(null); };
    x.send();
  }

  // Calls a service and passes its response (?return_response) to cb, or null.
  function callService(domain, service, data, cb) {
    var x = new XMLHttpRequest();
    x.timeout = 45000;
    x.open('POST', cfg.url.replace(/\/+$/, '') + '/api/services/' + domain + '/' + service + '?return_response');
    x.setRequestHeader('Authorization', 'Bearer ' + GH.haToken);
    x.setRequestHeader('Content-Type', 'application/json');
    x.onload = function () {
      var j = null;
      try { j = x.status === 200 ? JSON.parse(x.responseText) : null; } catch (e) { /* null */ }
      cb(j);
    };
    x.onerror = x.ontimeout = function () { cb(null); };
    x.send(JSON.stringify(data || {}));
  }

  function renderInside() {
    var box = document.getElementById('wx-inside');
    if (!box) return;
    var ok = inside && Date.now() - inside.at <= HIDE_AFTER;
    document.getElementById('wx-inside-temp').textContent = ok ? Math.round(inside.value) + '°' : '';
    box.classList.toggle('on', !!ok);
  }

  var loading = false;
  function load() {
    if (loading) return;
    loading = true;
    getState(cfg.entity || 'sensor.babysteps_status', function (j) {
      if (j && j.attributes) {
        last = { at: Date.now(), state: j.state, attrs: j.attributes };
        writeCache(last);
      }
      loading = false;
      render();
    });
    if (cfg.insideTemperature) {
      getState(cfg.insideTemperature, function (j) {
        var v = j ? parseFloat(j.state) : NaN;
        if (!isNaN(v)) inside = { at: Date.now(), value: v };
        renderInside();
      });
    }
  }

  function start() {
    stop();
    timers.push(setInterval(load, REFRESH));
    timers.push(setInterval(render, 30 * 1000));   // keep "ago" current between loads
  }
  function stop() {
    while (timers.length) clearInterval(timers.pop());
  }

  document.addEventListener('visibilitychange', function () {
    if (document.hidden) { stop(); return; }
    render();
    load();
    start();
  });

  GH.ha = {
    getState: getState,
    callService: callService,
    // Calls a service that returns nothing (light.toggle, …); cb(ok).
    call: function (domain, service, data, cb) {
      var x = new XMLHttpRequest();
      x.timeout = 15000;
      x.open('POST', cfg.url.replace(/\/+$/, '') + '/api/services/' + domain + '/' + service);
      x.setRequestHeader('Authorization', 'Bearer ' + GH.haToken);
      x.setRequestHeader('Content-Type', 'application/json');
      x.onload = function () { if (cb) cb(x.status === 200); };
      x.onerror = x.ontimeout = function () { if (cb) cb(false); };
      x.send(JSON.stringify(data || {}));
    },
    // Renders a Jinja template in HA (rooms and devices); cb(text) or cb(null).
    template: function (tpl, cb) {
      var x = new XMLHttpRequest();
      x.timeout = 15000;
      x.open('POST', cfg.url.replace(/\/+$/, '') + '/api/template');
      x.setRequestHeader('Authorization', 'Bearer ' + GH.haToken);
      x.setRequestHeader('Content-Type', 'application/json');
      x.onload = function () { cb(x.status === 200 ? x.responseText : null); };
      x.onerror = x.ontimeout = function () { cb(null); };
      x.send(JSON.stringify({ template: tpl }));
    },
    // Speech for `text` from HA's text-to-speech (homeAssistant.ttsEngine):
    // cb(url of an MP3 the TV can play without signing in) or cb(null).
    ttsUrl: function (text, cb) {
      var x = new XMLHttpRequest();
      x.timeout = 30000;
      x.open('POST', cfg.url.replace(/\/+$/, '') + '/api/tts_get_url');
      x.setRequestHeader('Authorization', 'Bearer ' + GH.haToken);
      x.setRequestHeader('Content-Type', 'application/json');
      x.onload = function () {
        var j = null;
        try { j = x.status === 200 ? JSON.parse(x.responseText) : null; } catch (e) { /* null */ }
        cb(j && j.url ? j.url : null);
      };
      x.onerror = x.ontimeout = function () { cb(null); };
      x.send(JSON.stringify({ engine_id: cfg.ttsEngine || 'tts.google_translate_en_com', message: text }));
    },
    // The BabySteps sensor's attributes, or null.
    baby: function () { return last && last.attrs; },
    // Asks HA to re-read BabySteps now (its sensor otherwise updates every
    // 5 minutes), then reloads the card.
    refreshBaby: function () {
      var x = new XMLHttpRequest();
      x.open('POST', cfg.url.replace(/\/+$/, '') + '/api/services/homeassistant/update_entity');
      x.setRequestHeader('Authorization', 'Bearer ' + GH.haToken);
      x.setRequestHeader('Content-Type', 'application/json');
      x.onloadend = function () { setTimeout(load, 1500); };
      x.send(JSON.stringify({ entity_id: cfg.entity || 'sensor.babysteps_status' }));
    }
  };

  last = readCache();
  render();
  load();
  start();
}());
