/*
 * Roche Home - log BabySteps entries from the remote.
 *
 * The BabySteps tile in the dock opens six buttons (Feed, Pump, Diaper, Tummy,
 * Bath, Sleep); each opens a short form of choice rows. Up/Down picks a row,
 * Left/Right changes its value, OK moves on and finally saves, Back goes back.
 * Saving calls Home Assistant's shell_command.babysteps_log (../rpi
 * scripts/babysteps/ha/babysteps.yaml), which validates the entry with
 * BabySteps' own rules and answers with its confirmation or its reasons; both
 * are shown as they come. The card then refreshes (GH.ha.refreshBaby).
 *
 * While open it takes every key first (window capture, after ambient.js), so
 * the dock underneath doesn't move.
 */
(function () {
  'use strict';
  var GH = window.GH || {};
  var root = document.getElementById('baby');
  if (!GH.ha || !root) return;

  var KEY = { LEFT: 37, UP: 38, RIGHT: 39, DOWN: 40, ENTER: 13, BACK: 10009, ESC: 27, BACKSPACE: 8 };
  var MIN = 60 * 1000;

  function opt(v, label) { return { v: v, label: label }; }
  function nums(list, unit) { return list.map(function (n) { return opt(n, n + ' ' + unit); }); }
  var WHEN = { name: 'ago', label: 'When', options: [opt(0, 'Now'), opt(15, '15 min ago'), opt(30, '30 min ago'), opt(60, '1 hr ago')] };

  // Each form: rows(values) -> rows to show (some depend on earlier choices),
  // and payload(values) -> the BabySteps request (see babysteps_today.py).
  var FORMS = {
    feed: {
      label: 'Feed',
      rows: function (v) {
        var r = [{ name: 'method', label: 'Type', options: [opt('bottle', 'Bottle'), opt('nursing', 'Nursing')] }];
        if (v.method === 'nursing') {
          r.push({ name: 'side', label: 'Side', options: [opt('left', 'Left'), opt('right', 'Right'), opt('both', 'Both')] });
          r.push({ name: 'minutes', label: 'Length', options: nums([5, 10, 15, 20, 30, 45], 'min'), start: 2 });
        } else {
          r.push({ name: 'amount', label: 'Amount', options: nums([1, 2, 3, 4, 5, 6, 7, 8], 'oz'), start: 3 });
          r.push({ name: 'contents', label: 'Milk', options: [opt('breastmilk', 'Breast milk'), opt('formula', 'Formula'), opt('mixed', 'Mixed')] });
        }
        r.push(WHEN);
        return r;
      },
      payload: function (v) {
        return v.method === 'nursing'
          ? { type: 'feed', method: 'nursing', side: v.side, durationMinutes: v.minutes }
          : { type: 'feed', method: 'bottle', amount: v.amount, unit: 'oz', contents: v.contents };
      },
      duration: function (v) { return v.method === 'nursing' ? v.minutes : 0; }
    },
    pump: {
      label: 'Pump',
      rows: function () {
        return [{ name: 'amount', label: 'Amount', options: nums([1, 2, 3, 4, 5, 6, 8, 10], 'oz'), start: 3 },
          { name: 'side', label: 'Side', options: [opt('both', 'Both'), opt('left', 'Left'), opt('right', 'Right')] }, WHEN];
      },
      payload: function (v) { return { type: 'pump', amount: v.amount, unit: 'oz', side: v.side }; }
    },
    diaper: {
      label: 'Diaper',
      rows: function (v) {
        var r = [{ name: 'kind', label: 'Type', options: [opt('wet', 'Wet'), opt('dirty', 'Dirty'), opt('both', 'Both')] }];
        if (v.kind !== 'wet') r.push({ name: 'size', label: 'Size', options: [opt('', 'Skip'), opt('small', 'Small'), opt('medium', 'Medium'), opt('large', 'Large')] });
        r.push(WHEN);
        return r;
      },
      payload: function (v) {
        var p = { type: 'diaper', diaperType: v.kind };
        if (v.kind !== 'wet' && v.size) p.poopSize = v.size;
        return p;
      }
    },
    tummytime: {
      label: 'Tummy time',
      rows: function () { return [{ name: 'minutes', label: 'Length', options: nums([2, 5, 10, 15, 20, 30], 'min'), start: 1 }, WHEN]; },
      payload: function (v) { return { type: 'tummytime', durationMinutes: v.minutes }; },
      duration: function (v) { return v.minutes; }
    },
    bath: {
      label: 'Bath',
      rows: function () { return [WHEN]; },
      payload: function () { return { type: 'bath' }; }
    },
    sleep: {
      label: 'Sleep',
      rows: function (v) {
        var r = [{ name: 'action', label: 'What', options: [opt('asleep', 'Fell asleep'), opt('nap', 'Woke up')] }];
        if (v.action === 'nap') r.push({ name: 'minutes', label: 'Slept for', options: [opt(30, '30 min'), opt(45, '45 min'), opt(60, '1 hr'), opt(90, '1.5 hr'), opt(120, '2 hr'), opt(180, '3 hr'), opt(240, '4 hr')], start: 2 });
        r.push(WHEN);
        return r;
      },
      payload: function (v) {
        return v.action === 'nap' ? { type: 'sleep', durationMinutes: v.minutes } : { type: 'sleep', started: true };
      },
      // A nap's time is when it ended (BabySteps counts back); "fell asleep" is its start.
      note: function (v) {
        var b = GH.ha.baby();
        var s = b && b.lastSleep;
        if (v.action === 'nap' && s && s.at && !s.endedAt) return 'An open sleep is still running in BabySteps. End it in the app, or this adds a second one.';
        return '';
      }
    }
  };
  var ORDER = ['feed', 'pump', 'diaper', 'tummytime', 'bath', 'sleep'];

  // ---------- state ----------
  var view = null;          // 'menu' | 'form'
  var menuFocus = 0;
  var form = null;          // FORMS entry
  var formKey = '';
  var values = {};
  var rows = [];
  var rowFocus = 0;         // rows.length = the button row
  var buttonFocus = 0;      // 0 Save, 1 Cancel
  var saving = false;
  var closeTimer = null;

  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }
  function clear(n) { while (n.firstChild) n.removeChild(n.firstChild); }
  function childName() {
    var b = GH.ha.baby();
    return (b && b.child) || 'Baby';
  }

  // ---------- rendering ----------
  function renderMenu() {
    clear(root);
    var card = el('div', 'baby-card');
    card.appendChild(el('div', 'baby-title', 'Log for ' + childName()));
    var grid = el('div', 'baby-grid');
    ORDER.forEach(function (k, i) {
      grid.appendChild(el('div', 'baby-btn' + (i === menuFocus ? ' focused' : ''), FORMS[k].label));
    });
    card.appendChild(grid);
    card.appendChild(el('div', 'baby-hint', 'OK to choose · Back to close'));
    root.appendChild(card);
  }

  function syncRows() {
    rows = form.rows(values);
    rows.forEach(function (r) {
      var ok = r.options.some(function (o) { return o.v === values[r.name]; });
      if (!ok) values[r.name] = r.options[r.start || 0].v;
    });
    rowFocus = Math.min(rowFocus, rows.length);
  }

  function renderForm(status, statusClass) {
    clear(root);
    var card = el('div', 'baby-card');
    card.appendChild(el('div', 'baby-title', form.label + ' · ' + childName()));
    rows.forEach(function (r, i) {
      var row = el('div', 'baby-row' + (i === rowFocus ? ' focused' : ''));
      row.appendChild(el('div', 'baby-label', r.label));
      var chips = el('div', 'baby-chips');
      r.options.forEach(function (o) {
        chips.appendChild(el('span', 'baby-chip' + (o.v === values[r.name] ? ' on' : ''), o.label));
      });
      row.appendChild(chips);
      card.appendChild(row);
    });
    var note = form.note ? form.note(values) : '';
    if (note) card.appendChild(el('div', 'baby-note', note));
    var buttons = el('div', 'baby-buttons');
    ['Save', 'Cancel'].forEach(function (label, i) {
      buttons.appendChild(el('div', 'baby-btn small' + (rowFocus === rows.length && buttonFocus === i ? ' focused' : ''), saving && i === 0 ? 'Saving…' : label));
    });
    card.appendChild(buttons);
    if (status) card.appendChild(el('div', 'baby-status ' + (statusClass || ''), status));
    root.appendChild(card);
  }

  // ---------- open / close ----------
  function open() {
    clearTimeout(closeTimer);
    view = 'menu';
    menuFocus = 0;
    root.classList.add('open');
    renderMenu();
  }
  function close() {
    clearTimeout(closeTimer);
    view = null;
    saving = false;
    root.classList.remove('open');
    clear(root);
  }
  function openForm(key) {
    formKey = key;
    form = FORMS[key];
    values = {};
    rowFocus = 0;
    buttonFocus = 0;
    view = 'form';
    syncRows();
    renderForm();
  }

  // ---------- saving ----------
  function save() {
    if (saving) return;
    saving = true;
    var p = form.payload(values);
    p.source = 'tv';
    var ago = values.ago || 0;
    if (ago) {
      // BabySteps reads `timestamp` as the start for timed entries and as the
      // end for a nap given as a length; the form asks when it ended.
      var dur = formKey === 'sleep' ? 0 : (form.duration ? form.duration(values) : 0);
      p.timestamp = new Date(Date.now() - (ago + (dur || 0)) * MIN).toISOString();
    }
    renderForm();
    GH.ha.callService('shell_command', 'babysteps_log', { payload: p }, function (j) {
      saving = false;
      if (view !== 'form') return;
      var out = null;
      try { out = JSON.parse(j.service_response.stdout); } catch (e) { /* below */ }
      if (out && out.ok) {
        renderForm(out.message || 'Saved', 'ok');
        GH.ha.refreshBaby();
        closeTimer = setTimeout(close, 2500);
      } else if (out && out.errors) {
        renderForm(out.errors.join(' '), 'err');
      } else {
        renderForm('Couldn’t reach Home Assistant. Nothing was saved.', 'err');
      }
    });
  }

  // ---------- keys ----------
  function onKey(e) {
    if (!view) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    if (e.type !== 'keydown') return;
    var k = e.keyCode;
    if (k === KEY.BACK || k === KEY.ESC || k === KEY.BACKSPACE) {
      if (view === 'form' && !saving) { view = 'menu'; renderMenu(); } else if (view === 'menu') close();
      return;
    }
    if (view === 'menu') {
      if (k === KEY.LEFT) menuFocus = Math.max(0, menuFocus - 1);
      else if (k === KEY.RIGHT) menuFocus = Math.min(ORDER.length - 1, menuFocus + 1);
      else if (k === KEY.UP && menuFocus >= 3) menuFocus -= 3;
      else if (k === KEY.DOWN && menuFocus < 3) menuFocus += 3;
      else if (k === KEY.ENTER && !e.repeat) { openForm(ORDER[menuFocus]); return; }
      renderMenu();
      return;
    }
    if (saving) return;
    if (k === KEY.UP) rowFocus = Math.max(0, rowFocus - 1);
    else if (k === KEY.DOWN) rowFocus = Math.min(rows.length, rowFocus + 1);
    else if (k === KEY.LEFT || k === KEY.RIGHT) {
      var d = k === KEY.LEFT ? -1 : 1;
      if (rowFocus === rows.length) buttonFocus = Math.max(0, Math.min(1, buttonFocus + d));
      else {
        var r = rows[rowFocus];
        var i = 0;
        r.options.forEach(function (o, j) { if (o.v === values[r.name]) i = j; });
        values[r.name] = r.options[Math.max(0, Math.min(r.options.length - 1, i + d))].v;
        syncRows();
      }
    } else if (k === KEY.ENTER && !e.repeat) {
      if (rowFocus < rows.length) { rowFocus += 1; buttonFocus = 0; }
      else if (buttonFocus === 0) { save(); return; }
      else { view = 'menu'; renderMenu(); return; }
    }
    renderForm();
  }
  window.addEventListener('keydown', onKey, true);
  window.addEventListener('keyup', onKey, true);

  document.addEventListener('visibilitychange', function () { if (document.hidden && !saving) close(); });

  GH.babyLog = { open: open, isOpen: function () { return !!view; } };

  // Preview (desktop browser): ?baby=1 opens the buttons, ?baby=feed a form.
  var q = GH.config && GH.config.preview && GH.query && GH.query.baby;
  if (q) setTimeout(function () { open(); if (FORMS[q]) openForm(q); }, 300);
}());
