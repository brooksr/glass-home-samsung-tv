# Roche Home podcasts

The podcast lists for the TV's Podcasts tile (`app/js/podcasts.js`) and the
Android app (`android/`).

- `export.txt`: the subscriptions export this list was made from (shows,
  publishers and hosts mixed together).
- `sources.json`: the 110 shows, each with Apple's collection ID (`id`), feed
  URL, cover art and `category` (chosen by hand: Apple's genres put Coast to
  Coast AM under Science). `"id3chapters": true` marks the shows whose MP3s
  carry chapters (Decoding the Gurus, Lex Fridman, ShopTalk, Web Rush); found
  by checking every show's newest MP3 for ID3 CHAP frames. Edit it by hand to add or remove a show; any unique text
  works as `id`. Look a show's feed up with
  `https://itunes.apple.com/search?media=podcast&term=NAME`.
- `build.py sources.json OUT`: reads every feed (12 at a time) and writes
  `OUT/podcasts.json` (the shows and their 3 newest episodes),
  `OUT/shows/<id>.json` (the newest 100) and `OUT/art/<id>.jpg` (300 px).
  It skips video episodes, and a feed that fails keeps its last file.
  `OUT/extras/<episode>.json` holds the chapters and transcript of each
  show's newest 10 episodes, for the TV, when the feed publishes them
  (Podcasting 2.0 `podcast:chapters` / `podcast:transcript`, or ID3 chapters
  for `id3chapters` shows). Subtitle cues are joined into sentences with the
  speaker noted where it changes. Each is made once; `<episode>.none` marks
  episodes with nothing, or whose host refused (MonsterTalk's transcript host
  answers 403). Transcripts are only ever the
  show's own; nothing is transcribed here. Standard
  library only; about 30 s for all 110.

- YouTube: `"youtube": {"channel", "handle", "only"}` on 26 shows. Each
  channel was checked by matching its recent videos to the show's episodes;
  news-organization channels with no matches (Vox, Reason, The Economist, NYT)
  and a look-alike "Lovett or Leave itt" channel were left out. `build.py`
  reads each channel's own feed (newest 15 videos, Shorts skipped), keeps the
  last 60 in `OUT/youtube/<id>.json`, gives matching episodes `v` (video ID)
  and, with `only`, adds the rest as `yt: 1` items without audio.
- `news.json`: the feeds for the Android app's News tab (category, name,
  RSS or Atom URL), bundled into the app at build time; the phone reads the
  sites directly. Rebuild the app after editing it.

None of the 110 podcast feeds carries video episodes (checked 2026-11-01).

## On the Home Assistant laptop

`build.py` and `sources.json` live in `~/roche-podcasts` on the laptop
(`HA_SSH` in `.env`), writing into `$HA_WWW/roche-home/podcasts/`, which HA
serves at `$HA_URL/local/roche-home/podcasts/`. To refresh every 30 minutes,
add this with `crontab -e` on the laptop:

```
*/30 * * * * cd $HOME/roche-podcasts && python3 build.py sources.json /home/broche/homeassistant/www/roche-home/podcasts > build.log 2>&1
```

After editing `sources.json`: `scp tools/podcasts/sources.json "$HA_SSH:roche-podcasts/"`
(quote it: in zsh, `$HA_SSH:r…` is read as a modifier). Rebuild the Android app
too if you want its built-in fallback list to match.

## Listening progress

Both apps store progress locally and share it through the Home Assistant
entity `sensor.roche_podcasts`. Its `progress` attribute maps episode ID
(first 12 hex digits of the SHA-1 of the feed's `<guid>`, or of the audio URL
when there's none) to `[seconds, duration, played 0/1, updated unix time,
show ID]`. Each device keeps the newer entry per episode and writes back.
The entity keeps the 400 most recent entries from the last 180 days. A state
set this way disappears when Home Assistant restarts; the next device to sync
puts it back.

## Left out of the export

Publishers and hosts (WNYC Studios, Vox Media, NPR, Sam Harris…) have no feed
of their own; their shows are in the list. YouTube-only entries ("Auto
playlist", "Episodes for Later", "Season 2", the single Jonathan Reed
episode) aren't podcasts. Not found in Apple's directory, so add their feed
URLs by hand if you want them: Skullduggery (Yahoo News), The Adam Savage
Project, The alientheorists's Podcast, The Bulwark Podcast, GIANTS POSTCASTS,
Economist Podcasts, Here's the Deal, The Free Press (its shows besides
Conversations with Coleman), Seaquence and itmeJP's other shows. Picked by
judgment where the name was ambiguous: Locked On Giants (San Francisco),
PSIcoactivo (KGRA), Open Minds UAP News (the current name of Open Minds UFO
Radio), The Other Side of Midnight with Walter Sterling.

Paid feeds (Audible, members-only Making Sense, Hardcore History back
catalogue, The Free Press) are the free public versions; a private feed URL
from your subscription can replace one in `sources.json`.
