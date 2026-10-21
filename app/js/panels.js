/*
 * Roche Home - the Weather and Monitor tiles and their full-screen pages.
 *
 * Weather (second in the dock): the tile shows the sky and temperature now.
 * OK opens Home: conditions, details, the next 12 hours and 7 days. Up shows
 * Ventura, Down shows Malibu (sensor.daytrip from ../rpi: drive time, air and
 * water, wind, surf and swell now and every 3 hours, tides). Back closes.
 *
 * Monitor (last in the dock): the tile shows a dot each for the TV, the
 * Raspberry Pi and the internet; OK opens the details. TV figures come from
 * tizen.systeminfo; Pi and internet from sensor.raspberry_pi, sensor.internet
 * and the Pi-hole sensors.
 *
 * The forecast is read from Open-Meteo directly (hourly is not in
 * js/weather.js); everything else from Home Assistant via GH.ha. Like
 * js/babylog.js, an open page takes every key first. Nothing from the network
 * is inserted as HTML (the icons are drawn by js/weather.js).
 */
(function () {
  'use strict';
  var GH = window.GH || {};
  var cfg = GH.config || {};
  var wx = cfg.weather || {};
  var root = document.getElementById('sheet');
  if (!root) return;

  var KEY = { UP: 38, DOWN: 40, BACK: 10009, ESC: 27, BACKSPACE: 8 };
  var tz = typeof window.tizen !== 'undefined' ? window.tizen : null;
  var E = {
    insideTemp: (cfg.homeAssistant || {}).insideTemperature, insideHumidity: (cfg.homeAssistant || {}).insideHumidity,
    air: 'sensor.local_air_quality', daytrip: 'sensor.daytrip', pi: 'sensor.raspberry_pi',
    internet: 'sensor.internet', uptime: 'sensor.internet_uptime', piholeStatus: 'binary_sensor.pi_hole_status',
    piholeBlocked: 'sensor.pi_hole_ads_blocked', piholePercent: 'sensor.pi_hole_ads_percentage_blocked'
  };
  var states = {};
  var forecast = null;           // { at, data } from Open-Meteo
  var tvInfo = {};
  var view = null;               // 'weather' | 'monitor'
  var page = 1;                  // weather: 0 Ventura, 1 Home, 2 Malibu
  var faces = {};                // kind -> tile face element
  var started = Date.now();

  // ---------- small helpers ----------
  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }
  function clear(n) { while (n.firstChild) n.removeChild(n.firstChild); }
  function num(v) { return typeof v === 'number' && isFinite(v); }
  function deg(v) { return num(v) ? Math.round(v) + '°' : '–'; }
  function round1(v) { return num(v) ? Math.round(v * 10) / 10 : null; }
  var timeFmt = null;
  var dayFmt = null;
  var hourFmt = null;
  try {
    timeFmt = new Intl.DateTimeFormat(cfg.locale || undefined, { hour: 'numeric', minute: '2-digit' });
    dayFmt = new Intl.DateTimeFormat(cfg.locale || undefined, { weekday: 'short' });
    hourFmt = new Intl.DateTimeFormat(cfg.locale || undefined, { hour: 'numeric' });
  } catch (e) { /* toLocale* below */ }
  function fmt(f, t) { try { return f.format(new Date(t)); } catch (e) { return new Date(t).toLocaleTimeString(); } }
  // "2026-10-21T07:05" (no offset) as local time; Tizen 4 would read it as UTC.
  function local(s) {
    var m = /^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2}))?/.exec(String(s || ''));
    return m ? new Date(+m[1], +m[2] - 1, +m[3], +(m[4] || 0), +(m[5] || 0)).getTime() : null;
  }
  var POINTS = ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW'];
  function compass(d) { return num(d) ? POINTS[Math.round(((d % 360) + 360) % 360 / 45) % 8] : ''; }
  function icon(code, night, cls) {
    var n = el('div', cls || 'sh-icon');
    if (GH.wxIcon && GH.wxInfo) n.innerHTML = GH.wxIcon(GH.wxInfo(code)[2], night);   // drawn locally, no network text
    return n;
  }
  function label(code, night) { return GH.wxInfo ? GH.wxInfo(code)[night ? 1 : 0] : ''; }
  function attrs(key) { var s = states[key]; return s && s.attributes; }
  function value(key) { var s = states[key]; var v = s ? parseFloat(s.state) : NaN; return isNaN(v) ? null : v; }

  // ---------- data ----------
  function loadStates(keys, done) {
    if (!GH.ha) { done(); return; }
    var n = keys.length;
    keys.forEach(function (k) {
      if (!E[k]) { if (--n === 0) done(); return; }   // not set in .env
      GH.ha.getState(E[k], function (j) {
        if (j) states[k] = j;
        if (--n === 0) done();
      });
    });
  }

  function loadForecast(done) {
    if (forecast && Date.now() - forecast.at < 15 * 60 * 1000) { done(); return; }
    if (!wx.latitude && !wx.longitude) { done(); return; }
    var x = new XMLHttpRequest();
    x.timeout = 15000;
    x.open('GET', 'https://api.open-meteo.com/v1/forecast?latitude=' + encodeURIComponent(wx.latitude) +
      '&longitude=' + encodeURIComponent(wx.longitude) +
      '&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,is_day,wind_speed_10m,wind_direction_10m,wind_gusts_10m,cloud_cover,pressure_msl' +
      '&hourly=temperature_2m,weather_code,precipitation_probability,is_day' +
      '&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset,uv_index_max' +
      '&temperature_unit=' + encodeURIComponent(wx.temperatureUnit || 'celsius') + '&wind_speed_unit=mph' +
      '&timezone=' + encodeURIComponent(wx.timezone || 'auto') + '&forecast_days=8');
    x.onload = function () {
      try { var j = JSON.parse(x.responseText); if (j && j.current && j.daily) forecast = { at: Date.now(), data: j }; } catch (e) { /* keep old */ }
      done();
    };
    x.onerror = x.ontimeout = function () { done(); };
    x.send();
  }

  function loadTV(done) {
    tvInfo.memory = null;
    if (!tz || !tz.systeminfo) { done(); return; }
    try { tvInfo.memory = { total: tz.systeminfo.getTotalMemory(), free: tz.systeminfo.getAvailableMemory() }; } catch (e) { /* skip */ }
    var props = ['STORAGE', 'CPU', 'ETHERNET_NETWORK', 'BUILD', 'NETWORK'];
    var n = props.length;
    props.forEach(function (p) {
      function next() { if (--n === 0) done(); }
      try { tz.systeminfo.getPropertyValue(p, function (v) { tvInfo[p] = v; next(); }, next); } catch (e) { next(); }
    });
  }

  // ---------- weather: home ----------
  function detail(grid, name, val, sub) {
    var c = el('div', 'sh-detail');
    c.appendChild(el('div', 'sh-dname', name));
    c.appendChild(el('div', 'sh-dval', val));
    if (sub) c.appendChild(el('div', 'sh-dsub', sub));
    grid.appendChild(c);
  }

  function aqiLabel(v) {
    return v <= 50 ? 'Good' : v <= 100 ? 'Moderate' : v <= 150 ? 'Unhealthy for some' : v <= 200 ? 'Unhealthy' : 'Very unhealthy';
  }

  function renderHome(card) {
    var j = forecast && forecast.data;
    var head = el('div', 'sh-head');
    head.appendChild(el('div', 'sh-title', 'Home' + (wx.place ? ' · ' + wx.place : '')));
    head.appendChild(el('div', 'sh-nav', '▲ Ventura   ▼ Malibu'));
    card.appendChild(head);
    if (!j) { card.appendChild(el('div', 'sh-empty', 'The forecast isn’t available right now.')); return; }
    var c = j.current;
    var d = j.daily;
    var night = c.is_day === 0;

    var top = el('div', 'sh-top');
    top.appendChild(icon(c.weather_code, night, 'sh-icon big'));
    var now = el('div', 'sh-now');
    now.appendChild(el('div', 'sh-temp', deg(c.temperature_2m)));
    now.appendChild(el('div', 'sh-cond', label(c.weather_code, night)));
    now.appendChild(el('div', 'sh-sub', 'Feels like ' + deg(c.apparent_temperature) + '  ·  H ' + deg(d.temperature_2m_max[0]) + '  L ' + deg(d.temperature_2m_min[0])));
    top.appendChild(now);

    var grid = el('div', 'sh-grid');
    var it = value('insideTemp');
    var ih = value('insideHumidity');
    detail(grid, 'Inside', it != null ? deg(it) : '–', ih != null ? Math.round(ih) + '% humidity' : '');
    detail(grid, 'Humidity', num(c.relative_humidity_2m) ? Math.round(c.relative_humidity_2m) + '%' : '–', num(c.cloud_cover) ? Math.round(c.cloud_cover) + '% cloud cover' : '');
    detail(grid, 'Wind', num(c.wind_speed_10m) ? Math.round(c.wind_speed_10m) + ' mph ' + compass(c.wind_direction_10m) : '–',
      num(c.wind_gusts_10m) ? 'gusts ' + Math.round(c.wind_gusts_10m) + ' mph' : '');
    var aqi = value('air');
    var air = attrs('air') || {};
    detail(grid, 'Air quality', aqi != null ? String(Math.round(aqi)) : '–', aqi != null ? aqiLabel(aqi) + (num(air.pm2_5) ? ' · PM2.5 ' + round1(air.pm2_5) : '') : '');
    detail(grid, 'UV index', num(d.uv_index_max[0]) ? String(Math.round(d.uv_index_max[0])) : '–', 'today’s high');
    detail(grid, 'Rain', num(d.precipitation_probability_max[0]) ? Math.round(d.precipitation_probability_max[0]) + '%' : '–', 'chance today');
    detail(grid, 'Sunrise', d.sunrise ? fmt(timeFmt, local(d.sunrise[0])) : '–', d.sunset ? 'sunset ' + fmt(timeFmt, local(d.sunset[0])) : '');
    detail(grid, 'Pressure', num(c.pressure_msl) ? (c.pressure_msl / 33.8639).toFixed(2) + ' in' : '–', '');
    top.appendChild(grid);
    card.appendChild(top);

    // Next 12 hours from the current local hour.
    var h = j.hourly;
    var hourRow = el('div', 'sh-row');
    var nowT = Date.now();
    var shown = 0;
    for (var i = 0; i < h.time.length && shown < 12; i++) {
      var t = local(h.time[i]);
      if (t + 3600 * 1000 <= nowT) continue;
      var cell = el('div', 'sh-cell');
      cell.appendChild(el('div', 'sh-ct', shown === 0 ? 'Now' : fmt(hourFmt, t)));
      cell.appendChild(icon(h.weather_code[i], h.is_day[i] === 0));
      cell.appendChild(el('div', 'sh-cv', deg(h.temperature_2m[i])));
      cell.appendChild(el('div', 'sh-cr', num(h.precipitation_probability[i]) && h.precipitation_probability[i] >= 20 ? h.precipitation_probability[i] + '%' : ''));
      hourRow.appendChild(cell);
      shown++;
    }
    card.appendChild(el('div', 'sh-label', 'Next 12 hours'));
    card.appendChild(hourRow);

    var dayRow = el('div', 'sh-row');
    for (var k = 1; k < d.time.length && k <= 7; k++) {
      var dc = el('div', 'sh-cell wide');
      dc.appendChild(el('div', 'sh-ct', fmt(dayFmt, local(d.time[k]))));
      dc.appendChild(icon(d.weather_code[k], false));
      dc.appendChild(el('div', 'sh-cv', deg(d.temperature_2m_max[k]) + ' / ' + deg(d.temperature_2m_min[k])));
      dc.appendChild(el('div', 'sh-cr', num(d.precipitation_probability_max[k]) && d.precipitation_probability_max[k] >= 20 ? d.precipitation_probability_max[k] + '%' : ''));
      dayRow.appendChild(dc);
    }
    card.appendChild(el('div', 'sh-label', 'Next 7 days'));
    card.appendChild(dayRow);
  }

  // ---------- weather: beaches ----------
  function surfLabel(ft) {
    return ft < 1 ? 'Flat' : ft < 2 ? 'Small' : ft < 4 ? 'Fun size' : ft < 6 ? 'Good size' : 'Big';
  }

  function renderBeach(card, name) {
    var all = (attrs('daytrip') || {}).destinations || [];
    var b = null;
    all.forEach(function (x) { if (x.name === name) b = x; });
    var head = el('div', 'sh-head');
    head.appendChild(el('div', 'sh-title', name + (b && b.place ? ' · ' + b.place : '')));
    head.appendChild(el('div', 'sh-nav', name === 'Ventura' ? '▼ Home' : '▲ Home'));
    card.appendChild(head);
    if (!b) { card.appendChild(el('div', 'sh-empty', 'No beach data from Home Assistant right now.')); return; }

    var drive = el('div', 'sh-drive', num(b.drive_minutes) ? b.drive_minutes + ' min drive' + (num(b.drive_miles) ? ' · ' + b.drive_miles + ' mi' : '') + ' with traffic now' : 'Drive time unavailable');
    card.appendChild(drive);

    var top = el('div', 'sh-top');
    var s = b.surf;
    var now = el('div', 'sh-now');
    now.appendChild(el('div', 'sh-temp', s && num(s.wave_ft) ? round1(s.wave_ft) + ' ft' : '–'));
    now.appendChild(el('div', 'sh-cond', s && num(s.wave_ft) ? surfLabel(s.wave_ft) + ' surf' : 'No surf data'));
    if (s && num(s.period_s)) now.appendChild(el('div', 'sh-sub', Math.round(s.period_s) + ' s period'));
    top.appendChild(now);

    var grid = el('div', 'sh-grid');
    detail(grid, 'Swell', s && num(s.swell_ft) ? round1(s.swell_ft) + ' ft @ ' + Math.round(s.swell_period_s) + ' s' : '–', s && num(s.swell_dir) ? 'from ' + compass(s.swell_dir) + ' (' + Math.round(s.swell_dir) + '°)' : '');
    detail(grid, 'Wind', num(b.wind_mph) ? Math.round(b.wind_mph) + ' mph ' + compass(b.wind_dir) : '–', num(b.wind_gust_mph) ? 'gusts ' + Math.round(b.wind_gust_mph) + ' mph' : '');
    detail(grid, 'Air', deg(b.air_temp), num(b.air_high) ? 'high ' + deg(b.air_high) : '');
    detail(grid, 'Water', deg(b.water_temp), '');
    // Next four tides.
    var nowT = Date.now();
    var tides = (b.tides || []).filter(function (t) { return Date.parse(t.t) > nowT; }).slice(0, 4);
    tides.forEach(function (t, i) {
      detail(grid, i ? '' : 'Tides', (t.type === 'H' ? 'High ' : 'Low ') + round1(t.v) + ' ft', fmt(timeFmt, Date.parse(t.t)) + (new Date(Date.parse(t.t)).getDate() !== new Date().getDate() ? ' tomorrow' : ''));
    });
    top.appendChild(grid);
    card.appendChild(top);

    var row = el('div', 'sh-row');
    (b.surf_forecast || []).slice(0, 9).forEach(function (f, i) {
      var t = Date.parse(f.t);
      var cell = el('div', 'sh-cell');
      cell.appendChild(el('div', 'sh-ct', i === 0 && t <= nowT ? 'Now' : fmt(hourFmt, t)));
      cell.appendChild(el('div', 'sh-cv big', num(f.wave_ft) ? round1(f.wave_ft) + ' ft' : '–'));
      cell.appendChild(el('div', 'sh-cr', num(f.period_s) ? Math.round(f.period_s) + ' s' : ''));
      row.appendChild(cell);
    });
    card.appendChild(el('div', 'sh-label', 'Surf, next 24 hours'));
    card.appendChild(row);
    if (b.tide_name && b.tide_name !== name) card.appendChild(el('div', 'sh-foot', 'Tides from the ' + b.tide_name + ' station · surf from Open-Meteo Marine'));
    else card.appendChild(el('div', 'sh-foot', 'Surf from Open-Meteo Marine · tides from NOAA'));
  }

  // ---------- monitor ----------
  // Each row: [name, value, level] with level ok | warn | bad | null.
  function worst(rows) {
    var rank = { bad: 3, warn: 2, ok: 1 };
    var w = null;
    rows.forEach(function (r) { if (r[2] && (!w || rank[r[2]] > rank[w])) w = r[2]; });
    return w || 'unknown';
  }
  function mb(bytes) { return bytes >= 1e9 ? (bytes / 1e9).toFixed(1) + ' GB' : Math.round(bytes / 1e6) + ' MB'; }
  function ago(hours) { return hours >= 48 ? Math.round(hours / 24) + ' days' : Math.round(hours) + ' h'; }

  function tvRows() {
    var rows = [];
    var units = (tvInfo.STORAGE && tvInfo.STORAGE.units) || [];
    units.forEach(function (u) {
      var free = u.availableCapacity;
      if (u.type === 'INTERNAL') rows.push(['Storage', mb(free) + ' free of ' + mb(u.capacity), free < 50e6 ? 'bad' : free < 150e6 ? 'warn' : 'ok']);
      else rows.push(['USB drive', mb(free) + ' free of ' + mb(u.capacity), null]);
    });
    if (tvInfo.memory) {
      var f = tvInfo.memory.free;
      rows.push(['Memory', mb(f) + ' free of ' + mb(tvInfo.memory.total), f < 100e6 ? 'bad' : f < 200e6 ? 'warn' : 'ok']);
    }
    if (tvInfo.CPU && num(tvInfo.CPU.load)) {
      var l = tvInfo.CPU.load * 100;
      rows.push(['CPU', Math.round(l) + '%', l > 90 ? 'warn' : 'ok']);
    }
    var eth = tvInfo.ETHERNET_NETWORK;
    if (eth) rows.push(['Network', (eth.status === 'CONNECTED' ? 'Wired · ' + eth.ipAddress : 'Wired · ' + String(eth.status).toLowerCase()), eth.status === 'CONNECTED' ? 'ok' : 'bad']);
    else if (tvInfo.NETWORK) rows.push(['Network', String(tvInfo.NETWORK.networkType), tvInfo.NETWORK.networkType === 'NONE' ? 'bad' : 'ok']);
    rows.push(['Roche Home', 'up ' + ago((Date.now() - started) / 3600000), 'ok']);
    if (tvInfo.BUILD) rows.push(['Software', String(tvInfo.BUILD.buildVersion || '').replace(/^TIZEN-/, 'Tizen ').replace(/-RELEASE_/, ' · '), null]);
    if (!tz) rows.push(['TV', 'details only on the TV', null]);
    return rows;
  }

  function piRows() {
    var a = attrs('pi');
    var rows = [];
    if (!a) return [['Raspberry Pi', 'no answer from Home Assistant', 'bad']];
    rows.push(['Home Assistant', 'answering', 'ok']);
    if (num(a.cpu_percent)) rows.push(['CPU', Math.round(a.cpu_percent) + '%' + (a.load ? ' · load ' + a.load[0] : ''), a.cpu_percent > 95 ? 'warn' : 'ok']);
    if (num(a.temperature_c)) rows.push(['Temperature', round1(a.temperature_c) + ' °C', a.temperature_c >= 80 ? 'bad' : a.temperature_c >= 70 ? 'warn' : 'ok']);
    if (num(a.memory_percent)) rows.push(['Memory', Math.round(a.memory_percent) + '% used · ' + a.memory_available_mb + ' MB free', a.memory_percent >= 92 ? 'bad' : a.memory_percent >= 85 ? 'warn' : 'ok']);
    if (num(a.swap_percent)) rows.push(['Swap', Math.round(a.swap_percent) + '% used', a.swap_percent >= 90 ? 'bad' : a.swap_percent >= 75 ? 'warn' : 'ok']);
    if (num(a.disk_free_gb)) rows.push(['Disk', round1(a.disk_free_gb) + ' GB free (' + Math.round(a.disk_percent) + '% used)', a.disk_percent >= 90 ? 'bad' : a.disk_percent >= 80 ? 'warn' : 'ok']);
    rows.push(['Power', a.undervoltage ? 'under-voltage!' : 'OK', a.undervoltage ? 'bad' : 'ok']);
    if (num(a.uptime_hours)) rows.push(['Uptime', ago(a.uptime_hours), null]);
    var ph = states.piholeStatus;
    if (ph) rows.push(['Pi-hole', (ph.state === 'on' ? 'on' : 'off') + (value('piholeBlocked') != null ? ' · ' + Math.round(value('piholeBlocked')) + ' blocked today' : ''), ph.state === 'on' ? 'ok' : 'warn']);
    return rows;
  }

  function netRows() {
    var s = states.internet;
    var a = attrs('internet');
    if (!s || !a) return [['Internet', 'no answer from Home Assistant', 'warn']];
    var rows = [['Status', s.state, s.state === 'online' ? 'ok' : 'bad']];
    function ping(name, p) {
      if (!p) return;
      rows.push([name, (num(p.ms) ? round1(p.ms) + ' ms' : 'no reply') + (p.loss ? ' · ' + Math.round(p.loss) + '% loss' : ''),
        !num(p.ms) || p.loss >= 50 ? 'bad' : p.ms > 100 || p.loss > 0 ? 'warn' : 'ok']);
    }
    var pg = a.ping || {};
    ping('Router', pg.router);
    ping('Cloudflare', pg.cloudflare);
    ping('Google', pg.google);
    var dns = a.dns || {};
    if (dns.pihole) rows.push(['DNS (Pi-hole)', dns.pihole.ok ? dns.pihole.ms + ' ms' : 'failing', dns.pihole.ok ? (dns.pihole.ms > 500 ? 'warn' : 'ok') : 'bad']);
    if (dns.router) rows.push(['DNS (router)', dns.router.ok ? dns.router.ms + ' ms' : 'failing', dns.router.ok ? 'ok' : 'warn']);
    if (a.https) rows.push(['Web (HTTPS)', a.https.ok ? a.https.ms + ' ms' : 'failing', a.https.ok ? (a.https.ms > 2000 ? 'warn' : 'ok') : 'bad']);
    var up = value('uptime');
    if (up != null) rows.push(['Uptime', round1(up) + '%', up < 95 ? 'warn' : 'ok']);
    return rows;
  }

  function column(title, rows) {
    var col = el('div', 'mon-col');
    var h = el('div', 'mon-head');
    h.appendChild(el('span', 'dot ' + worst(rows)));
    h.appendChild(el('span', '', title));
    col.appendChild(h);
    rows.forEach(function (r) {
      var row = el('div', 'mon-row');
      row.appendChild(el('span', 'dot small ' + (r[2] || 'none')));
      row.appendChild(el('span', 'mon-name', r[0]));
      row.appendChild(el('span', 'mon-val', r[1]));
      col.appendChild(row);
    });
    return col;
  }

  function renderMonitor(card) {
    var head = el('div', 'sh-head');
    head.appendChild(el('div', 'sh-title', 'Monitor'));
    head.appendChild(el('div', 'sh-nav', 'Back to close'));
    card.appendChild(head);
    var cols = el('div', 'mon-cols');
    cols.appendChild(column('TV', tvRows()));
    cols.appendChild(column('Raspberry Pi', piRows()));
    cols.appendChild(column('Internet', netRows()));
    card.appendChild(cols);
  }

  // ---------- tiles ----------
  function renderFaces() {
    var f = faces.weather;
    if (f) {
      clear(f);
      var j = forecast && forecast.data;
      var sky = GH.sky;
      var code = j ? j.current.weather_code : sky && sky.code;
      var night = j ? j.current.is_day === 0 : sky && !sky.isDay;
      if (code != null) f.appendChild(icon(code, night, 'tile-wx-icon'));
      var t = j ? j.current.temperature_2m : null;
      f.appendChild(el('div', 'tile-wx-temp', t != null ? deg(t) : 'Weather'));
    }
    var m = faces.monitor;
    if (m) {
      clear(m);
      [['TV', worst(tvRows())], ['Pi', worst(piRows())], ['Net', worst(netRows())]].forEach(function (r) {
        var row = el('div', 'tile-mon-row');
        row.appendChild(el('span', 'dot small ' + r[1]));
        row.appendChild(el('span', '', r[0]));
        m.appendChild(row);
      });
    }
  }

  function refreshAll(done) {
    var n = 3;
    function next() { if (--n === 0) { renderFaces(); if (view) render(); if (done) done(); } }
    loadForecast(next);
    loadTV(next);
    loadStates(Object.keys(E), next);
  }

  // ---------- page ----------
  function render() {
    clear(root);
    var card = el('div', 'sh-card ' + view);
    if (view === 'monitor') renderMonitor(card);
    else if (page === 0) renderBeach(card, 'Ventura');
    else if (page === 2) renderBeach(card, 'Malibu');
    else renderHome(card);
    if (view === 'weather') {
      var dots = el('div', 'sh-dots');
      for (var i = 0; i < 3; i++) dots.appendChild(el('span', i === page ? 'on' : ''));
      card.appendChild(dots);
    }
    root.appendChild(card);
  }

  function open(kind) {
    view = kind;
    page = 1;
    root.classList.add('open');
    render();
    refreshAll();
  }
  function close() {
    view = null;
    root.classList.remove('open');
    clear(root);
  }

  function onKey(e) {
    if (!view) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    if (e.type !== 'keydown') return;
    var k = e.keyCode;
    if (k === KEY.BACK || k === KEY.ESC || k === KEY.BACKSPACE) { close(); return; }
    if (view === 'weather' && (k === KEY.UP || k === KEY.DOWN)) {
      page = Math.max(0, Math.min(2, page + (k === KEY.UP ? -1 : 1)));
      render();
    }
  }
  window.addEventListener('keydown', onKey, true);
  window.addEventListener('keyup', onKey, true);

  var timer = null;
  function start() { clearInterval(timer); timer = setInterval(refreshAll, 5 * 60 * 1000); }
  document.addEventListener('visibilitychange', function () {
    if (document.hidden) { clearInterval(timer); close(); return; }
    refreshAll();
    start();
  });

  GH.panels = {
    open: open,
    isOpen: function () { return !!view; },
    // home.js hands over the tile's face element to draw into.
    face: function (kind, face) { faces[kind] = face; renderFaces(); }
  };

  refreshAll();
  start();

  // Preview (desktop browser): ?sheet=weather|ventura|malibu|monitor opens a page.
  var q = cfg.preview && GH.query && GH.query.sheet;
  if (q) setTimeout(function () {
    open(q === 'monitor' ? 'monitor' : 'weather');
    if (q === 'ventura') { page = 0; render(); } else if (q === 'malibu') { page = 2; render(); }
  }, 300);
}());
