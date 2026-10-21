/*
 * Roche Home - local alert banners and "Up next" reminders, from Home Assistant.
 *
 * Banners (top centre) appear only while something needs attention:
 *   wildfire       sensor.fire_incidents: a fire-type incident within
 *                  alerts.fireMiles, responded to in the last 12 hours
 *   earthquake     sensor.earthquakes: its `alert` (M4.0+ in the last 30 minutes)
 *   air quality    sensor.local_air_quality: AQI above 100
 *   weather        sensor.local_weather_alerts: each active NWS alert
 *   power          POWER_SHUTOFF in .env: SCE shutoff active or being considered
 * "Up next" (under the weather) lists at most two things within 4 hours:
 *   rocket launches from LAUNCHES in .env, and the trash or street
 *   sweeping evening reminder from sensor.household_schedule.
 *
 * A sensor that fails to load or reports an error shows nothing: no banner is
 * better than a wrong one, and the HA dashboards stay the place for detail.
 * Uses GH.ha from js/homeassistant.js. Nothing from the network is inserted as
 * HTML.
 *
 * Preview (desktop browser): ?alerts=mock shows sample alerts and reminders.
 */
(function () {
  'use strict';
  var GH = window.GH || {};
  var cfg = (GH.config && GH.config.alerts) || {};
  var bannerRoot = document.getElementById('banners');
  var nextRoot = document.getElementById('upnext');
  if (!GH.ha || !bannerRoot || !nextRoot || cfg.enabled === false) return;

  var E = cfg.entities || {};
  var FIRE_MILES = cfg.fireMiles || 10;
  var WITHIN = 4 * 3600 * 1000;
  var REFRESH = cfg.refreshMs || 60 * 1000;
  var states = {};
  var timer = null;

  var timeFmt = null;
  var dayFmt = null;
  try {
    timeFmt = new Intl.DateTimeFormat(GH.config.locale || undefined, { hour: 'numeric', minute: '2-digit' });
    dayFmt = new Intl.DateTimeFormat(GH.config.locale || undefined, { weekday: 'short', hour: 'numeric', minute: '2-digit' });
  } catch (e) { /* fallback below */ }

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
    var sameDay = new Date().toDateString() === d.toDateString();
    try { return (sameDay ? timeFmt : dayFmt).format(d); } catch (e) { return d.toLocaleString(); }
  }
  function span(ms) {
    var min = Math.max(0, Math.round(ms / 60000));
    var h = Math.floor(min / 60);
    return h ? h + 'h ' + (min % 60) + 'm' : min + 'm';
  }
  // A sensor's state if it was read in the last 30 minutes (HA unreachable
  // for longer: show nothing rather than an old alert).
  function fresh(key) {
    var s = states[key];
    return s && Date.now() - s.readAt <= 30 * 60 * 1000 ? s : null;
  }
  function attrs(key) {
    var s = fresh(key);
    return s && s.attributes && !s.attributes.error ? s.attributes : null;
  }

  // ---------- banners: [{ level: 'red'|'amber', title, detail }] ----------
  function fireBanners(now) {
    var a = attrs('fire');
    var out = [];
    ((a && a.incidents) || []).forEach(function (i) {
      if (!i || !i.fire || i.miles == null || i.miles > FIRE_MILES) return;
      var m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(String(i.responded || ''));
      var at = m ? new Date(+m[1], +m[2] - 1, +m[3], +m[4], +m[5]).getTime() : null;   // local, no offset
      if (at && now - at > 12 * 3600 * 1000) return;
      out.push({ level: i.miles <= 3 ? 'red' : 'amber', title: i.type || 'Fire',
        detail: i.miles + ' mi' + (i.city ? ' · ' + i.city : '') + (i.status ? ' · ' + i.status : '') });
    });
    return out.slice(0, 2);
  }
  function quakeBanners() {
    var a = attrs('quake');
    var q = a && a.alert;
    if (!q) return [];
    return [{ level: q.magnitude >= 5 ? 'red' : 'amber', title: 'Earthquake M' + q.magnitude,
      detail: (q.miles != null ? q.miles + ' mi · ' : '') + (q.place || '') + (parse(q.time) ? ' · ' + clock(parse(q.time)) : '') }];
  }
  function airBanners() {
    var s = fresh('air');
    var aqi = s ? parseFloat(s.state) : NaN;
    if (isNaN(aqi) || aqi <= 100) return [];
    var label = aqi > 300 ? 'Hazardous' : aqi > 200 ? 'Very unhealthy' : aqi > 150 ? 'Unhealthy' : 'Unhealthy for sensitive groups';
    return [{ level: aqi > 150 ? 'red' : 'amber', title: 'Air quality ' + Math.round(aqi), detail: label + ' · keep windows closed' }];
  }
  function weatherBanners() {
    var a = attrs('weather');
    return ((a && a.alerts) || []).slice(0, 2).map(function (w) {
      var until = parse(w.ends) || parse(w.expires);
      return { level: w.severity === 'Extreme' || w.severity === 'Severe' ? 'red' : 'amber',
        title: w.event || 'Weather alert', detail: until ? 'until ' + clock(until) : (w.headline || '') };
    });
  }
  function powerBanners() {
    var s = fresh('power');
    var a = attrs('power');
    if (!s || !a) return [];
    if (s.state === 'power_shutoff') return [{ level: 'red', title: 'SCE power shutoff', detail: (a.circuit || '') + ' circuit' }];
    if (s.state === 'under_consideration') return [{ level: 'amber', title: 'SCE may shut off power', detail: (a.circuit || '') + ' circuit is being watched' }];
    return [];
  }

  function renderBanners() {
    var now = Date.now();
    var list = [].concat(powerBanners(), fireBanners(now), quakeBanners(), weatherBanners(), airBanners());
    while (bannerRoot.firstChild) bannerRoot.removeChild(bannerRoot.firstChild);
    list.slice(0, 3).forEach(function (b) {
      var n = el('div', 'banner ' + b.level);
      n.appendChild(el('span', 'banner-dot'));
      n.appendChild(el('span', 'banner-title', b.title));
      if (b.detail) n.appendChild(el('span', 'banner-detail', b.detail));
      bannerRoot.appendChild(n);
    });
    bannerRoot.classList.toggle('on', list.length > 0);
  }

  // ---------- up next: [{ at, title, detail }] ----------
  function launches(now) {
    var a = attrs('launch');
    return ((a && a.launches) || []).filter(function (l) {
      var t = parse(l.net);
      return t && t - now <= WITHIN && now - t <= 30 * 60 * 1000 && !/fail|success|scrub/i.test(l.status || '');
    }).map(function (l) {
      var t = parse(l.net);
      return { at: t, title: (l.rocket || 'Launch') + (l.mission ? ' · ' + l.mission : ''),
        detail: clock(t) + (t > now ? ' · in ' + span(t - now) : ' · now') + (l.view === 'night' ? ' · look NW for the trail' : '') };
    });
  }
  function household(now) {
    var a = attrs('household');
    var hour = new Date(now).getHours();
    if (!a || hour < 15) return [];          // the evening before, from 3 PM (within 4 hours of sunset)
    var tonight = new Date(now);
    tonight.setHours(19, 0, 0, 0);
    var out = [];
    if (a.trash_tomorrow) out.push({ at: tonight.getTime(), title: 'Trash out tonight', detail: 'Pickup tomorrow' + (a.trash_holiday_delayed ? ' (holiday delay)' : '') });
    if (a.sweep_tomorrow) out.push({ at: tonight.getTime() + 1, title: 'Move the car tonight', detail: 'Street sweeping tomorrow' });
    return out;
  }

  function renderNext() {
    var now = Date.now();
    var list = launches(now).concat(household(now)).sort(function (x, y) { return x.at - y.at; }).slice(0, 2);
    while (nextRoot.firstChild) nextRoot.removeChild(nextRoot.firstChild);
    list.forEach(function (it) {
      var n = el('div', 'next-item');
      n.appendChild(el('div', 'next-title', it.title));
      n.appendChild(el('div', 'next-detail', it.detail));
      nextRoot.appendChild(n);
    });
    nextRoot.classList.toggle('on', list.length > 0);
  }

  function render() { renderBanners(); renderNext(); }

  function load() {
    Object.keys(E).forEach(function (key) {
      if (!E[key]) return;   // not set in .env
      GH.ha.getState(E[key], function (j) {
        if (j) { j.readAt = Date.now(); states[key] = j; }   // a failed read keeps the last answer
        render();
      });
    });
  }

  function start() { clearInterval(timer); timer = setInterval(load, REFRESH); }

  document.addEventListener('visibilitychange', function () {
    if (document.hidden) { clearInterval(timer); timer = null; return; }
    load();
    start();
  });

  if (GH.config.preview && GH.query && GH.query.alerts === 'mock') {
    var soon = new Date(Date.now() + 95 * 60 * 1000).toISOString();
    var mock = {
      fire: { attributes: { incidents: [{ type: 'Vegetation Fire', fire: true, miles: 2.4, city: 'Springfield', status: 'On scene' }] } },
      weather: { attributes: { alerts: [{ event: 'Red Flag Warning', severity: 'Severe', ends: new Date(Date.now() + 30 * 3600 * 1000).toISOString() }] } },
      air: { state: '128', attributes: {} },
      launch: { attributes: { launches: [{ rocket: 'Falcon 9', mission: 'Starlink Group 15-15', net: soon, status: 'Go', view: 'night' }] } },
      household: { attributes: { trash_tomorrow: true } }
    };
    Object.keys(mock).forEach(function (k) { mock[k].readAt = Date.now(); states[k] = mock[k]; });
    render();
    return;
  }

  load();
  start();
}());
