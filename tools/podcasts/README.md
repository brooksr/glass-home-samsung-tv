# Roche Home podcasts

The podcast lists for the TV's Podcasts tile (`app/js/podcasts.js`) and the
Android app (`android/`).

- `export.txt`: the subscriptions export this list was made from (shows,
  publishers and hosts mixed together).
- `sources.json`: the 119 shows, each with Apple's collection ID (`id`), feed
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
  library only; about 30 s for all 119.

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

## Ad-free copies

`adfree.py` (on the laptop beside `build.py`) finds the ad breaks in episodes
and writes copies without them: `$HA_WWW/roche-home/podcasts/adfree/<episode>.mp3`
and `adfree/index.json` (each episode's cuts, in the original's seconds, and
"maybe" sponsor reads it left in, in the copy's). It runs all the time as the
`roche-adfree` service and checks Home Assistant every minute:

- **Episodes being listened to** (touched in `sensor.roche_podcasts` within 20
  minutes) are done straight away, in about 30–90 s, well before the first
  mid-roll; the TV and phone then switch to the copy at the same place.
- **New episodes of the shows you listen to regularly** (2 or more episodes in
  60 days, or listed in `adfree.json` as `{"always": [show ids]}`; `"never"`
  leaves shows alone) are done when `build.py` first lists them (under 10 days
  old, unplayed), so they start ad-free.

How it finds ads, without transcribing anything:

1. **Several copies.** The episode is downloaded four times with different
   apps' user agents. Inserted ads differ between copies and the show doesn't,
   so lining up their Chromaprint fingerprints shows exactly where the breaks
   are. A stretch counts as an ad when two of the other three copies lack it (one
   copy often shares an ad with the first). Only gaps between solid stretches
   of matched show (2 min or more) count: quiet or musical audio fingerprints
   badly. Tested on The Vergecast (4 breaks, 271 s, all exact), The David
   Pakman Show (pre- and post-roll) and Earth Ancients (a long music tail is
   left alone).
2. **The ad library** (`adfree-state/lib/`): every ad found is kept as a
   fingerprint and cut wherever it turns up again, on any show, including ads
   recorded into an episode that every copy has.
3. **Other shows**: a 10 s–2.5 min stretch also found in a recent episode of a
   different show is a bought ad (or a promo) and is cut.
4. **What you skip**: two or more +30 s presses in a row (45 s to 5 min in all)
   are reported by the TV and phone to `sensor.roche_podcasts_skips`. The
   stretch goes into the library as a candidate and is cut when it turns up
   in a second episode; where in the episode you skip teaches each show's
   usual ad slots. Pressing ⏩ / "Skip sponsor" on a flagged read reports it too.
5. **Sponsor reads** the host records: phrases in the show's own transcript
   ("brought to you by", "promo code", "dot com slash", "commercial break",
   phone numbers...), the show's bumpers (a sound found at the edges of breaks
   in two or more episodes), a jump in loudness at both ends, and a learned slot,
   scored together. A high score is cut; a middling one is offered as "maybe":
   the TV says *Sponsor message? ⏩ skips it*, the phone shows **Skip sponsor**.

Progress: an entry whose 6th item is 1 is in the copy's seconds (see
[Listening progress](#listening-progress)). An episode started before its copy
existed is converted when the copy first plays. Away from home (Home
Assistant not answering) the phone plays the original, ads and all: skipping
the copy's cut times in the original was tried and missed, since the host
inserts different ads of different lengths each time. An episode queued as the
original switches to its copy as soon as it plays at home.

Each episode is downloaded four times, so its host counts four downloads.
Copies are deleted 3 days after an episode is played, or oldest first past
6 GB; the 40 newest originals are kept in `adfree-state/orig/` so episodes
can be redone when the library learns something (not ones already started).

Needs `sudo apt install ffmpeg libchromaprint-tools`. Set up on the laptop:

```
scp tools/podcasts/adfree.py "$HA_SSH:roche-podcasts/"
# HA_URL=http://localhost:8123 and HA_TOKEN=<a long-lived token>, readable only by you:
ssh "$HA_SSH" 'umask 077; cat > roche-podcasts/adfree.env'
# /etc/systemd/system/roche-adfree.service (User=broche, Nice=10, IOSchedulingClass=idle):
#   ExecStart=/usr/bin/python3 -u /home/broche/roche-podcasts/adfree.py /home/broche/homeassistant/www/roche-home/podcasts
sudo systemctl enable --now roche-adfree
```

`journalctl -u roche-adfree -f` shows each episode as it's done;
`python3 adfree.py OUT status` lists what's been cut and what's been learned;
`python3 adfree.py OUT once <episode id>` does one episode now.

## Second pass: transcripts, sponsor reads, chapters

`deep.py` (run by `adfree.py`) transcribes each processed episode as the players hear it (the
ad-free copy) and has a model mark the ads still in it and the chapters:

| | Episode being listened to | The rest (nightly, 3:00–6:00) | M5 can't be woken |
|---|---|---|---|
| Transcribe | M5: MLX Whisper large-v3-turbo (~40–75× real time) | same | laptop: faster-whisper `base.en` (~19×) / `small.en` (~7.5×), `whisper_cpu.py` |
| Read it | M5: Qwen3.6-35B-A3B (~30 s) | M5: Qwen3.8-27B (~3 min) | Claude Haiku via `claude -p` (only when someone's listening, or after 3 failed nights) |

The model's ads go into the index as `skip` (host reads, produced ads, promos: the TV and phone
jump over them by themselves, saying so on the TV) and self-promotion as `maybe` (offered, as
before). Chapters and the transcript go into `adfree/<id>.extras.json` (`x: 1`), which both
players use instead of the feed's. A player with an episode still waiting checks the index every
30 s for 40 minutes, so a second pass that finishes mid-listen applies from there on. Produced
ads and promos also go into the fingerprint library, so the first pass cuts them elsewhere.

First runs (2026-11-11): Pakman 76 min, 4 sponsor reads (307 s) found, 113 s in all (79 s
transcribing, 33 s reading); Pod Save the World, 6 reads (381 s), 110 s. Each second pass is a
`"what": "deep"` line in `adfree-state/metrics.jsonl` (where it was transcribed, which model read
it, tokens, seconds). `python3 adfree.py OUT deep <episode id> [now]` redoes one.

The laptop's Whisper lives in its own Python 3.12 environment, `~/roche-podcasts/.venv`
(`uv venv --python 3.12 .venv && uv pip install --python .venv/bin/python faster-whisper`).
On the M5, `mlx-whisper` is in `~/Projects/local_llm/.venv` and the model in `models/hf`;
`m5-whisper.py` is installed there as `podcast-bench/whisper.py`.

## The M5's local models

`m5.py` (on the laptop) reaches the Qwen models on the M5 MacBook (`~/Projects/local_llm`
there) for reading transcripts: Qwen3.6-35B-A3B (fast, ~10–40 s an episode) and Qwen3.8-27B
(~2.5–4 min, closest to Claude in the 2026-11-11 bench; see `llm_bench.py`). The laptop's key
is trusted on the M5 only for `m5-remote.sh` (installed there as
`~/Projects/local_llm/podcast-bench/remote.sh`): from the laptop's addresses, no forwarding,
no terminal; it can ping, run one request on one of the two models, or unload. The model loads
on demand and unloads after 10 idle minutes; while loaded, `caffeinate -i -s` keeps the Mac awake.

Waking: connecting wakes the sleeping M5 ("Wake for network access" is on and it's on AC):
tested 2026-11-11, it woke in 0.3 s from sleep and finished an 82 s run without dropping off.
A Wake-on-LAN packet is the second try; after 45 s without an answer the caller falls back
to Claude. It also wakes every night at 3:00 (`pmset repeat wakeorpoweron MTWRFSU 03:00:00`;
`sudo pmset repeat cancel` removes it). Every wake and run goes into `adfree-state/m5.jsonl`.

To undo on the M5: remove the `roche-adfree` line from `~/.ssh/authorized_keys` (a backup
from before is beside it) and cancel the repeat.

## Listening progress

Both apps store progress locally and share it through the Home Assistant
entity `sensor.roche_podcasts`. Its `progress` attribute maps episode ID
(first 12 hex digits of the SHA-1 of the feed's `<guid>`, or of the audio URL
when there's none) to `[seconds, duration, played 0/1, updated unix time,
show ID, ad-free 0/1]` (1: the seconds are in the ad-free copy's time). Each device keeps the newer entry per episode and writes back.
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
Here's the Deal, The Free Press (its shows besides
Conversations with Coleman), Seaquence and itmeJP's other shows. Picked by
judgment where the name was ambiguous: Locked On Giants (San Francisco),
PSIcoactivo (KGRA), Open Minds UAP News (the current name of Open Minds UFO
Radio), The Intelligence from The Economist (Spotify's "Economist Podcasts" feed).

Paid feeds (Audible, members-only Making Sense, Hardcore History back
catalogue, The Free Press) are the free public versions; a private feed URL
from your subscription can replace one in `sources.json`.
