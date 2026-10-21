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
