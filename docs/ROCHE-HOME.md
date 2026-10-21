# Roche Home: this TV's build of Glass Home

Glass Home, adapted for one TV and installed on 2026-10-21. This page records
what differs from upstream, how to rebuild and reinstall it, what was changed
on the TV and the Pi, and how to undo each part.

## The TV

| | |
|---|---|
| Model | Samsung UN55NU6900 (2018, "6 Series 55"), wired |
| OS | Tizen **4.0**, ARMv7, web engine reports Chrome-era WebKit (no flex `gap`, no `inset`, no `backdrop-filter`) |
| Address | `TV_IP` in `scripts/tizen-env.sh`, set **manually** on the TV (see [TV settings](#tv-settings-changed)) |
| MAC | `TV_MAC` in `.env` |
| Device ID (DUID) | `DUID` in `scripts/tizen-env.sh` (in the distributor certificate) |
| App | name **Roche Home**, package `GlassHome1`, app ID `GlassHome1.GlassHome` |

A backup of the TV's state before anything was installed is in
`~/samsung-tv-backup/` on the Mac: device info from `:8001/api/v2/`,
`sdb capability`, the DUID, and the installed apps (`applist-raw.txt`,
readable as `apps.tsv`). Settings menus can't be exported over `sdb` on a retail
TV; take photos of anything you care about.

## What differs from upstream Glass Home

| Change | Where | Why |
|---|---|---|
| Name **Roche Home** | `app/config.xml` `<name>`, `app/index.html` | Launcher tile name |
| No photo service (no phone website, no **+** tile) | `photoService: false` in `app/js/config.js`; `<tizen:service>` and the `web.service` feature removed from `app/config.xml` | Asked to skip it; 2018 TVs' Node/web-service support is unknown. Holding OK to move/hide dock items can't save without it. |
| Old-browser CSS | `app/css/tv.css`: every `inset: 0` → `top/right/bottom/left: 0`; flex `gap` on `#brand`, `#wx-now`, `#dock-row` → sibling margins | Tizen 4's engine lacks both; full-screen layers collapsed without the first |
| Brand and photo credit hidden | `#brand, #credit { display: none; }` in `tv.css` | Asked for |
| All 18 installed apps in the dock (not e-Manual), with this TV's exact IDs | `apps` in `app/js/config.js` | Upstream lists six |
| Apps open through Samsung's launcher | `storeId` per app in `config.js` (the `app_id` from `sdb shell 0 vd_applist`); `smartHubLaunch` in `app/js/platform.js` POSTs to `http://127.0.0.1:8001/api/v2/applications/<storeId>`, falling back to `tizen.application.launch` | Opened with a plain Tizen launch, Netflix stayed black and Prime Video crashed on this TV; Samsung's launcher (what Smart Hub and phone remotes use) works. Samsung TV Plus and Internet have no store ID and use the old way. |
| Screen blanked while opening an app or in the background | `html.launching` / `html.paused` → `#stage { display: none }` in `tv.css`; `activate()` in `home.js` (8-second guard restores it if the app never appears) | Frees graphics memory for the app on a 2018 TV |
| Alert banners and "Up next" | new `app/js/alerts.js`; `alerts` in `config.js`; `#banners`, `#upnext` in `index.html` | See [Alerts and reminders](#alerts-and-reminders) |
| BabySteps logger | new `app/js/babylog.js`; BabySteps tile first in the dock (`buildItems` in `home.js`, icon `app/img/babysteps.png` from the BabySteps PWA) | See [Logging BabySteps](#logging-babysteps-from-the-remote) |
| Photos that match the sky | `photos` in `config.js`; `skyMood` / `matchingPhotos` and the remote manifest in `loadManifest` (`home.js`); `weather.js` shares `GH.sky` (WMO code, sunrise, sunset); 64 tagged California photos on the Pi, see [tools/sky-photos](../tools/sky-photos/README.md) | Asked for. Time of day: dawn (45 min before to 1 h after sunrise), golden (90 min before to 30 min after sunset), day, night. Weather: clear (WMO 0–2), fog (45, 48), rain (drizzle, rain, showers, storms), else cloudy. Falls back to time only, then all photos, when fewer than 3 match. The 12 built-in photos are used only if the Pi is unreachable. |
| Holiday photos | `holiday` tag in the photo manifest; `holidayNow` and `matchingPhotos` in `home.js`; 35 photos on the Pi, see [tools/sky-photos](../tools/sky-photos/README.md) | Asked for. Halloween from Oct 3, Thanksgiving from 4 weeks before (not before Nov 1), Christmas from 4 weeks before (not before the day after Thanksgiving), New Year Dec 26-31; each through the day itself. They join the sky-matched photos, by time of day when 3 or more fit, and never show out of season. Preview with `?holiday=christmas` (or `none`). |
| Weather and Monitor tiles | new `app/js/panels.js`; `#sheet` in `index.html`; tiles added in `buildItems` (`home.js`), Weather second, Monitor last; `weather.js` shares its icons (`GH.wxIcon`) | See [Weather and Monitor pages](#weather-and-monitor-pages) |
| Library tile | new `app/js/library.js`; `library` in `config.js`; `#library` in `index.html`; icon `app/img/library.svg`; `GH.ha.ttsUrl` in `homeassistant.js`; catalogue and books on the Pi, see [tools/library](../tools/library/README.md) | See [Library](#library) |
| Home tile | new `app/js/devices.js`; `home` in `config.js`; `#devices` in `index.html`; icon `app/img/home.svg`; `GH.ha.call` / `GH.ha.template` in `homeassistant.js` | See [Home devices](#home-devices) |
| YouTube and Prime Video icons | `app/img/youtube.svg`, `app/img/primevideo.png` | Both apps report Tizen's pinwheel placeholder as their icon |
| Weather: icon, outside and inside temperature only | `weather` in `config.js` (coordinates from `.env`, `temperatureUnit: 'fahrenheit'`); `weather.js` adds `temperature_unit` to the Open-Meteo URL (cache key now `gh.weather.v2`); forecast and high/low hidden in `tv.css`; markup in `index.html` | Asked for |
| BabySteps card and inside temperature from Home Assistant | new `app/js/homeassistant.js`; `homeAssistant` in `config.js`; `#ha` in `index.html`; card styles at the end of `tv.css` | Asked for |

### Alerts and reminders

`app/js/alerts.js` reads these every minute and shows nothing unless needed
(and nothing if a sensor errors or HA hasn't answered for 30 minutes):

| Banner | Entity | Shown when |
|---|---|---|
| SCE power | `POWER_SHUTOFF` in `.env` | state `power_shutoff` (red) or `under_consideration` (amber) |
| Wildfire | `sensor.fire_incidents` | a fire-type incident within `alerts.fireMiles` (10) miles, responded to in the last 12 h; red within 3 mi |
| Earthquake | `sensor.earthquakes` | its `alert` is set (M4.0+ in the last 30 min); red from M5 |
| Weather | `sensor.local_weather_alerts` | any active NWS alert; red if Severe/Extreme |
| Air quality | `sensor.local_air_quality` | AQI over 100; red over 150 |

At most three banners, at the top between the BabySteps card and the clock; they stay up during the screensaver.
"Up next" (under the weather, at most two): Vandenberg launches within 4 hours
(`LAUNCHES` in `.env`), and from 3 PM the evening before, "Trash out
tonight" / "Move the car tonight" (`sensor.household_schedule`). Preview the
layout with `app/index.html?alerts=mock` in desktop Chrome.

### Weather and Monitor pages

**Weather** (tile shows the sky and temperature now). OK opens **Home**:
conditions, feels-like, high/low, inside temperature and humidity, humidity,
wind and gusts, air quality, UV, rain chance, sunrise/sunset, pressure, the next
12 hours and the next 7 days (Open-Meteo, fetched when opened, kept 15 min).
**Up** shows **Ventura**, **Down** shows **Malibu**, from `sensor.daytrip`:
drive time with traffic, surf height and period with a size label, swell
(height, period, direction), wind, air and water temperature, the next four
tides, and surf every 3 hours for 24 hours. Back closes.

Surf and wind were added to `../rpi/scripts/local-map/daytrip.py` for this
(the same Open-Meteo Marine call as the water temperature, no key). It was
deployed alone into HA's `/config/scripts/local-daytrip.py`, with the old one
in `/config/backups/local-map/`; the HA sensor config didn't change.

**Monitor** (tile shows a green/amber/red dot for TV, Pi and Net; OK opens the
details):
- TV (`tizen.systeminfo`): internal storage (amber under 150 MB, red under
  50 MB), USB drive, memory, CPU, network, Roche Home uptime, software build.
- Raspberry Pi (`sensor.raspberry_pi`, Pi-hole sensors): HA answering, CPU,
  temperature (amber 70 °C, red 80 °C), memory, swap, disk, power
  (under-voltage), uptime, Pi-hole on and ads blocked today.
- Internet (`sensor.internet`, `sensor.internet_uptime`): status, ping to
  router/Cloudflare/Google, DNS via Pi-hole and router, HTTPS, uptime.

Preview: `app/index.html?sheet=weather|ventura|malibu|monitor`.

### Library

Third tile. Rows: Continue (anything started), Movies (47 classics), TV (18
series, e.g. Dragnet, Bonanza, Sherlock Holmes 1954), Kids (Fleischer
Superman, Popeye, Casper, Gulliver's Travels…), Audiobooks (19 LibriVox
recordings) and Books (6 Gutenberg samples: Pride and Prejudice, Alice, the
Adventures of Sherlock Holmes, Frankenstein, The Wizard of Oz, Peter Rabbit).
All public domain; video and audio stream from the Internet Archive, books and
the catalogue from the Pi.

- **Video**: OK or Play/Pause pauses, Left/Right or Rew/FF −10 s/+30 s, Up
  shows the bar, Down next part, Back stops and saves the place.
- **Audiobooks**: OK pause, Left/Right −15 s/+30 s, Up/Down chapter; the photo
  slideshow stays behind it.
- **Books**: Up/Down scroll, Left/Right chapter, OK auto-scroll (Up/Down then
  set its speed), Play/Pause or the red button reads aloud with HA's
  `tts.google_translate_en_com` (the TV's own speech engine refuses web apps),
  one paragraph at a time, highlighted and kept in view. Each book's detail
  page also offers its LibriVox audiobook (a human reader).
- Resume points live in the TV's localStorage (`gh.library.v1`).

### Home devices

Fourth tile. Reads HA's areas and every controllable entity in them (light,
switch, fan, number, media_player, scene, cover, lock, climate, humidifier;
no room → "Other") with one template call, so new HA devices appear on the
next open. Entities of one device are grouped and named by role. OK toggles or
runs a scene; dimmable lights, media players, numbers (e.g. the noise
machine's volume) and thermostats open a control (◀ ▶ adjust, OK on/off or
play/pause, ▼ turns a player off). `home.exclude` hides this TV's own
entities and the BabySteps switches; `home.confirm` (locks, covers, the
Pi-hole switch) needs a second OK. States refresh every 3 s while open.

Devices on 2026-10-21: Living Room (Smart Life scenes All on / All off,
Chromecast Ultra, Family Room speaker), Bedroom TV, Nursery (Smart White Noise
Machine: power, light, music, volume; via HA's Tuya integration), Other
(Pi-hole). Not in HA: Geeni lights (Tuya, but only reachable after re-pairing
in Smart Life), Wyze plugs and camera (no official integration; HACS was
declined).

### Logging BabySteps from the remote

The BabySteps tile opens Feed, Pump, Diaper, Tummy time, Bath and Sleep; each
is a short form (Up/Down rows, Left/Right values, OK next/save, Back back).
Save calls `shell_command.babysteps_log` with `?return_response`, so the TV
shows BabySteps' own confirmation or reasons, then asks HA to refresh
`sensor.babysteps_status`. "When" is when it ended (BabySteps counts timed
entries back from it). **Sleep → Woke up** logs a finished sleep of the chosen
length; it can't close a sleep left running in BabySteps (the log command only
adds entries), so end those in the app. Preview with `?baby=1` or `?baby=feed`.

### Home Assistant

- The TV calls `$HA_URL/api/states/<entity>` directly every
  minute (Tizen doesn't enforce CORS for this app, so HA needs no CORS config).
- `sensor.babysteps_status` (built by `../rpi/scripts/local-map/babysteps_today.py`,
  refreshed every 5 minutes in HA) feeds the card: asleep/awake headline, last
  feed and diaper (amber after 3 hours), "Crying now". It hides when the data is
  over 2 hours old or the sensor's state isn't `ok`.
- `INSIDE_TEMPERATURE` in `.env` is the "Inside" temperature.
- **This home's settings** (HA address and token, coordinates, entities, and
  the addresses in this page) are in `.env` at the repository root, which is
  git-ignored; `.env.example` lists every key. `scripts/build.sh` writes the
  app's part into `app/js/env.js` (also git-ignored, packaged into the app);
  run `node scripts/make-env-js.js` yourself to update a desktop preview.
- **Token:** `HA_TOKEN` in `.env`, a long-lived HA access token. Make it in
  HA under your profile → Security → Long-lived access tokens.
  Without it the card and inside temperature simply don't appear.
  Revoke it in the same HA page if the TV is ever sold or reset.

## Build and install

Tools on the Mac (installed per [INSTALL.md](INSTALL.md), steps 2 and 3):
`~/tizen-studio` (Samsung's CLI SDK) and `~/tizen-agent-skills` (certificate
tool). Settings are in `scripts/tizen-env.sh` (git-ignored: `TV_IP`, `DUID`,
profile `GlassHome`).

1. Turn on Developer Mode on the TV: **Apps**, type **1 2 3 4 5**, Developer
   mode **On**, Host PC IP **`DEV_HOST_IP`** from `.env` (this Mac, not the TV), then
   restart the TV by holding the power button.
2. On the Mac:
   ```sh
   scripts/build.sh      # signs dist/GlassHome.wgt with the GlassHome profile
   scripts/install.sh    # installs over the old copy and opens it
   ```
3. Turn Developer Mode off again and restart the TV.

**Certificates:** `~/tizen-studio-data/keystore/samsung/GlassHome/`
(`author.p12`, `distributor.p12`) and the password in
`~/.glass-home-secrets/cert-password.env`. Back both up: every update must be
signed with the same author certificate. They expire after a year
(about 2027-10-21); renew with step 6 of INSTALL.md.

**Checking it on the TV:** the TV can't take screenshots, but the page can be
inspected:
```sh
. scripts/tizen-env.sh
sdb -s "$TV" shell 0 debug GlassHome1.GlassHome     # prints "port: NNNNN"
sdb -s "$TV" forward tcp:9222 tcp:NNNNN             # then DevTools JSON at http://127.0.0.1:9222/json
sdb -s "$TV" forward --remove-all                   # when done
```
To see the layout, open `app/index.html?still=1` in desktop Chrome started with
`--disable-web-security` (so it can reach HA); fonts differ slightly from the TV.

## TV settings changed

| Setting | Now | Default / revert |
|---|---|---|
| Developer Mode (**Apps** → 1 2 3 4 5) | Host PC IP `DEV_HOST_IP` (`.env`); turn **Off** when not installing | Off |
| **Settings → General → Network → Network Status → IP Settings** | IP manual `TV_IP`, mask `255.255.255.0`, gateway `GATEWAY_IP`, DNS manual **`PI_IP`** (Pi-hole) | IP and DNS **Obtain automatically** |
| Home row | Roche Home added (Apps → ⚙ → Roche Home → Add to Home) | Remove from the same menu |
| **Settings → General → Smart Features** | Autorun Smart Hub off, Autorun Last App on (if set) | Autorun Smart Hub on |

The router should also have a DHCP reservation for `TV_MAC` →
`TV_IP` so nothing else is given that address.

**If the Pi is down the TV has no internet** (its only DNS server is the Pi).
Fix: set DNS back to **Obtain automatically** on the TV.

## Pi-hole for the TV only

Pi-hole on the Pi blocks ads and tracking for the TV and nothing else: the TV
is the only client in the **TV** group, both blocklists apply only to that
group, and every other device (including this Mac, which uses the Pi for DNS)
is in **Default**, which has no blocklists. Details, the setup SQL and how to
revert are in `../rpi/README.md` under "Pi-hole for the Samsung TV only".

It blocks Samsung's ad, tracking and viewing-data servers and some app
telemetry. It does **not** remove ads inside streaming apps (YouTube, Hulu,
Pluto, Tubi, …): those come from the same servers as the shows.

## Removing it

- **Roche Home:** on the TV, **Apps → ⚙ → Roche Home → Delete**. Turn
  **Autorun Smart Hub** back on. Revoke its HA token.
- **TV network:** IP and DNS back to **Obtain automatically** (table above).
- **Pi-hole:** see the revert steps in `../rpi/README.md`.
- **Mac tools:** delete `~/tizen-studio`, `~/tizen-studio-data`,
  `~/.package-manager`, `~/.tizen`, `~/.tizen.sdk.path.config`,
  `~/tizen-agent-skills` and `~/.glass-home-secrets`, and the two
  `certificate-manager` items in the login Keychain.
- **Code:** everything above is in this repository's history on top of the
  upstream commit `8b3e233`; `git diff 8b3e233` shows all of it.
