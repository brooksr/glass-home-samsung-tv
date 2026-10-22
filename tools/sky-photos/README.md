# Sky-matched photos (Roche Home)

64 public-domain / CC0 photos of California (Santa Monica Mountains, Point
Mugu, Channel Islands, Big Sur, Yosemite, Joshua Tree, Lake Tahoe, …) from
Wikimedia Commons, tagged by time of day (`dawn`, `day`, `golden`, `night`) and
weather (`clear`, `cloudy`, `fog`, `rain`). `manifest.json` lists each with its
title, author, licence and source page.

They live on the Pi, not in the app (the TV is short of space):
`$PI_WWW/roche-home/photos/` (HA's `/config/www`; `PI_WWW` in `.env`),
served by Home Assistant at
`$HA_URL/local/roche-home/photos/` (no sign-in for `/local/`).
`photos.remoteManifest` in `app/js/config.js` points at it; the 12 built-in
photos are the fallback when the Pi is unreachable.

How the set was made (scripts run with `python3 -I`, downloads kept in their own
folder):
1. `search.py < queries.txt > candidates.json`: Commons search, keeping only
   public domain / CC0, landscape, at least 1920 px wide.
2. `thumbs.py candidates.json thumbs/`: 320 px previews for contact sheets;
   picked by eye (no people, events, maps or paintings).
3. `fetch.py`: downloads the picks at 1920 px and writes the manifest with the
   chosen tags; `resize.py raw/ out/` crops to 1920x1080 JPEG (q80, ~350 KB).
4. `scp out/* "$PI_SSH:$PI_WWW/roche-home/photos/"`

To add your own: put a 1920x1080 JPEG in that folder and add an entry with
`file`, `time` and `weather` to its `manifest.json`. No reinstall needed.

## Holiday photos

35 more (7 Halloween, 8 Thanksgiving, 10 Christmas, 10 New Year), public
domain / CC0 from Commons, named `<holiday>-NN.jpg`. Their manifest entries
have `holiday` (`halloween`, `thanksgiving`, `christmas` or `newyear`) and
`time`, and no `weather`. The app shows them only in their season (the four
weeks before each holiday, and Dec 26-31 for New Year; see `holidayNow` in
`app/js/home.js`), alongside the sky-matched photos.

Made the same way: `search.py < holiday-queries.txt`, contact sheets by eye
(no people in focus, no paintings), then
`fetch-seasonal.py candidates.json holiday-picks.txt raw/` (picks are
candidate indexes from the two search runs, merged) and `resize.py`. The
Pi's manifest from before them is kept as `manifest.before-holidays.json`
next to it.

## Monthly photos

31 more for October to January (8, 8, 7 and 8), named `<month>-NN.jpg`:
Eastern Sierra aspens and a full moon in October, late fall colour, fog and
migrating geese in November, Tahoe and Sierra snow and frost in December,
Yosemite snow, a frozen lake and the winter coast (murres, sea lions, a
harbor seal) in January. Entries have `month` (e.g. `[12]`) and `time`. The
app adds a month's photos in that month only, and only those tagged for the
current time of day. Made with `search.py < month-queries.txt`, then
`fetch-seasonal.py candidates.json month-picks.txt raw/` and `resize.py`
(candidates already in the manifest were dropped first). The Pi's manifest
from before them is `manifest.before-months.json`. To add another month,
add queries and picks the same way.
