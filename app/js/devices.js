/*
 * Roche Home - the Home tile: devices from Home Assistant, by room.
 *
 * Rooms are HA's areas; every controllable entity in them is a card
 * (lights, switches, fans, numbers such as a volume, media players, scenes,
 * covers, locks, climate). Entities without a room go under "Other". Cards of
 * the same device sit together and are named by their role ("Power",
 * "Light", "Volume"). Nothing is listed by hand, so devices added to HA appear
 * the next time Home opens. home.exclude in config.js hides entities (this
 * TV itself, the BabySteps switches).
 *
 * Keys: Up/Down room, Left/Right card, OK acts: toggles, runs a scene, or
 * opens a small control (Left/Right volume, brightness or temperature; OK
 * play/pause or on/off; Back closes it). Locks, covers and the Pi-hole switch
 * ask for a second OK. States refresh every few seconds while open.
 */
(function () {
  'use strict';
  var GH = window.GH || {};
  var cfg = (GH.config && GH.config.home) || {};
  var root = document.getElementById('devices');
  if (!root || !GH.ha || !GH.ha.call) return;

  var K = { LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, ENTER: 13, BACK: 10009, ESC: 27, BACKSPACE: 8 };
  var DOMAINS = ['light', 'switch', 'fan', 'number', 'media_player', 'scene', 'cover', 'lock', 'climate', 'humidifier'];
  var CONFIRM = new RegExp(cfg.confirm || '^(lock|cover)\\.|^switch\\.pi_hole$');
  var EXCLUDE = new RegExp(cfg.exclude || 'samsung_6_series|babysteps|lcars');
  var ROLE = { light: 'Light', switch: 'Power', fan: 'Fan', media_player: 'Player', climate: 'Thermostat', cover: 'Cover', lock: 'Lock', humidifier: 'Humidifier' };

  var rooms = [];          // [{ name, cards: [{ id, device, label }] }]
  var states = {};
  var view = false;
  var row = 0;
  var col = [];
  var popup = null;        // card id with its control open
  var armed = null;        // { id, until } waiting for a confirming OK
  var note = '';
  var timer = null;

  // ---------- helpers ----------
  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }
  function clear(n) { while (n.firstChild) n.removeChild(n.firstChild); }
  function domain(id) { return id.split('.')[0]; }
  function st(id) { return states[id] || { state: 'unknown', attributes: {} }; }
  function isOn(id) { return /^(on|playing|open|unlocked|heat|cool|heat_cool|auto)$/.test(st(id).state); }
  function getStates(ids, done) {
    var n = ids.length;
    if (!n) { done(); return; }
    ids.forEach(function (id) {
      GH.ha.getState(id, function (j) { if (j) states[id] = j; if (--n === 0) done(); });
    });
  }

  // A light that dims, a media player with volume, a number or a thermostat
  // gets a control; everything else acts on OK.
  function adjustable(id) {
    var d = domain(id);
    var a = st(id).attributes;
    if (d === 'number' || d === 'climate' || d === 'media_player') return true;
    if (d === 'light') return (a.supported_color_modes || []).some(function (m) { return m !== 'onoff'; });
    return false;
  }

  function stateText(id) {
    var s = st(id);
    var a = s.attributes;
    var d = domain(id);
    if (s.state === 'unavailable') return 'Unavailable';
    if (d === 'scene') return 'Scene';
    if (d === 'number') return Math.round(parseFloat(s.state)) + (a.unit_of_measurement ? ' ' + a.unit_of_measurement : '');
    if (d === 'light' && s.state === 'on' && a.brightness) return 'On · ' + Math.round(a.brightness / 2.55) + '%';
    if (d === 'media_player') {
      if (s.state === 'playing' || s.state === 'paused') return (s.state === 'paused' ? 'Paused' : 'Playing') + (a.media_title ? ' · ' + a.media_title : '');
      return s.state === 'off' ? 'Off' : s.state.charAt(0).toUpperCase() + s.state.slice(1);
    }
    if (d === 'climate') return (a.current_temperature != null ? Math.round(a.current_temperature) + '° now' : '') + (a.temperature != null ? ' · set ' + a.temperature + '°' : '');
    return s.state.charAt(0).toUpperCase() + s.state.slice(1).replace(/_/g, ' ');
  }

  // ---------- loading ----------
  var TEMPLATE = '{% set ns = namespace(out=[]) %}{% for a in areas() %}{% for e in area_entities(a) %}' +
    '{% set d = device_id(e) %}{% set ns.out = ns.out + [[area_name(a), e, (device_attr(d, "name_by_user") or device_attr(d, "name")) if d else ""]] %}' +
    '{% endfor %}{% endfor %}{% set ns2 = namespace(out=[]) %}{% for s in states %}{% if not area_id(s.entity_id) %}' +
    '{% set d = device_id(s.entity_id) %}{% set ns2.out = ns2.out + [["Other", s.entity_id, (device_attr(d, "name_by_user") or device_attr(d, "name")) if d else ""]] %}' +
    '{% endif %}{% endfor %}{{ (ns.out + ns2.out) | tojson }}';

  function load(done) {
    GH.ha.template(TEMPLATE, function (text) {
      var list = null;
      try { list = JSON.parse(text); } catch (e) { list = null; }
      if (!list) { done(false); return; }
      var ids = list.filter(function (x) { return DOMAINS.indexOf(domain(x[1])) >= 0 && !EXCLUDE.test(x[1]); }).map(function (x) { return x[1]; });
      getStates(ids, function () {
        var byRoom = {};
        var order = [];
        list.forEach(function (x) {
          var id = x[1];
          if (ids.indexOf(id) < 0 || st(id).state === 'unavailable') return;
          if (!byRoom[x[0]]) { byRoom[x[0]] = []; order.push(x[0]); }
          byRoom[x[0]].push({ id: id, device: x[2] || '' });
        });
        rooms = order.map(function (name) {
          var cards = byRoom[name];
          // Group a device's entities and name each by its role.
          cards.sort(function (a, b) { return (a.device + a.id).localeCompare(b.device + b.id); });
          cards.forEach(function (c) {
            var name2 = st(c.id).attributes.friendly_name || c.id;
            var siblings = cards.filter(function (o) { return o.device && o.device === c.device; }).length;
            var rest = c.device && name2.indexOf(c.device) === 0 ? name2.slice(c.device.length).trim() : '';
            c.label = rest || (siblings > 1 ? ROLE[domain(c.id)] || name2 : name2);
            c.group = siblings > 1 ? c.device : '';
          });
          return { name: name, cards: cards };
        });
        // "Other" (no room in HA) last.
        rooms.sort(function (a, b) { return (a.name === 'Other') - (b.name === 'Other'); });
        while (col.length < rooms.length) col.push(0);
        done(true);
      });
    });
  }

  function refresh() {
    var ids = [];
    rooms.forEach(function (r) { r.cards.forEach(function (c) { ids.push(c.id); }); });
    getStates(ids, function () { if (view) render(); });
  }

  // ---------- icons (drawn here; small and plain) ----------
  var ICONS = {
    light: '<path d="M32 8a16 16 0 0 0-9 29c2 2 3 4 3 7h12c0-3 1-5 3-7a16 16 0 0 0-9-29z"/><rect x="25" y="47" width="14" height="4" rx="2"/><rect x="27" y="53" width="10" height="4" rx="2"/>',
    switch: '<path d="M32 10v20" stroke-width="6" stroke-linecap="round" fill="none"/><path d="M20 18a18 18 0 1 0 24 0" stroke-width="6" stroke-linecap="round" fill="none"/>',
    number: '<path d="M14 26h10l12-10v32L24 38H14z"/><path d="M44 22a14 14 0 0 1 0 20" stroke-width="5" fill="none" stroke-linecap="round"/>',
    media_player: '<rect x="10" y="14" width="44" height="30" rx="4"/><rect x="24" y="48" width="16" height="4" rx="2"/>',
    scene: '<path d="M32 8l7 15 16 2-12 11 3 16-14-8-14 8 3-16L9 25l16-2z"/>',
    fan: '<circle cx="32" cy="32" r="5"/><path d="M32 27c-2-10 4-17 10-15s4 12-10 15zM37 32c10-2 17 4 15 10s-12 4-15-10zM32 37c2 10-4 17-10 15s-4-12 10-15zM27 32c-10 2-17-4-15-10s12-4 15 10z"/>',
    lock: '<rect x="14" y="28" width="36" height="26" rx="5"/><path d="M22 28v-8a10 10 0 0 1 20 0v8" stroke-width="5" fill="none"/>',
    cover: '<rect x="12" y="10" width="40" height="44" rx="3" fill="none" stroke-width="4"/><path d="M12 22h40M12 32h40M12 42h40" stroke-width="3"/>',
    climate: '<rect x="27" y="8" width="10" height="34" rx="5" fill="none" stroke-width="4"/><circle cx="32" cy="46" r="9"/>'
  };
  function icon(id) {
    var n = el('div', 'dv-icon');
    var d = domain(id);
    var body = ICONS[d] || ICONS.switch;
    if (/music/.test(id)) body = '<path d="M26 14l22-6v30" stroke-width="5" fill="none"/><circle cx="21" cy="44" r="7"/><circle cx="43" cy="38" r="7"/><path d="M26 14v30" stroke-width="5"/>';
    n.innerHTML = '<svg viewBox="0 0 64 64" width="100%" height="100%" fill="currentColor" stroke="currentColor">' + body + '</svg>';   // fixed markup, no network text
    return n;
  }

  // ---------- rendering ----------
  function render() {
    clear(root);
    var page = el('div', 'dv-page');
    var head = el('div', 'dv-head');
    head.appendChild(el('div', 'dv-title', 'Home'));
    head.appendChild(el('div', 'dv-hint', note || 'OK to use · Back to close'));
    page.appendChild(head);
    if (!rooms.length) {
      page.appendChild(el('div', 'dv-empty', 'No devices in Home Assistant yet.'));
      root.appendChild(page);
      return;
    }
    var track = el('div', 'dv-rooms');
    rooms.forEach(function (r, ri) {
      var rowEl = el('div', 'dv-room' + (ri === row ? ' focused' : ''));
      rowEl.appendChild(el('div', 'dv-roomname', r.name));
      var strip = el('div', 'dv-strip');
      r.cards.forEach(function (c, ci) {
        var focused = ri === row && ci === col[ri];
        var card = el('div', 'dv-card' + (isOn(c.id) ? ' on' : '') + (focused ? ' focused' : '') + (armed && armed.id === c.id ? ' armed' : ''));
        card.appendChild(icon(c.id));
        if (c.group) card.appendChild(el('div', 'dv-group', c.group));
        card.appendChild(el('div', 'dv-name', c.label));
        card.appendChild(el('div', 'dv-state', armed && armed.id === c.id ? 'Press OK again' : stateText(c.id)));
        strip.appendChild(card);
      });
      strip.style.transform = 'translateX(' + (-Math.max(0, col[ri] - 3) * 330) + 'px)';
      rowEl.appendChild(strip);
      track.appendChild(rowEl);
    });
    track.style.transform = 'translateY(' + (-Math.max(0, row - 1) * 330) + 'px)';
    page.appendChild(track);
    if (popup) page.appendChild(renderPopup(popup));
    root.appendChild(page);
  }

  function renderPopup(id) {
    var s = st(id);
    var a = s.attributes;
    var d = domain(id);
    var box = el('div', 'dv-popup');
    box.appendChild(el('div', 'dv-pop-title', a.friendly_name || id));
    var value = null;
    var keys = '';
    if (d === 'number') { value = (parseFloat(s.state) - (a.min || 0)) / ((a.max || 100) - (a.min || 0)); keys = '◀ ▶ change · Back done'; }
    else if (d === 'light') { value = s.state === 'on' && a.brightness ? a.brightness / 255 : 0; keys = '◀ ▶ brightness · OK on/off · Back done'; }
    else if (d === 'media_player') { value = a.volume_level != null ? a.volume_level : null; keys = '◀ ▶ volume · OK ' + (s.state === 'off' ? 'turn on' : 'play/pause') + ' · ▼ turn off · Back done'; }
    else if (d === 'climate') { keys = '◀ ▶ temperature · Back done'; }
    box.appendChild(el('div', 'dv-pop-state', stateText(id)));
    if (value != null) {
      var bar = el('div', 'dv-bar');
      var fill = el('div', 'dv-fill');
      fill.style.width = Math.round(Math.max(0, Math.min(1, value)) * 100) + '%';
      bar.appendChild(fill);
      box.appendChild(bar);
    }
    box.appendChild(el('div', 'dv-pop-keys', keys));
    return box;
  }

  // ---------- actions ----------
  function after(ok) {
    note = ok ? '' : 'Home Assistant didn’t accept that.';
    setTimeout(refresh, 600);
    setTimeout(refresh, 2000);
  }
  function act(c) {
    var id = c.id;
    var d = domain(id);
    if (adjustable(id)) { popup = id; render(); return; }
    if (CONFIRM.test(id) && !(armed && armed.id === id && Date.now() < armed.until)) {
      armed = { id: id, until: Date.now() + 4000 };
      render();
      setTimeout(function () { if (armed && armed.id === id) { armed = null; if (view) render(); } }, 4100);
      return;
    }
    armed = null;
    if (d === 'scene') { GH.ha.call('scene', 'turn_on', { entity_id: id }, function (ok) { note = ok ? 'Ran ' + c.label : 'Couldn’t run ' + c.label; render(); }); return; }
    if (d === 'lock') { GH.ha.call('lock', st(id).state === 'locked' ? 'unlock' : 'lock', { entity_id: id }, after); return; }
    if (d === 'cover') { GH.ha.call('cover', 'toggle', { entity_id: id }, after); return; }
    // Show the change at once; the next refresh confirms it.
    states[id] = { state: isOn(id) ? 'off' : 'on', attributes: st(id).attributes };
    render();
    GH.ha.call(d === 'switch' || d === 'light' || d === 'fan' || d === 'humidifier' ? d : 'homeassistant', 'toggle', { entity_id: id }, after);
  }

  var adjustTimer = null;
  function adjust(id, dir) {
    var s = st(id);
    var a = s.attributes;
    var d = domain(id);
    var send;
    if (d === 'number') {
      var step = Math.max(a.step || 1, ((a.max || 100) - (a.min || 0)) / 20);
      var v = Math.max(a.min || 0, Math.min(a.max || 100, parseFloat(s.state) + dir * step));
      s.state = String(v);
      send = function () { GH.ha.call('number', 'set_value', { entity_id: id, value: v }, after); };
    } else if (d === 'light') {
      var pct = Math.max(1, Math.min(100, Math.round((s.state === 'on' && a.brightness ? a.brightness / 2.55 : 0) + dir * 10)));
      s.state = 'on';
      a.brightness = Math.round(pct * 2.55);
      send = function () { GH.ha.call('light', 'turn_on', { entity_id: id, brightness_pct: pct }, after); };
    } else if (d === 'media_player') {
      var vol = Math.max(0, Math.min(1, Math.round(((a.volume_level || 0) + dir * 0.05) * 100) / 100));
      a.volume_level = vol;
      send = function () { GH.ha.call('media_player', 'volume_set', { entity_id: id, volume_level: vol }, after); };
    } else if (d === 'climate') {
      var t = (a.temperature || 70) + dir;
      a.temperature = t;
      send = function () { GH.ha.call('climate', 'set_temperature', { entity_id: id, temperature: t }, after); };
    }
    render();
    // Several presses in a row send one request.
    clearTimeout(adjustTimer);
    if (send) adjustTimer = setTimeout(send, 400);
  }
  function popupOk(id) {
    var d = domain(id);
    var s = st(id).state;
    if (d === 'light') GH.ha.call('light', 'toggle', { entity_id: id }, after);
    else if (d === 'media_player') GH.ha.call('media_player', s === 'off' ? 'turn_on' : 'media_play_pause', { entity_id: id }, after);
  }

  // ---------- open / close / keys ----------
  function open() {
    view = true;
    popup = null;
    armed = null;
    note = '';
    root.classList.add('open');
    clear(root);
    root.appendChild(el('div', 'dv-empty', 'Loading devices…'));
    load(function (ok) {
      if (!view) return;
      if (!ok) { clear(root); root.appendChild(el('div', 'dv-empty', 'Couldn’t reach Home Assistant.')); return; }
      row = Math.min(row, Math.max(0, rooms.length - 1));
      render();
      clearInterval(timer);
      timer = setInterval(refresh, 3000);
    });
  }
  function close() {
    view = false;
    clearInterval(timer);
    root.classList.remove('open');
    clear(root);
  }

  function onKey(e) {
    if (!view) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    if (e.type !== 'keydown') return;
    var k = e.keyCode;
    if (k === K.BACK || k === K.ESC || k === K.BACKSPACE) {
      if (popup) { popup = null; render(); } else close();
      return;
    }
    var r = rooms[row];
    if (!r) return;
    if (popup) {
      if (k === K.LEFT || k === K.RIGHT) adjust(popup, k === K.LEFT ? -1 : 1);
      else if (k === K.ENTER && !e.repeat) popupOk(popup);
      else if (k === K.DOWN && domain(popup) === 'media_player') GH.ha.call('media_player', 'turn_off', { entity_id: popup }, after);
      return;
    }
    if (k === K.UP) row = Math.max(0, row - 1);
    else if (k === K.DOWN) row = Math.min(rooms.length - 1, row + 1);
    else if (k === K.LEFT) col[row] = Math.max(0, col[row] - 1);
    else if (k === K.RIGHT) col[row] = Math.min(r.cards.length - 1, col[row] + 1);
    else if (k === K.ENTER && !e.repeat) { act(r.cards[col[row]]); return; }
    if (armed) armed = null;
    note = '';
    render();
  }
  window.addEventListener('keydown', onKey, true);
  window.addEventListener('keyup', onKey, true);
  document.addEventListener('visibilitychange', function () { if (document.hidden && view) close(); });

  GH.devices = { open: open, isOpen: function () { return view; } };

  // Preview (desktop browser): ?home=1 opens it.
  if (GH.config && GH.config.preview && GH.query && GH.query.home) setTimeout(open, 600);
}());
