#!/usr/bin/env python3
"""Roche Home podcasts: find the ad breaks in episodes and make ad-free copies.

    python3 adfree.py OUT                 run: watch Home Assistant and process episodes
    python3 adfree.py OUT once EPISODE_ID process one episode now (then exit)
    python3 adfree.py OUT deep EPISODE_ID [now]  its second pass now ("now": as if it were playing)
    python3 adfree.py OUT status          what has been processed and learned

OUT is build.py's folder (shows/*.json, extras/*.json are read from it). This writes
OUT/adfree/<episode id>.mp3 (the episode without its ads) and OUT/adfree/index.json,
which the TV (app/js/podcasts.js) and the phone (android/) read:

  {"v": 1, "eps": {"<id>": {"at": unix s, "f": 1 if <id>.mp3 exists, "d": its duration,
                           "o": the original's duration,
                           "cuts":  [[start, end, kind], ...]   seconds in the original, removed
                           "maybe": [[start, end, kind], ...]   seconds in the ad-free copy,
                                                                 likely sponsor reads left in
                                                                 (the players offer to skip them)
                           "skip":  [[start, end, kind], ...]   ...ads found by the second pass,
                                                                 which the players skip themselves
                           "x": 1}}}                            adfree/<id>.extras.json: chapters
                                                                 and transcript, in the same time

Nothing is transcribed. The signals, strongest first:
  dai     Download the episode four times with different apps' user agents. Ads
          inserted by the host differ between copies; the show itself doesn't.
          Fingerprints (Chromaprint's fpcalc) are lined up, and what's in the first
          copy but missing from at least two others is an ad.
  known   Every ad found is kept in a fingerprint library; a stretch of a new episode
          that matches one is an ad, on any show.
  shared  A stretch (10 s to 2.5 min) that also appears in a recent episode of a
          different show: the same creative bought on both.
  skip    Where you skip (two or more +30 s presses; the apps report them through
          sensor.roche_podcasts_skips). What you skipped is remembered; when it turns
          up in another episode it's cut there. Where you skip, as a share of the
          episode, also teaches each show's usual ad slots.
  host    Sponsor reads in the show's own transcript ("brought to you by", "promo
          code", "dot com slash"...), its bumpers (a sound found at the edges of
          several breaks) and a jump in loudness at both ends, scored together;
          a high score is cut, a middling one is offered as "maybe".

Second pass (deep.py): each episode is then transcribed and a model marks its remaining
ads (sponsor reads the host records) and its chapters, in the time of the audio the players
hear. Those ads go into the index as "skip" (the players skip them by themselves), self-promotion
as "maybe"; chapters and the transcript into adfree/<id>.extras.json ("x": 1). Episodes being
listened to get it straight away (the M5's fast model, or Claude Haiku if the M5 can't be woken);
the rest wait for the nightly batch at 3:00-6:00, when the M5 wakes (its better model).

Episodes queued on the phone (Play, Play next, Add to Up next) and not yet done are asked for
through sensor.roche_podcasts_requests ({episode id: unix time}); they come right after what's
playing, both passes.

Which episodes: any episode started on the TV or phone (seen in sensor.roche_podcasts
within 20 minutes), straight away; and new episodes (under 10 days old, unplayed) of
the shows you listen to regularly (2+ episodes in 60 days, or listed in adfree.json
"always"), ahead of time. adfree.json "never" lists shows to leave alone.

Needs ffmpeg and fpcalc (apt install ffmpeg libchromaprint-tools); otherwise
standard library only. HA_URL and HA_TOKEN come from the environment or adfree.env
beside this script. State (fingerprints, the library, kept originals) lives in
adfree-state/ beside this script, or ADFREE_STATE.
"""
import array
import json
import os
import re
import resource
import shutil
import subprocess
import sys
import tempfile
import time
import traceback
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
STEP = 4096 / 3 / 11025            # seconds per fingerprint item (fpcalc's default algorithm)
UAS = ['AppleCoreMedia/1.0.0.21E236 (iPhone; U; CPU OS 17_4 like Mac OS X; en_us)',
       'Overcast/3.0 (+http://overcast.fm/; iOS podcast app)',
       'Pocket Casts',
       'Spotify/8.9 iOS']
PLAYING_WINDOW = 20 * 60           # an episode touched this recently is being listened to
REGULAR_DAYS = 60
NEW_DAYS = 10
KEEP_ORIGINALS = 40                # originals kept for reprocessing (newest first)
KEEP_EPISODE_FPS = 200
CROSS_SHOW_EPS = 40                # recent episodes of other shows compared against
MAX_CLEAN_BYTES = 6 * 1024 ** 3
INDEX_KEEP = 400
MAX_ATTEMPTS = 3

try:
    (0).bit_count()

    def pc(x):
        return x.bit_count()
except AttributeError:            # Python < 3.10
    def pc(x):
        return bin(x).count('1')


def log(*a):
    print(time.strftime('%Y-%m-%d %H:%M:%S'), *a, flush=True)


class Meter:
    """Time, CPU and bytes per stage of one episode, for state/metrics.jsonl.

    CPU is this process plus the tools it runs (ffmpeg, fpcalc). Energy is estimated from
    it: adfree.json "watts_per_cpu_second" (the laptop's CPU package, above idle, per busy
    thread; measure with RAPL and update) and "kwh_price" ($ per kWh). The raw seconds are
    kept, so the estimates can be redone when those numbers change."""

    def __init__(self):
        self.t0 = self.t = time.time()
        self.c0 = self.c = self.cpu()
        self.stages = {}
        self.bytes = 0

    @staticmethod
    def cpu():
        a, b = resource.getrusage(resource.RUSAGE_SELF), resource.getrusage(resource.RUSAGE_CHILDREN)
        return a.ru_utime + a.ru_stime + b.ru_utime + b.ru_stime

    def stage(self, name):
        now, c = time.time(), self.cpu()
        wall, cpu = self.stages.get(name, (0, 0))
        self.stages[name] = (round(wall + now - self.t, 2), round(cpu + c - self.c, 2))
        self.t, self.c = now, c

    def total(self, cfg):
        cpu = self.cpu() - self.c0
        wh = cpu * cfg.get('watts_per_cpu_second', 4.5) / 3600
        return {'wall_s': round(time.time() - self.t0, 1), 'cpu_s': round(cpu, 1), 'stages': self.stages,
                'mb_down': round(self.bytes / 1e6, 1), 'est_wh': round(wh, 3),
                'est_usd': round(wh / 1000 * cfg.get('kwh_price', 0.35), 5)}


def items(sec):
    return int(round(sec / STEP))


def secs(n):
    return n * STEP


# ---------- files ----------

def read_json(path, default=None):
    try:
        with open(path, encoding='utf-8') as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def read_jsonl(path):
    try:
        with open(path, encoding='utf-8') as f:
            return [json.loads(l) for l in f if l.strip()]
    except (OSError, ValueError):
        return []


def write_json(path, data):
    tmp = path + '.tmp'
    with open(tmp, 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, separators=(',', ':'))
    os.replace(tmp, path)


def save_fp(path, fp):
    tmp = path + '.tmp'
    with open(tmp, 'wb') as f:
        array.array('I', fp).tofile(f)
    os.replace(tmp, path)


def load_fp(path):
    a = array.array('I')
    try:
        with open(path, 'rb') as f:
            a.frombytes(f.read())
    except OSError:
        return None
    return a.tolist()


def env_file():
    for line in open(os.path.join(HERE, 'adfree.env'), encoding='utf-8') if os.path.exists(os.path.join(HERE, 'adfree.env')) else []:
        m = re.match(r'^\s*(?:export\s+)?([A-Z_]+)\s*=\s*(.*?)\s*$', line)
        if m and m.group(1) not in os.environ:
            os.environ[m.group(1)] = m.group(2).strip('"\'')


# ---------- audio tools ----------

def run(cmd, timeout=900):
    return subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout, check=False)


def fingerprint(path):
    r = run(['fpcalc', '-raw', '-json', '-length', '0', path])
    if r.returncode == 0:
        return json.loads(r.stdout)['fingerprint']
    # fpcalc gives up at a damaged frame (some hosts' MP3s have one); ffmpeg decodes past it.
    dec = subprocess.Popen(['ffmpeg', '-v', 'quiet', '-err_detect', 'ignore_err', '-i', path, '-vn', '-ac', '1', '-ar', '11025',
                            '-f', 's16le', '-'], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    try:
        r = subprocess.run(['fpcalc', '-raw', '-json', '-length', '0', '-format', 's16le', '-rate', '11025', '-channels', '1', '-'],
                           stdin=dec.stdout, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=900, check=False)
    finally:
        dec.stdout.close()
        dec.wait()
    if r.returncode != 0:
        raise RuntimeError('fpcalc: ' + r.stderr.decode('utf-8', 'replace')[-300:])
    return json.loads(r.stdout)['fingerprint']


def probe(path):
    r = run(['ffprobe', '-v', 'error', '-select_streams', 'a:0', '-show_entries', 'stream=codec_name:format=duration',
             '-of', 'json', path], timeout=120)
    j = json.loads(r.stdout or b'{}')
    codec = (j.get('streams') or [{}])[0].get('codec_name', '')
    return codec, float((j.get('format') or {}).get('duration') or 0)


def levels(path):
    """RMS level (dB) of every half second."""
    r = run(['ffmpeg', '-v', 'error', '-i', path, '-vn', '-af',
             'aresample=8000,aformat=channel_layouts=mono,asetnsamples=n=4000:p=0,'
             'astats=metadata=1:reset=1,ametadata=print:key=lavfi.astats.Overall.RMS_level:file=-',
             '-f', 'null', '-'])
    out = []
    for m in re.finditer(rb'RMS_level=(-?[\d.]+|-inf)', r.stdout):
        v = m.group(1)
        out.append(-90.0 if v == b'-inf' else max(-90.0, float(v)))
    return out


def download(url, path, ua, meter=None):
    req = urllib.request.Request(url, headers={'User-Agent': ua, 'Accept': '*/*'})
    tmp = path + '.part'
    with urllib.request.urlopen(req, timeout=60) as r, open(tmp, 'wb') as f:
        shutil.copyfileobj(r, f, 1024 * 1024)
    os.replace(tmp, path)
    if meter:
        meter.bytes += os.path.getsize(path)


def cut(src, cuts, dur, codec, dest):
    """Write src without the [start, end] cuts to dest (MP3)."""
    keep, at = [], 0.0
    for s, e in cuts:
        if s > at + 0.05:
            keep.append((at, s))
        at = max(at, e)
    if dur > at + 0.05:
        keep.append((at, dur))
    with tempfile.NamedTemporaryFile('w', suffix='.txt', delete=False) as lst:
        for s, e in keep:
            lst.write("file '%s'\ninpoint %.3f\noutpoint %.3f\n" % (src.replace("'", "'\\''"), s, e))
    tmp = dest + '.part.mp3'
    codec_args = ['-c:a', 'copy'] if codec == 'mp3' else ['-c:a', 'libmp3lame', '-b:a', '96k']
    try:
        r = run(['ffmpeg', '-v', 'error', '-y', '-f', 'concat', '-safe', '0', '-i', lst.name, '-map', '0:a',
                 '-map_metadata', '-1'] + codec_args + [tmp])
        if r.returncode != 0:
            raise RuntimeError('ffmpeg: ' + r.stderr.decode('utf-8', 'replace')[-300:])
        os.replace(tmp, dest)
    finally:
        os.unlink(lst.name)
        if os.path.exists(tmp):
            os.unlink(tmp)


# ---------- fingerprint matching ----------

def index(fp, step=1, maxcount=40):
    """value -> positions. Values found very often (silence, hum) are left out."""
    idx = {}
    for j in range(0, len(fp), step):
        idx.setdefault(fp[j], []).append(j)
    return {v: p for v, p in idx.items() if len(p) <= maxcount}


def segments(a, b, idx, minlen, maxerr=10, gap=8, minseeds=2):
    """Stretches where a matches b: [(start in a, start in b, length)], all in items.

    Exact matches of fingerprint values seed an offset; from each seed the match is
    grown both ways while values differ in at most maxerr of 32 bits (with up to
    `gap` worse ones in a row)."""
    seeds = {}
    for i, v in enumerate(a):
        ps = idx.get(v)
        if ps:
            for j in ps:
                seeds.setdefault(j - i, []).append(i)
    out = []
    na, nb = len(a), len(b)
    for o, starts in seeds.items():
        if len(starts) < minseeds:
            continue
        done_to = -1
        for i in starts:
            if i < done_to:
                continue
            last, k = i, i - 1
            while k >= 0 and k + o >= 0 and last - k <= gap:
                if pc(a[k] ^ b[k + o]) <= maxerr:
                    last = k
                k -= 1
            s = last
            last, k = i, i + 1
            while k < na and k + o < nb and k - last <= gap:
                if pc(a[k] ^ b[k + o]) <= maxerr:
                    last = k
                k += 1
            e = last + 1
            done_to = e
            # Real audio: not a stretch of near-identical values (silence matches silence).
            if e - s >= minlen and len(set(a[s:e])) >= 0.5 * (e - s):
                out.append((s, s + o, e - s))
    # Neighbouring offsets (the two files' frames don't line up exactly) find the same stretch.
    out.sort(key=lambda x: -x[2])
    kept = []
    for s, j, n in out:
        dup = False
        for ks, kj, kn in kept:
            ov = min(s + n, ks + kn) - max(s, ks)
            if ov > 0.8 * n and abs((j - s) - (kj - ks)) <= 24:
                dup = True
                break
        if not dup:
            kept.append((s, j, n))
    return kept


def chain(segs, tol=16):
    """The longest run of segments in order in both files: the show's own audio."""
    segs = sorted(segs)
    best, prev = [], []
    for k, (s, j, n) in enumerate(segs):
        b, p = n, -1
        for m in range(k):
            ms, mj, mn = segs[m]
            if ms + mn <= s + tol and mj + mn <= j + tol and ms < s and best[m] + n > b:
                b, p = best[m] + n, m
        best.append(b)
        prev.append(p)
    if not best:
        return []
    k = max(range(len(best)), key=lambda x: best[x])
    out = []
    while k >= 0:
        out.append(segs[k])
        k = prev[k]
    return out[::-1]


def gaps(cover, total, mingap):
    """Stretches of [0, total) not covered by the (start, length) list, at least mingap long."""
    out, at = [], 0
    for s, n in sorted(cover):
        if s - at >= mingap:
            out.append((at, s))
        at = max(at, s + n)
    if total - at >= mingap:
        out.append((at, total))
    return out


def merge(ranges, join=3.0):
    out = []
    for r in sorted(ranges, key=lambda x: x[0]):
        if out and r[0] <= out[-1][1] + join:
            kinds = out[-1][2].split('+')
            out[-1] = [out[-1][0], max(out[-1][1], r[1]), '+'.join(kinds + [k for k in r[2].split('+') if k not in kinds])]
        else:
            out.append([r[0], r[1], r[2]])
    return out


def breaks_between(a, b, path):
    """Where a and b differ between the stretches they share: ([(start, end) in a], [(start, end) in b]).

    Only trusted between solid stretches (2 min or more, or the very start of both
    files): quiet or musical audio fingerprints badly and matches only in scraps, and
    the spaces between scraps aren't ads. Where the solid stretches on either side
    line up at the same offset, the audio in between is compared directly: different
    ads differ in about half their bits, the show's own audio far less."""
    solid = [(s, j, n) for s, j, n in path if n >= items(120) or (s <= items(1) and j <= items(1) and n >= items(10))]
    bounds = [(0, 0, 0)] + solid + [(len(a), len(b), 0)]
    ga, gb = [], []
    for (ls, lj, ln), (rs, rj, rn) in zip(bounds, bounds[1:]):
        a0, a1, b0, b1 = ls + ln, rs, lj + ln, rj
        if a1 - a0 > items(360) or b1 - b0 > items(360):
            continue                                     # not an ad break: too long to trust
        if a1 - a0 >= items(8) and abs((lj - ls) - (rj - rs)) < items(1):
            o = lj - ls
            ks = [k for k in range(a0, a1) if 0 <= k + o < len(b)]
            if ks and sum(pc(a[k] ^ b[k + o]) for k in ks) / len(ks) < 13:
                continue                                 # the same audio, just badly matched
        if a1 - a0 >= items(8):
            ga.append((a0, a1))
        if b1 - b0 >= items(8):
            gb.append((b0, b1))
    return ga, gb


def distinct(fp):
    """fp is something one could recognise: not near-silence, and not a loop (music
    that repeats itself would match all through an episode)."""
    if len(set(fp)) < 0.6 * len(fp):
        return False
    return not any(abs(j - s) >= items(3) for s, j, n in segments(fp, fp, index(fp), items(5)))


# ---------- the fingerprint library ----------

class Library:
    """Ads (and candidates) as fingerprints: state/lib.json + state/lib/<n>.fp.

    kind: ad      an ad; matched anywhere
          skip    something you skipped; an ad once it turns up in a second episode
          edge    5 s either side of a break; a show's bumper once found in a second episode
    """

    def __init__(self, state):
        self.dir = os.path.join(state, 'lib')
        os.makedirs(self.dir, exist_ok=True)
        self.path = os.path.join(state, 'lib.json')
        self.meta = read_json(self.path, {'next': 1, 'version': 0, 'entries': {}})
        self._fps = {}

    def save(self):
        write_json(self.path, self.meta)

    def fp(self, key):
        if key not in self._fps:
            self._fps[key] = load_fp(os.path.join(self.dir, key + '.fp')) or []
        return self._fps[key]

    def entries(self, kinds, show=None, other_show=None):
        for key, e in self.meta['entries'].items():
            if e['kind'] in kinds and (show is None or e['show'] == show) and (other_show is None or e['show'] != other_show):
                yield key, e

    def bump(self):
        self.meta['version'] += 1

    def add(self, kind, fp, show, ep, **extra):
        """Adds fp unless the library already has it (then that entry is seen again)."""
        if len(fp) < 24:
            return None
        if kind in ('ad', 'skip') and not distinct(fp):
            return None
        known = self.find(fp, [kind, 'ad'] if kind != 'edge' else ['edge'], minlen=min(len(fp) // 2, items(8)))
        for key, s, n in known:
            if n >= 0.7 * len(fp):
                e = self.meta['entries'][key]
                if ep not in e['eps']:
                    e['eps'] = (e['eps'] + [ep])[-10:]
                e['seen'] = int(time.time())
                return key
        key = str(self.meta['next'])
        self.meta['next'] += 1
        save_fp(os.path.join(self.dir, key + '.fp'), fp)
        self._fps[key] = list(fp)
        e = {'kind': kind, 'show': show, 'eps': [ep], 'len': round(secs(len(fp)), 1), 'seen': int(time.time())}
        e.update(extra)
        self.meta['entries'][key] = e
        if kind == 'ad':
            self.bump()
        return key

    def find(self, a, kinds, minlen, show=None, other_show=None, maxerr=10):
        """Where entries of these kinds match in a: [(key, start in a, length)]."""
        keys, big, starts = [], [], []
        for key, _ in self.entries(kinds, show, other_show):
            fp = self.fp(key)
            if not fp:
                continue
            keys.append(key)
            starts.append(len(big))
            big.extend(fp)
            big.extend([0x55555555, 0xAAAAAAAA] * 10)   # nothing matches across entries
        if not big:
            return []
        out = []
        for s, j, n in segments(a, big, index(big), minlen, maxerr=maxerr):
            k = max(i for i in range(len(starts)) if starts[i] <= j)
            out.append((keys[k], s, n))
        return out

    def prune(self, days=150):
        cutoff = time.time() - days * 86400
        for key in [k for k, e in self.meta['entries'].items() if e['seen'] < cutoff]:
            del self.meta['entries'][key]
            self._fps.pop(key, None)
            try:
                os.unlink(os.path.join(self.dir, key + '.fp'))
            except OSError:
                pass


# ---------- transcripts ----------

AD_PHRASES = [
    r"brought to you by", r"sponsored by", r"(this|today'?s) (episode|show|podcast)( is|'s)? (sponsored|supported|brought|presented)",
    r"support for (this|the) (show|podcast|episode) comes from", r"\bour sponsors?\b", r"today'?s sponsor", r"promo code",
    r"\buse (the )?code\b", r"\bcode [A-Z0-9]{3,}\b", r"\b[a-z0-9-]+\.(com|co|io|org)/[a-z0-9]+", r"dot com slash",
    r"\bfree trial\b", r"(\d+|ten|fifteen|twenty|thirty|forty|fifty) ?(%|percent) off", r"first (order|month|box|purchase)",
    r"terms (and conditions )?apply", r"\bgo to [a-z0-9-]+\.(com|co|io)", r"\bvisit [a-z0-9-]+\.(com|co|io)", r"sign up (today|now)",
    r"download the [a-z0-9 ]{1,20} app", r"\blimited time\b", r"money[- ]back guarantee", r"\bfree shipping\b",
    r"commercial break", r"sponsors? to identify", r"\b1[- ]?8[0-9]{2}[- ][0-9]{3}[- ][0-9]{4}\b", r"\bcall (now|today)\b",
]
BREAK_START = r"(we'?ll be right back|commercial break|after (the|this) (break|short break|word from)|let'?s take a (quick |short )?break|a quick break)"
BREAK_END = r"^(and )?(we'?re|we are|i'?m) back\b|^welcome back\b"
PHRASES = [re.compile(p, re.I) for p in AD_PHRASES]


def transcript_ranges(extras, clean_dur):
    """Sponsor reads in the show's transcript: [(start, end, score)] in the transcript's
    time, which is the show's own (no inserted ads): the ad-free copy's time."""
    tr = (extras or {}).get('transcript') or {}
    lines = tr.get('lines') or []
    if not tr.get('timed') or len(lines) < 10:
        return []
    if lines[-1]['t'] > clean_dur + 60:      # timed against some other cut of the audio
        return []
    ends = [lines[i + 1]['t'] if i + 1 < len(lines) else lines[i]['t'] + 10 for i in range(len(lines))]
    hits = []
    for i, ln in enumerate(lines):
        found = set(k for k, p in enumerate(PHRASES) if p.search(ln['x']))
        if found:
            hits.append((i, found))
    out = []
    k = 0
    while k < len(hits):
        i0, found = hits[k]
        i1 = i0
        k += 1
        while k < len(hits) and lines[hits[k][0]]['t'] - ends[i1] < 60 and lines[hits[k][0]]['t'] - lines[i0]['t'] < 240:
            i1 = hits[k][0]
            found |= hits[k][1]
            k += 1
        s, e = lines[i0]['t'], ends[i1]
        if e - s >= 10:
            out.append((s, e, min(6, 2 * len(found))))
    # "We'll be right back" ... "and we're back": a break the show takes itself.
    for i, ln in enumerate(lines):
        if re.search(BREAK_START, ln['x'], re.I):
            for j in range(i + 1, len(lines)):
                if lines[j]['t'] - ends[i] > 300:
                    break
                if re.search(BREAK_END, lines[j]['x'], re.I):
                    if lines[j]['t'] - ends[i] >= 10:
                        out.append((ends[i], lines[j]['t'], 3))
                    break
    return out


def ad_start(text_lines, start, sponsor):
    """Where an ad found by a model really starts. The model gives the time of a line, and a line
    can begin with the show before the read: if the line it chose neither names the sponsor nor
    sounds like an ad, the start moves on to the first line (within 25 s) that does. A few seconds
    of ad heard beats a sentence of the show skipped."""
    words = [w for w in re.findall(r'[a-z0-9]{4,}', sponsor.lower()) if w not in ('sponsor', 'unknown', 'show', 'podcast')]

    def adlike(x):
        x = x.lower()
        return any(w in x for w in words) or any(p.search(x) for p in PHRASES) or 'sponsor' in x
    following = [l for l in text_lines if start - 1 <= l['t'] <= start + 25]
    if not following or adlike(following[0]['x']):
        return start
    for l in following[1:]:
        if adlike(l['x']):
            return float(l['t'])
    return start


# ---------- the work ----------

class AdFree:
    def __init__(self, out):
        self.out = out
        self.dest = os.path.join(out, 'adfree')
        self.state = os.environ.get('ADFREE_STATE') or os.path.join(HERE, 'adfree-state')
        for d in (self.dest, self.state, os.path.join(self.state, 'orig'), os.path.join(self.state, 'eps')):
            os.makedirs(d, exist_ok=True)
        self.lib = Library(self.state)
        self.meta_path = os.path.join(self.state, 'episodes.json')
        self.meta = read_json(self.meta_path, {})       # id -> what was done
        self.learn_path = os.path.join(self.state, 'learned.json')
        self.learned = read_json(self.learn_path, {'slots': {}, 'skips_seen': []})
        self.index_path = os.path.join(self.dest, 'index.json')
        self.idx = read_json(self.index_path, {'v': 1, 'eps': {}})
        self.cfg = read_json(os.path.join(HERE, 'adfree.json'), {}) or {}
        self._catalog, self._catalog_at = {}, 0
        self.requests = {}                              # episode id -> when it was queued (sensor.roche_podcasts_requests)

    def save(self):
        write_json(self.meta_path, self.meta)
        write_json(self.learn_path, self.learned)
        self.lib.save()
        eps = self.idx['eps']
        if len(eps) > INDEX_KEEP:
            for k in sorted(eps, key=lambda k: eps[k]['at'])[:len(eps) - INDEX_KEEP]:
                del eps[k]
        write_json(self.index_path, self.idx)

    # The episodes build.py knows, by id.
    def catalog(self):
        folder = os.path.join(self.out, 'shows')
        newest = max([os.path.getmtime(os.path.join(folder, f)) for f in os.listdir(folder)] or [0])
        if newest != self._catalog_at:
            cat = {}
            for f in os.listdir(folder):
                data = read_json(os.path.join(folder, f))
                if not data:
                    continue
                for e in data.get('eps', []):
                    if e.get('u') and not e.get('yt'):
                        cat[e['id']] = dict(e, show=data['id'], title=data.get('title', ''))
            self._catalog, self._catalog_at = cat, newest
        return self._catalog

    def path(self, ep_id, kind):
        return {'orig': os.path.join(self.state, 'orig', ep_id + '.audio'),
                'fp': os.path.join(self.state, 'eps', ep_id + '.fp'),
                'clean': os.path.join(self.dest, ep_id + '.mp3')}[kind]

    # ----- one episode -----

    def process(self, ep, redo=False):
        """Find the breaks in ep (a catalog entry) and write its ad-free copy."""
        started = time.time()
        meter = self.meter = Meter()
        outcome = 'failed'
        eid, show = ep['id'], ep['show']
        m = self.meta.setdefault(eid, {'show': show, 'tries': 0})
        m['tries'] = m.get('tries', 0) + (0 if redo else 1)
        work = tempfile.mkdtemp(prefix='adfree-')
        try:
            orig = self.path(eid, 'orig')
            if not os.path.exists(orig):
                download(ep['u'], orig, UAS[0], meter)
            meter.stage('download')
            codec, dur = probe(orig)
            if dur < 60:
                raise RuntimeError('too short or unreadable (%s, %.0f s)' % (codec, dur))
            a = load_fp(self.path(eid, 'fp')) if redo else None
            if not a:
                a = fingerprint(orig)
                save_fp(self.path(eid, 'fp'), a)
            meter.stage('fingerprint')
            found = []                                   # [start, end, kind] in the original's seconds

            # 1. The same episode fetched again: inserted ads differ.
            dai = m.get('dai') if redo else None
            if dai is None:
                dai = self.compare_downloads(ep, a, dur, orig, work)
                m['dai'] = dai
            meter.stage('copies')
            found += [[s, e, 'dai'] for s, e in dai]

            # 2. Ads already in the library, and things you skipped that turn up again.
            for key, s, n in self.lib.find(a, ['ad'], minlen=items(6)):
                found.append([secs(s), secs(s + n), 'known'])
                self.lib.meta['entries'][key]['seen'] = int(time.time())
            for key, s, n in self.lib.find(a, ['skip'], minlen=items(10)):
                e = self.lib.meta['entries'][key]
                if eid in e['eps'] and len(e['eps']) == 1:
                    continue                             # only the episode it was skipped in
                found.append([secs(s), secs(s + n), 'skip'])
                if eid not in e['eps']:
                    e['eps'].append(eid)
                e['kind'] = 'ad'                         # in two episodes: an ad
                self.lib.bump()

            # 3. Shared with a recent episode of another show.
            meter.stage('library')
            shared = self.cross_show(a, show, eid)
            meter.stage('other_shows')
            found += [[s, e, 'shared'] for s, e in shared]

            sure = merge([r for r in found if r[1] - r[0] >= 5])
            # 4. Sponsor reads: transcript, bumpers, loudness, the show's usual slots.
            lv = levels(orig)
            meter.stage('loudness')
            maybe = self.host_reads(ep, a, dur, sure, lv)
            meter.stage('host_reads')
            for s, e, score, why in maybe:
                if score >= 6:
                    sure.append([snap(lv, s), snap(lv, e), 'host'])
            sure = merge(sure)
            total = sum(e - s for s, e, _ in sure)
            if total > 0.35 * dur:                       # that much can't be ads: keep the surest
                sure = merge([r for r in found if r[2] in ('dai', 'known')])
            # The original's own breaks feed the library (ads) and teach the show's bumpers.
            for s, e, kind in sure:
                if kind in ('dai', 'known', 'shared', 'skip') and e - s <= 300:
                    self.lib.add('ad', a[items(s):items(e)], show, eid, src=kind)
                self.lib.add('edge', a[max(0, items(s) - items(5)):items(s)], show, eid, side='pre')
                self.lib.add('edge', a[items(e):items(e) + items(5)], show, eid, side='post')

            clean = self.path(eid, 'clean')
            sure = [[round(s, 2), round(min(e, dur), 2), k] for s, e, k in sure if e - s >= 5]
            if sure:
                meter.stage('decide')
                cut(orig, [(s, e) for s, e, _ in sure], dur, codec, clean)
                meter.stage('cut')
                _, clean_dur = probe(clean)
            else:
                if os.path.exists(clean):
                    os.unlink(clean)
                clean_dur = dur
            # Middling sponsor reads, in the ad-free copy's time, for the players to offer.
            offer = []
            for s, e, score, why in maybe:
                if 2 <= score < 6 and not any(min(e, ce) - max(s, cs) > 0.5 * (e - s) for cs, ce, _ in sure):
                    offer.append([round(to_clean(sure, snap(lv, s)), 1), round(to_clean(sure, snap(lv, e)), 1), why])
            m.update({'at': int(time.time()), 'cuts': sure, 'dur': dur, 'lib': self.lib.meta['version'], 'error': None})
            m.pop('deep', None)                          # a new copy: its second pass is redone too
            self.idx['eps'][eid] = {'at': m['at'], 'f': 1 if sure else 0, 'd': round(clean_dur, 1), 'o': round(dur, 1),
                                    'cuts': sure, 'maybe': offer}
            outcome = 'done'
            log('%s %s · %s: %d cut (%.0f s of %.0f), %d maybe, %.0f s' % (
                'redid' if redo else 'did', ep.get('title', show), ep.get('t', eid)[:60], len(sure),
                sum(e - s for s, e, _ in sure), dur, len(offer), time.time() - started))
        except Exception as e:     # noqa: BLE001 - note it and move on
            m.update({'error': str(e)[:300], 'failed_at': int(time.time())})
            log('FAILED', ep.get('title', show), '·', ep.get('t', eid)[:60], '-', e)
            if os.environ.get('ADFREE_DEBUG'):
                traceback.print_exc()
        finally:
            shutil.rmtree(work, ignore_errors=True)
            self.save()
            rec = {'at': int(time.time()), 'ep': eid, 'show': show, 'title': ep.get('t', ''), 'outcome': outcome,
                   'redo': redo, 'why': ep.get('why', ''), 'host': hostname(), 'dur_s': round(m.get('dur') or 0),
                   'cut_s': round(sum(c[1] - c[0] for c in m.get('cuts') or [])), 'cuts': len(m.get('cuts') or []),
                   'kinds': sorted(set(k for c in m.get('cuts') or [] for k in c[2].split('+'))),
                   'maybe': len((self.idx['eps'].get(eid) or {}).get('maybe') or []), 'error': m.get('error')}
            rec.update(meter.total(self.cfg))
            with open(os.path.join(self.state, 'metrics.jsonl'), 'a', encoding='utf-8') as f:
                f.write(json.dumps(rec, ensure_ascii=False) + '\n')

    def compare_downloads(self, ep, a, dur, orig, work):
        """Ad breaks: the stretches of the first download missing from the others.

        One more copy isn't enough: it often gets some of the same ads (the Vergecast's
        second copy had the first's pre-roll), which then look like the show. The show
        is what the copies agree on."""
        size = os.path.getsize(orig)
        breaks, compared = [], 0
        idx_a = index(a)
        for n, ua in enumerate(UAS[1:]):
            other = os.path.join(work, 'b%d.audio' % n)
            try:
                download(ep['u'], other, ua, getattr(self, 'meter', None))
            except Exception as e:     # noqa: BLE001
                log('  another download failed:', e)
                continue
            if os.path.getsize(other) == size and same_file(orig, other):
                continue                                 # the very same file
            try:
                b = fingerprint(other)
            except Exception as e:     # noqa: BLE001 - a damaged copy: the others will do
                log('  a copy could not be read:', e)
                continue
            segs = segments(a, b, index(b), items(5))
            mine, theirs = breaks_between(a, b, chain(segs))
            if sum(secs(e - s) for s, e in mine) > 0.4 * dur:
                log('  a copy differs too much (re-edited, or cut short); not using it')
                continue
            compared += 1
            breaks.append([(secs(s), min(dur, secs(e))) for s, e in mine])
            # The other copy's ads are ads too (into the library, not cut from this one),
            # unless some of it is also in this copy's show (then it isn't an ad).
            for s, e in theirs:
                inside = lambda i, n: any(i >= gs - 8 and i + n <= ge + 8 for gs, ge in mine)
                if all(inside(i, n) for _, i, n in segments(b[s:e], a, idx_a, items(5))):
                    self.lib.add('ad', b[s:e], ep['show'], ep['id'], src='dai')
            # Ads both copies got, somewhere else in the break: also into the library.
            for s, j, n in segs:
                if n >= items(10) and any(s >= gs - 8 and s + n <= ge + 8 for gs, ge in mine) \
                        and any(j >= gs - 8 and j + n <= ge + 8 for gs, ge in theirs):
                    self.lib.add('ad', a[s:s + n], ep['show'], ep['id'], src='both')
        # An ad where at least two copies differ (one, if only one could be compared): a copy
        # sharing an ad with the first is outvoted, and so is one that lines up badly.
        need = 1 if compared < 2 else 2
        edges = sorted([(s, 1) for b in breaks for s, e in b] + [(e, -1) for b in breaks for s, e in b])
        out, depth, start = [], 0, 0.0
        for t, d in edges:
            if depth < need <= depth + d:
                start = t
            elif depth >= need > depth + d and t - start >= 8:
                out.append([start, t, 'dai'])
            depth += d
        out = merge(out, join=1.0)
        if compared and sum(e - s for s, e, _ in out) > 0.4 * dur:
            log('  copies disagree too much; not using them')
            return []
        return [[round(s, 2), round(e, 2)] for s, e, _ in out]

    def cross_show(self, a, show, eid):
        """Stretches (10 s to 2.5 min) also in a recent episode of another show."""
        others = [k for k, m in sorted(self.meta.items(), key=lambda kv: -kv[1].get('at', 0))
                  if m.get('show') != show and k != eid and os.path.exists(self.path(k, 'fp'))][:CROSS_SHOW_EPS]
        big = []
        for k in others:
            fp = load_fp(self.path(k, 'fp')) or []
            big.extend(fp)
            big.extend([0x55555555, 0xAAAAAAAA] * 10)
        if not big:
            return []
        out = []
        for s, j, n in segments(a, big, index(big, step=2), items(10)):
            if n <= items(150):
                out.append((secs(s), secs(s + n)))
        return out

    def host_reads(self, ep, a, dur, sure, lv):
        """Likely sponsor reads left after the sure cuts: [(start, end, score, why)] in the original's time."""
        cands = []
        extras = read_json(os.path.join(self.out, 'extras', ep['id'] + '.json'))
        clean_dur = dur - sum(e - s for s, e, _ in sure)
        for s, e, score in transcript_ranges(extras, clean_dur):
            cands.append([to_orig(sure, s), to_orig(sure, e), score, 'transcript'])
        # The show's bumpers: a sound found at the edges of breaks in two or more episodes.
        marks = []
        for key, s, n in self.lib.find(a, ['edge'], minlen=items(3), show=ep['show'], maxerr=8):
            e = self.lib.meta['entries'][key]
            if ep['id'] not in e['eps']:
                e['eps'] = (e['eps'] + [ep['id']])[-10:]
            if len(e['eps']) < 2:
                continue
            t0, t1 = secs(s), secs(s + n)
            if any(abs(t0 - cs) < 12 or abs(t1 - ce) < 12 or abs(t0 - ce) < 12 or abs(t1 - cs) < 12 for cs, ce, _ in sure):
                continue
            marks.append((t0, t1, e.get('side')))
        marks.sort()
        for (s0, e0, side0), (s1, e1, side1) in zip(marks, marks[1:]):
            if 15 <= s1 - e0 <= 240:
                cands.append([e0, s1, 3, 'bumper'])
        for c in cands:
            s, e = c[0], c[1]
            if any(m[1] <= s + 15 and m[1] >= s - 15 for m in marks) and c[3] != 'bumper':
                c[2] += 1
            if loud_jump(lv, s, e):
                c[2] += 1
            if self.in_slot(ep['show'], to_clean(sure, (s + e) / 2), clean_dur):
                c[2] += 2
        # Overlapping candidates: keep the best score.
        cands.sort(key=lambda c: -c[2])
        out = []
        for c in cands:
            if not any(min(c[1], o[1]) - max(c[0], o[0]) > 0 for o in out):
                out.append(c)
        return [tuple(c) for c in out]

    # ----- what you skip -----

    def in_slot(self, show, t, dur):
        """t is where, in other episodes of this show, you've skipped at least twice."""
        if not dur:
            return False
        frac = t / dur
        near = set(ep for f, ep in self.learned['slots'].get(show, []) if abs(f - frac) < 0.04)
        return len(near) >= 2

    def ingest_skips(self, skips):
        """skips: {episode id: [[from, to, clean 0/1, at, show], ...]} from the apps."""
        seen = set(self.learned['skips_seen'])
        new = 0
        for eid, rows in (skips or {}).items():
            for row in rows:
                if not isinstance(row, list) or len(row) < 4:
                    continue
                key = '%s:%s:%s:%s' % (eid, row[0], row[1], row[3])
                if key in seen:
                    continue
                seen.add(key)
                self.learned['skips_seen'].append(key)
                new += 1
                self.learn_skip(eid, float(row[0]), float(row[1]), bool(row[2]), row[4] if len(row) > 4 else '')
        self.learned['skips_seen'] = self.learned['skips_seen'][-3000:]
        if new:
            log('learned from %d skip(s)' % new)
            self.save()

    def learn_skip(self, eid, frm, to, clean, show):
        if not 15 <= to - frm <= 300:
            return
        m = self.meta.get(eid) or {}
        cuts = m.get('cuts') or []
        show = m.get('show') or show
        if not show:
            return
        dur = m.get('dur') or 0
        clean_dur = dur - sum(e - s for s, e, _ in cuts) if dur else 0
        c0, c1 = (frm, to) if clean else (to_clean(cuts, frm), to_clean(cuts, to))
        if clean_dur:
            slots = self.learned['slots'].setdefault(show, [])
            slots.append([round((c0 + c1) / 2 / clean_dur, 4), eid])
            del slots[:-60]
        a = load_fp(self.path(eid, 'fp'))
        if a:
            o0, o1 = (to_orig(cuts, frm), to_orig(cuts, to)) if clean else (frm, to)
            self.lib.add('skip', a[items(o0):items(o1)], show, eid)

    # ----- choosing episodes -----

    # ----- second pass: transcript, sponsor reads, chapters -----

    def deep(self, ep, urgent):
        """Transcribe the episode as the players hear it, and have a model mark its ads and chapters."""
        import deep as deepmod
        eid = ep['id']
        m = self.meta[eid]
        info = self.idx['eps'].get(eid)
        if not info:
            return
        meter = Meter()
        rec = {'what': 'deep', 'ep': eid, 'show': ep['show'], 'title': ep.get('t', ''), 'urgent': urgent, 'host': hostname()}
        d = m.setdefault('deep', {})
        d['tries'] = d.get('tries', 0) + 1
        try:
            audio = self.path(eid, 'clean') if info.get('f') else self.path(eid, 'orig')
            if not os.path.exists(audio):
                if info.get('f'):
                    raise RuntimeError('no audio')
                download(ep['u'], audio, UAS[0], meter)
            segments, how = deepmod.transcribe(audio, urgent)
            meter.stage('transcribe')
            rec['transcribe'] = how
            text = deepmod.lines(segments)
            found, used = deepmod.analyze(text, ep.get('t', ''), ep.get('title', ''), ep.get('x', ''), urgent, allow_claude=urgent or d['tries'] >= 3)
            meter.stage('analyze')
            rec['analyze'] = used
            if found is None:
                raise RuntimeError('no model answered (%s)' % used.get('error'))
            length = info.get('d') or 0
            skip, offer = [], []
            for a in found.get('ads') or []:
                try:
                    s0, e0 = float(a['start']), float(a['end'])
                except (KeyError, TypeError, ValueError):
                    continue
                if not (8 <= e0 - s0 <= 360) or s0 < 0 or (length and s0 > length):
                    continue
                kind = 'llm-' + re.sub(r'[^a-z-]', '', str(a.get('kind', 'ad')).lower())[:12]
                s0 = ad_start(text, s0, str(a.get('sponsor') or ''))
                (offer if kind == 'llm-self' else skip).append([round(s0, 1), round(min(e0, length or e0), 1), kind])
            if length and sum(e - s for s, e, _ in skip) > 0.3 * length:
                offer, skip = offer + skip, []           # that much can't all be ads: offer, don't skip
            chapters = sorted([{'t': int(float(c['start'])), 'n': str(c.get('title', ''))[:80],
                                'p': [str(x)[:160] for x in (c.get('points') or []) if str(x).strip()][:4]}
                               for c in found.get('chapters') or [] if isinstance(c, dict) and 'start' in c], key=lambda c: c['t'])
            extras = {'transcript': {'timed': True, 'lines': text}}
            if str(found.get('summary') or '').strip():
                extras['summary'] = str(found['summary']).strip()[:1200]
            if isinstance(found.get('description'), str):
                extras['description'] = found['description'].strip()[:2000]
            if len(chapters) > 1:
                extras['chapters'] = chapters
            write_json(os.path.join(self.dest, eid + '.extras.json'), extras)
            info.update({'skip': skip, 'maybe': offer, 'x': 1})
            # Produced ads and promos (the same recording runs elsewhere) teach the library.
            a = load_fp(self.path(eid, 'fp'))
            cuts = m.get('cuts') or []
            for s0, e0, kind in skip:
                if a and kind in ('llm-produced', 'llm-promo'):
                    o0, o1 = (to_orig(cuts, s0), to_orig(cuts, e0)) if info.get('f') else (s0, e0)
                    self.lib.add('ad', a[items(o0):items(o1)], ep['show'], eid, src='llm')
            d.update({'at': int(time.time()), 'error': None, 'v': deepmod.VERSION})
            rec.update({'outcome': 'done', 'skips': len(skip), 'skip_s': round(sum(e - s for s, e, _ in skip)),
                        'offers': len(offer), 'chapters': len(chapters), 'lines': len(text)})
            log('deep %s · %s: %d ads to skip (%.0f s), %d offered, %d chapters; %s on %s, %s' % (
                ep.get('title', ''), ep.get('t', eid)[:50], len(skip), sum(e - s for s, e, _ in skip), len(offer), len(chapters),
                how['model'], how['where'], used.get('model')))
        except Exception as e:     # noqa: BLE001
            d.update({'error': str(e)[:300], 'failed_at': int(time.time())})
            rec.update({'outcome': 'failed', 'error': str(e)[:300]})
            log('deep FAILED', ep.get('title', ''), '·', ep.get('t', eid)[:50], '-', e)
            if 'asleep' in str(e) or 'no model' in str(e):
                self.m5_rest = time.time() + 900         # don't keep waking it for the batch
        finally:
            rec.update(meter.total(self.cfg))
            rec['at'] = int(time.time())
            with open(os.path.join(self.state, 'metrics.jsonl'), 'a', encoding='utf-8') as f:
                f.write(json.dumps(rec, ensure_ascii=False) + '\n')
            self.save()

    def deep_due(self, progress, urgent):
        """Episodes with a first pass but no second: being listened to (urgent), or else the
        unplayed rest in the nightly window (3:00-6:00, when the M5 wakes for it)."""
        cat = self.catalog()
        now = time.time()
        if not urgent and (not 3 <= time.localtime().tm_hour < 6 or now < getattr(self, 'm5_rest', 0)):
            return None
        import deep as deepmod
        wanted = self.requests
        order = sorted(self.meta.items(), key=lambda kv: (kv[0] not in wanted, -kv[1].get('at', 0)))
        for eid, m in order:
            d = m.get('deep') or {}
            stale = d.get('at') and d.get('v', 1) < deepmod.VERSION      # done by an older prompt: redo at night
            if not m.get('at') or m.get('error') or (d.get('at') and not (stale and not urgent)) or eid not in cat or eid not in self.idx['eps']:
                continue
            if d.get('tries', 0) >= 4 or now - d.get('failed_at', 0) < (300 if urgent else 1800):
                continue
            p = progress.get(eid)
            playing = p and now - p[3] < PLAYING_WINDOW and not p[2]
            if urgent and (playing or eid in wanted) and not (p and p[2]):
                return cat[eid]
            if not urgent and not (p and p[2]) and now - m['at'] < 14 * 86400:
                return cat[eid]
        return None

    def due(self, progress):
        """Episodes to do now, most urgent first."""
        cat = self.catalog()
        now = time.time()
        always = set(self.cfg.get('always', []))
        never = set(self.cfg.get('never', []))

        def todo(eid):
            m = self.meta.get(eid)
            if not m:
                return True
            if m.get('at') and not m.get('error'):
                return False
            return m.get('tries', 0) < MAX_ATTEMPTS and now - m.get('failed_at', 0) > 1800

        out = []
        # Being listened to now.
        for eid, p in sorted(progress.items(), key=lambda kv: -kv[1][3]):
            if now - p[3] < PLAYING_WINDOW and not p[2] and eid in cat and cat[eid]['show'] not in never and todo(eid):
                out.append(dict(cat[eid], why='playing'))
        # Queued on the phone (Play, Play next, Add to Up next): soon to be heard.
        for eid in sorted(self.requests, key=lambda k: -self.requests[k]):
            if eid in cat and cat[eid]['show'] not in never and todo(eid) and all(o['id'] != eid for o in out) \
                    and not (progress.get(eid) or [0, 0, 0])[2]:
                out.append(dict(cat[eid], why='queued'))
        # New episodes of the shows you listen to regularly.
        recent = {}
        for eid, p in progress.items():
            if now - p[3] < REGULAR_DAYS * 86400 and len(p) > 4 and (p[2] or p[0] > 300):
                recent.setdefault(p[4], set()).add(eid)
        regular = set(s for s, eps in recent.items() if len(eps) >= 2) | always
        regular -= never
        for e in sorted(cat.values(), key=lambda e: -e.get('d', 0)):
            if e['show'] in regular and now - e.get('d', 0) < NEW_DAYS * 86400 and not (progress.get(e['id']) or [0, 0, 0])[2] \
                    and todo(e['id']) and all(o['id'] != e['id'] for o in out):
                out.append(dict(e, why='regular'))
        return out

    def redo(self, progress):
        """Unstarted episodes processed before the library learned something new."""
        cat = self.catalog()
        v = self.lib.meta['version']
        for eid, m in sorted(self.meta.items(), key=lambda kv: -kv[1].get('at', 0)):
            p = progress.get(eid)
            if m.get('at') and m.get('lib', v) < v and eid in cat and os.path.exists(self.path(eid, 'orig')) \
                    and not (p and (p[2] or p[0] > 0)):
                return cat[eid]
        return None

    def tidy(self, progress):
        """Keep the newest originals; drop ad-free copies once played (3 days) or over the size limit."""
        origs = sorted(os.listdir(os.path.join(self.state, 'orig')),
                       key=lambda f: -os.path.getmtime(os.path.join(self.state, 'orig', f)))
        for f in origs[KEEP_ORIGINALS:]:
            os.unlink(os.path.join(self.state, 'orig', f))
        fps = sorted(os.listdir(os.path.join(self.state, 'eps')),
                     key=lambda f: -os.path.getmtime(os.path.join(self.state, 'eps', f)))
        for f in fps[KEEP_EPISODE_FPS:]:
            os.unlink(os.path.join(self.state, 'eps', f))
        now = time.time()
        files = []
        for f in os.listdir(self.dest):
            if not f.endswith('.mp3') or '.part' in f:
                continue
            eid = f[:-4]
            p = progress.get(eid)
            path = os.path.join(self.dest, f)
            if p and p[2] and now - p[3] > 3 * 86400:
                self.drop(eid)
            else:
                files.append((os.path.getmtime(path), os.path.getsize(path), eid))
        total = sum(sz for _, sz, _ in files)
        for _, sz, eid in sorted(files):
            if total <= MAX_CLEAN_BYTES:
                break
            self.drop(eid)
            total -= sz
        for eid in [k for k, m in self.meta.items() if m.get('at', m.get('failed_at', 0)) < now - 120 * 86400]:
            del self.meta[eid]
        self.lib.prune()

    def drop(self, eid):
        for f in (self.path(eid, 'clean'), os.path.join(self.dest, eid + '.extras.json')):
            try:
                os.unlink(f)
            except OSError:
                pass
        self.idx['eps'].pop(eid, None)

    # ----- Home Assistant -----

    def ha(self, entity):
        url, token = os.environ.get('HA_URL', '').rstrip('/'), os.environ.get('HA_TOKEN', '')
        if not url or not token:
            return {}
        req = urllib.request.Request(url + '/api/states/' + entity, headers={'Authorization': 'Bearer ' + token})
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return json.loads(r.read()).get('attributes') or {}
        except Exception:     # noqa: BLE001 - not there yet, or HA restarting
            return {}

    def watch(self):
        log('watching; library has %d entries' % len(self.lib.meta['entries']))
        last_tidy = 0
        while True:
            try:
                progress = {k: v for k, v in (self.ha('sensor.roche_podcasts').get('progress') or {}).items()
                            if isinstance(v, list) and len(v) >= 4}
                self.ingest_skips(self.ha('sensor.roche_podcasts_skips').get('skips'))
                reqs = self.ha('sensor.roche_podcasts_requests').get('requests') or {}
                self.requests = {k: v for k, v in reqs.items() if isinstance(v, (int, float)) and time.time() - v < 2 * 86400}
                # Most urgent first: what's playing (cuts, then its second pass), then ahead of time.
                due = self.due(progress)
                if due and due[0].get('why') in ('playing', 'queued'):
                    self.process(due[0])
                    continue                             # straight on to the next
                hurry = self.deep_due(progress, urgent=True)
                if hurry:
                    self.deep(hurry, urgent=True)
                    continue
                if due:
                    self.process(due[0])
                    continue
                later = self.deep_due(progress, urgent=False)
                if later:
                    self.deep(later, urgent=False)
                    continue
                again = self.redo(progress)
                if again:
                    self.process(again, redo=True)
                    continue
                if time.time() - last_tidy > 3600:
                    self.tidy(progress)
                    self.save()
                    last_tidy = time.time()
            except Exception:     # noqa: BLE001 - keep watching
                traceback.print_exc()
            time.sleep(60)

    def status(self):
        rows = read_jsonl(os.path.join(self.state, 'metrics.jsonl'))
        if rows:
            print('per day (from metrics.jsonl):')
            days = {}
            for r in rows:
                d = days.setdefault(time.strftime('%Y-%m-%d', time.localtime(r['at'])), {'n': 0, 'fail': 0, 'cpu': 0, 'wh': 0, 'mb': 0, 'cut': 0, 'audio': 0})
                d['n'] += 1
                d['fail'] += r['outcome'] != 'done'
                d['cpu'] += r.get('cpu_s', 0)
                d['wh'] += r.get('est_wh', 0)
                d['mb'] += r.get('mb_down', 0)
                d['cut'] += r.get('cut_s', 0)
                d['audio'] += r.get('dur_s', 0)
            for day, d in sorted(days.items())[-7:]:
                print('  %s  %3d episodes (%d failed)  %5.1f h audio, %4.0f min of ads cut  cpu %5.0f s  ~%.1f Wh  %.1f GB down' % (
                    day, d['n'], d['fail'], d['audio'] / 3600, d['cut'] / 60, d['cpu'], d['wh'], d['mb'] / 1000))
        kinds = {}
        for e in self.lib.meta['entries'].values():
            kinds[e['kind']] = kinds.get(e['kind'], 0) + 1
        print('library:', kinds, 'version', self.lib.meta['version'])
        print('slots learned for %d shows' % len(self.learned['slots']))
        cat = self.catalog()
        for eid, m in sorted(self.meta.items(), key=lambda kv: -kv[1].get('at', kv[1].get('failed_at', 0)))[:30]:
            e = cat.get(eid, {})
            cuts = m.get('cuts') or []
            print('%s %-28.28s %-40.40s %s' % (eid, e.get('title', m.get('show')), e.get('t', ''),
                                              m.get('error') or '%d cut, %.0f s: %s' % (
                                                  len(cuts), sum(c[1] - c[0] for c in cuts),
                                                  ' '.join('%d-%d %s' % (c[0], c[1], c[2]) for c in cuts))))


# ---------- time in the original and in the ad-free copy ----------

def to_clean(cuts, t):
    out = t
    for s, e, *_ in cuts:
        if t >= e:
            out -= e - s
        elif t > s:
            out -= t - s
    return max(0.0, out)


def to_orig(cuts, t):
    for s, e, *_ in sorted(cuts):
        if t >= s:
            t += e - s
    return t


def snap(lv, t, reach=2.0):
    """The quietest half second within reach of t (a pause between sentences)."""
    if not lv:
        return t
    i0, i1 = max(0, int((t - reach) * 2)), min(len(lv), int((t + reach) * 2) + 1)
    if i0 >= i1:
        return t
    q = min(range(i0, i1), key=lambda i: lv[i])
    return q / 2 + 0.25


def loud_jump(lv, s, e, db=2.5):
    """The level inside differs by db or more from the 20 s before and after."""
    def med(a, b):
        xs = sorted(v for v in lv[max(0, int(a * 2)):max(0, int(b * 2))] if v > -60)
        return xs[len(xs) // 2] if xs else None
    inside, before, after = med(s, e), med(s - 20, s), med(e, e + 20)
    return None not in (inside, before, after) and abs(inside - before) >= db and abs(inside - after) >= db


def hostname():
    return os.uname().nodename


def same_file(p, q):
    with open(p, 'rb') as a, open(q, 'rb') as b:
        while True:
            x, y = a.read(1 << 20), b.read(1 << 20)
            if x != y:
                return False
            if not x:
                return True


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    env_file()
    for tool in ('ffmpeg', 'ffprobe', 'fpcalc'):
        if not shutil.which(tool):
            sys.exit('%s is missing: sudo apt install ffmpeg libchromaprint-tools' % tool)
    af = AdFree(sys.argv[1])
    cmd = sys.argv[2] if len(sys.argv) > 2 else 'watch'
    if cmd == 'once' and len(sys.argv) > 3:
        ep = af.catalog().get(sys.argv[3])
        if not ep:
            sys.exit('no episode %s in %s/shows' % (sys.argv[3], sys.argv[1]))
        af.meta.pop(ep['id'], None)
        af.process(ep)
    elif cmd == 'deep' and len(sys.argv) > 3:
        ep = af.catalog().get(sys.argv[3])
        if not ep or sys.argv[3] not in af.meta:
            sys.exit('no first pass for %s yet' % sys.argv[3])
        af.meta[sys.argv[3]].pop('deep', None)
        af.deep(ep, urgent=len(sys.argv) > 4 and sys.argv[4] == 'now')
    elif cmd == 'status':
        af.status()
    else:
        af.watch()


if __name__ == '__main__':
    main()
