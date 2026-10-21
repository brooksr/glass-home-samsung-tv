/*
 * Glass Home - settings.
 *
 * Preview mode (a desktop browser, or ?preview=1 on the TV) uses a mock app
 * list and talks to the dev runner (node dev/run.js) instead of the TV service.
 *
 * Dock apps: each is resolved at start-up, first by the Tizen app IDs listed
 * (fast and exact), then by a case-insensitive name match over all installed
 * apps. Names are compared lower-case with "+" read as " plus" and other
 * punctuation removed, so "Disney+" is "disney plus". If the wrong app is
 * picked on the TV, put its exact Tizen app ID first in `ids`.
 */
(function () {
  'use strict';
  var q = {};
  String(location.search || '').replace(/^\?/, '').split('&').forEach(function (kv) {
    if (!kv) return;
    var i = kv.indexOf('=');
    try {
      q[decodeURIComponent(i < 0 ? kv : kv.slice(0, i))] = i < 0 ? '1' : decodeURIComponent(kv.slice(i + 1));
    } catch (e) { /* ignore malformed */ }
  });
  var preview = typeof window.tizen === 'undefined' || q.preview === '1';
  // This home's settings, from .env via js/env.js (both git-ignored).
  var env = (window.GH && window.GH.env) || {};
  var haLocal = env.haUrl ? env.haUrl + '/local/roche-home/' : '';

  window.GH = window.GH || {};
  window.GH.query = q;
  window.GH.config = {
    preview: preview,
    // The photo/shortcut service in this package (app id <packageId>.Photos).
    serviceName: 'Photos',
    serviceBase: q.api || (preview ? 'http://127.0.0.1:18090' : 'http://127.0.0.1:8090'),
    // false: no photo website or + tile; only the bundled photos and the dock
    // apps. For TVs without web services (e.g. a 2018 model on Tizen 4); also
    // drop <tizen:service> from config.xml.
    photoService: false,

    photoIntervalMs: 45000,
    // Photos matching the time of day and weather. Extra photos live on the Pi
    // (Home Assistant's /config/www/roche-home/photos, served at /local/); the
    // built-in ones are the fallback. Tags in each manifest entry:
    // time: dawn | day | golden | night, weather: clear | cloudy | fog | rain.
    photos: {
      matchSky: true,
      remoteManifest: haLocal && haLocal + 'photos/manifest.json'
    },
    fadeMs: 1600,
    kenBurnsScale: 1.06,
    clockEveryMs: 15000,
    pollEveryMs: 20000,
    longPressMs: 700,
    // Date and time format, e.g. 'en-AU', 'en-GB', 'en-US'. Empty: the TV's own.
    locale: '',

    // How YouTube shortcuts are handed to the YouTube app. 'uri' sends
    // https://www.youtube.com/watch?v=...; 'payload' is Samsung's Smart Hub
    // deep-link form; 'both' sends both. Switchable on the TV under + (bottom
    // row). Tested on a 2023 Samsung on Tizen 8: 'payload' only worked while
    // YouTube was already running (from a fresh start YouTube showed "Who's
    // watching?" and dropped the video); 'uri' worked in both cases.
    youtubeMode: 'uri',

    // Weather under the clock (js/weather.js), from Open-Meteo (free, no key).
    // Off until you set your location. Find coordinates with
    // https://geocoding-api.open-meteo.com/v1/search?name=YOUR+TOWN
    weather: {
      enabled: true,
      place: '',                 // label under the forecast, e.g. 'Springfield'
      latitude: env.latitude || 0,      // LATITUDE and LONGITUDE in .env
      longitude: env.longitude || 0,
      temperatureUnit: 'fahrenheit',   // or 'celsius'
      timezone: 'auto',          // or an IANA name such as 'Europe/London'
      refreshMs: 30 * 60 * 1000
    },

    // BabySteps card from Home Assistant (js/homeassistant.js), top left.
    // HA_URL and HA_TOKEN (a long-lived access token, HA profile > Security)
    // come from .env.
    homeAssistant: {
      enabled: true,
      url: env.haUrl || '',
      entity: 'sensor.babysteps_status',
      // Shown as "Inside" next to the outside temperature in the weather card.
      insideTemperature: env.insideTemperature,
      insideHumidity: env.insideHumidity,
      refreshMs: 60 * 1000
    },

    // Banners and "Up next" from Home Assistant (js/alerts.js); the entities
    // come from ../rpi (scripts/local-map). Needs homeAssistant above.
    alerts: {
      enabled: true,
      fireMiles: 10,                       // fire incidents closer than this
      refreshMs: 60 * 1000,
      entities: {
        fire: 'sensor.fire_incidents',
        quake: 'sensor.earthquakes',
        air: 'sensor.local_air_quality',
        weather: 'sensor.local_weather_alerts',
        power: env.powerShutoff,
        launch: env.launches,
        household: 'sensor.household_schedule'
      }
    },

    // Library (js/library.js): public-domain movies, TV, cartoons, audiobooks
    // and books, streamed. The catalogue and books are built by
    // tools/library/build.py and served from the Pi.
    library: {
      base: haLocal && haLocal + 'library/'
    },

    // Home tile (js/devices.js): Home Assistant's devices by room. `exclude`
    // hides entities (regular expression on the entity ID: this TV itself,
    // the BabySteps switches); `confirm` ones need a second OK.
    home: {
      exclude: 'samsung_6_series|babysteps|lcars',
      confirm: '^(lock|cover)\\.|^switch\\.pi_hole$'
    },

    // Screensaver (js/ambient.js): only the photos after this long idle.
    ambient: { enabled: true, afterMs: 3 * 60 * 1000 },

    // Evening dimming of the photos, by the TV's clock (24-hour).
    night: { enabled: true, from: '20:00', to: '06:00' },

    // The dock's apps, in order. Each is found at start-up by the Tizen app IDs
    // listed (list yours with `sdb shell 0 vd_applist` in Developer Mode), then
    // by name. Remove entries you don't have; add others the same way.
    // `storeId`: the Samsung store ID (`app_id` in vd_applist). Apps with one are
    // opened through Samsung's own launcher (http://127.0.0.1:8001); on a 2018
    // TV Netflix stays black and Prime Video crashes when opened with a plain
    // tizen.application.launch.
    // `icon`: a path under app/ to use your own image, or false for a plain
    // glass tile with the app's name. Some apps report a poor icon to other
    // apps (YouTube reports its Cobalt engine's pinwheel, Prime Video's may not
    // load, Samsung TV Plus is a system app), so those default to name tiles.
    apps: [
      { key: 'youtube', label: 'YouTube', storeId: '111299001912', ids: ['9Ur5IzDKqV.TizenYouTube', 'com.samsung.tv.cobalt-yt'],
        names: ['^youtube$'], icon: 'img/youtube.svg' },
      { key: 'netflix', label: 'Netflix', storeId: '11101200001', ids: ['RN1MCdNq8t.Netflix', 'org.tizen.netflix-app'],
        names: ['^netflix$', '\\bnetflix\\b'] },
      { key: 'prime', label: 'Prime Video', storeId: '3201512006785', ids: ['evKhCgZelL.AmazonIgnitionLauncher2', 'org.tizen.primevideo', 'org.tizen.ignition'],
        names: ['^prime video$', '^amazon prime video$', '\\bprime video\\b'], icon: 'img/primevideo.png' },
      { key: 'hulu', label: 'Hulu', storeId: '3201601007625', ids: ['LBUAQX1exg.Hulu'],
        names: ['^hulu$'] },
      { key: 'tvplus', label: 'Samsung TV Plus', ids: ['com.samsung.tv.tvPlusDeeplinker', 'com.samsung.tv.tvplus'],
        names: ['^samsung tv plus$', '^tv plus$', 'samsung tv plus'], icon: false },
      { key: 'disney', label: 'Disney+', storeId: '3201901017640', ids: ['MCmYXNxgcu.DisneyPlus'],
        names: ['^disney plus$', '\\bdisney plus\\b', '^disney$'] },
      { key: 'paramount', label: 'Paramount+', storeId: '3201710014981', ids: ['rJyOSqC6Up.CBSAllAccess'],
        names: ['^paramount plus$'] },
      { key: 'espn', label: 'ESPN', storeId: '3201708014618', ids: ['XtU8fQk2gN.ESPN'],
        names: ['^espn$'] },
      { key: 'spotify', label: 'Spotify', storeId: '3201606009684', ids: ['rJeHak5zRg.Spotify'],
        names: ['^spotify'] },
      { key: 'tubi', label: 'Tubi', storeId: '3201504001965', ids: ['3KA0pm7a7V.TubiTV'],
        names: ['^tubi'] },
      { key: 'pluto', label: 'Pluto TV', storeId: '3201808016802', ids: ['X1pKpFCiUu.PlutoTV'],
        names: ['^pluto tv'] },
      { key: 'freevee', label: 'Freevee', storeId: '3202102022871', ids: ['OK2Rdkjmsi.IMDbTV'],
        names: ['^freevee$'] },
      { key: 'playmovies', label: 'Google Play Movies', storeId: '3201601007250', ids: ['QizQxC7CUf.PlayMovies'],
        names: ['^google play movies'] },
      { key: 'cbsnews', label: 'CBS News', storeId: '3201711015155', ids: ['7zteowV8pB.CBSNews'],
        names: ['^cbs news'] },
      { key: 'pbs', label: 'PBS', storeId: '3201809016951', ids: ['70fRFUwYlD.OtterGAProd'],
        names: ['^pbs video$'] },
      { key: 'pbskids', label: 'PBS KIDS', storeId: '3201809016952', ids: ['Rk72oKFsRS.OtterKidsProd'],
        names: ['^pbs kids'] },
      { key: 'guide', label: 'Universal Guide', storeId: '3201710015067', ids: ['com.samsung.tv.UniversalBrowse'],
        names: ['^universal guide$'] },
      { key: 'browser', label: 'Internet', ids: ['org.tizen.browser'],
        names: ['^internet$', '^browser$'] }
    ]
  };
}());
