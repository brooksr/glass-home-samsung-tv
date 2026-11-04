#!/usr/bin/env python3
"""Roche Home podcasts: read every feed in sources.json and write what the TV needs.

    python3 build.py sources.json OUT

Writes, in OUT:
  podcasts.json      the shows (title, author, category, art, newest date) and each one's
                     newest 3 episodes, for the TV's rows
  shows/<id>.json    one show's newest 100 episodes
  art/<id>.jpg       300 px cover art (fetched once; delete one to refetch)
  youtube/<id>.json  the show's recent YouTube videos (see youtube() below)
  extras/<ep>.json   chapters and transcript of each show's newest 10 episodes,
                     for the TV, where the feed publishes them (Podcasting 2.0
                     chapters or transcript, or ID3 chapters in the MP3 for
                     shows marked "id3chapters": true in sources.json)

Runs on the Home Assistant laptop every 30 minutes (see README.md), writing
into Home Assistant's www folder, which serves it at /local/roche-home/podcasts/.
A feed that fails keeps its previous file. Standard library only.

Episode IDs are the first 12 hex digits of the SHA-1 of the episode's <guid>
(or its audio URL when there is no guid). The Android app computes the same
IDs, so listening progress matches across the TV and the phone.
"""
import concurrent.futures
import email.utils
import hashlib
import html
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

UA = 'RocheHome/1.0 (podcast reader; +https://github.com/)'
ITUNES = '{http://www.itunes.com/dtds/podcast-1.0.dtd}'
PODCAST = '{https://podcastindex.org/namespace/1.0}'   # Podcasting 2.0
TRANSCRIPT_KINDS = {'text/vtt': 'vtt', 'application/srt': 'srt', 'application/x-subrip': 'srt', 'text/srt': 'srt',
                    'application/json': 'json', 'text/plain': 'txt', 'text/html': 'html'}
TRANSCRIPT_ORDER = ['vtt', 'srt', 'json', 'txt', 'html']   # timed first
EXTRAS = 10         # newest episodes per show given an extras/<id>.json for the TV
KEEP = 100          # episodes per show file
LATEST = 3          # episodes per show in podcasts.json
DESC = 600          # characters of episode description kept


def fetch(url, timeout=30):
    req = urllib.request.Request(url, headers={'User-Agent': UA, 'Accept': '*/*'})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def ep_id(key):
    return hashlib.sha1(key.encode('utf-8')).hexdigest()[:12]


def text(s, limit=None):
    """HTML/entities out, whitespace collapsed."""
    if not s:
        return ''
    s = re.sub(r'<(br|p|/p|li|div)[^>]*>', ' ', s, flags=re.I)
    s = re.sub(r'<[^>]+>', '', s)
    s = html.unescape(s)
    s = re.sub(r'\s+', ' ', s).strip()
    if limit and len(s) > limit:
        s = s[:limit].rsplit(' ', 1)[0] + '…'
    return s


def seconds(v):
    """itunes:duration: '1:02:03', '62:03', '3723' or '3723.5'."""
    if not v:
        return 0
    v = v.strip()
    try:
        parts = [float(p) for p in v.split(':')]
    except ValueError:
        return 0
    total = 0
    for p in parts:
        total = total * 60 + p
    return int(total)


def when(v):
    if not v:
        return 0
    try:
        return int(email.utils.parsedate_to_datetime(v.strip()).timestamp())
    except (TypeError, ValueError, IndexError):
        return 0


def parse(xml_bytes):
    root = ET.fromstring(xml_bytes)
    ch = root.find('channel')
    if ch is None:
        raise ValueError('no <channel>')
    eps = []
    for item in ch.findall('item'):
        enc = item.find('enclosure')
        url = enc.get('url') if enc is not None else None
        if not url:
            continue
        kind = (enc.get('type') or '').lower()
        if kind and not kind.startswith('audio') and 'mpeg' not in kind:
            continue    # video episodes: the TV's <audio> can't play them
        guid = (item.findtext('guid') or '').strip()
        chapters = item.find(PODCAST + 'chapters')
        ep = {
            'id': ep_id(guid or url),
            't': text(item.findtext('title')),
            'd': when(item.findtext('pubDate')),
            's': seconds(item.findtext(ITUNES + 'duration')),
            'u': url,
            'x': text(item.findtext(ITUNES + 'summary') or item.findtext('description'), DESC),
        }
        # Chapters as JSON and transcripts (Podcasting 2.0), as published.
        if chapters is not None and chapters.get('url') and 'json' in (chapters.get('type') or 'json'):
            ep['c'] = chapters.get('url')
        best = None
        for tr in item.findall(PODCAST + 'transcript'):
            kind = TRANSCRIPT_KINDS.get((tr.get('type') or '').lower())
            if kind and tr.get('url') and (best is None or TRANSCRIPT_ORDER.index(kind) < TRANSCRIPT_ORDER.index(best[0])):
                best = (kind, tr.get('url'))
        if best:
            ep['tt'], ep['tr'] = best
        eps.append(ep)
    eps.sort(key=lambda e: e['d'], reverse=True)
    img = ch.find(ITUNES + 'image')
    return {
        'title': text(ch.findtext('title')),
        'author': text(ch.findtext(ITUNES + 'author')),
        'desc': text(ch.findtext('description') or ch.findtext(ITUNES + 'summary'), 500),
        'image': img.get('href') if img is not None else (ch.findtext('image/url') or ''),
        'eps': eps[:KEEP],
    }


# ---------- chapters and transcripts for the TV (extras/<episode id>.json) ----------
# {"chapters": [{"t": seconds, "n": title}], "transcript": {"timed": bool, "lines": [{"t": s, "x": text}]}}
# Made once per episode. The phone reads the originals itself.

def id3_chapters(url):
    """CHAP frames from an MP3's ID3v2 tag (read from the start of the stream only)."""
    req = urllib.request.Request(url, headers={'User-Agent': UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        head = r.read(10)
        if len(head) < 10 or head[:3] != b'ID3':
            return []
        ver = head[3]
        size = (head[6] << 21) | (head[7] << 14) | (head[8] << 7) | head[9]
        if size > 8 * 1024 * 1024:
            return []
        tag = r.read(size)
    out = []

    def frames(buf):
        i = 0
        while i + 10 <= len(buf):
            fid = buf[i:i + 4]
            if not fid.strip(b'\0'):
                break
            n = buf[i + 4:i + 8]
            n = ((n[0] << 21) | (n[1] << 14) | (n[2] << 7) | n[3]) if ver >= 4 else int.from_bytes(n, 'big')
            yield fid.decode('latin-1'), buf[i + 10:i + 10 + n]
            i += 10 + n

    def decode(b):
        enc, b = b[0], b[1:]
        codec = {0: 'latin-1', 1: 'utf-16', 2: 'utf-16-be', 3: 'utf-8'}.get(enc, 'latin-1')
        return b.decode(codec, 'replace').strip('\0 ').strip()

    for fid, body in frames(tag):
        if fid != 'CHAP':
            continue
        end = body.find(b'\0')
        start_ms = int.from_bytes(body[end + 1:end + 5], 'big')
        title = ''
        for sid, sub in frames(body[end + 17:]):
            if sid == 'TIT2':
                title = decode(sub)
        out.append({'t': start_ms // 1000, 'n': title})
    out.sort(key=lambda c: c['t'])
    return out


def json_chapters(url):
    data = json.loads(fetch(url))
    return [{'t': int(c.get('startTime', 0)), 'n': text(c.get('title', ''))}
            for c in data.get('chapters', []) if c.get('toc', True) is not False]


def stamp(v):
    """'01:02:03.500', '02:03,500' or '3.5' -> seconds."""
    v = v.strip().replace(',', '.')
    total = 0.0
    for p in v.split(':'):
        total = total * 60 + float(p)
    return int(total)


def transcript(kind, url):
    raw = fetch(url).decode('utf-8', 'replace')
    lines = []
    if kind in ('vtt', 'srt'):
        for block in re.split(r'\n\s*\n', raw.replace('\r', '')):
            m = re.search(r'([\d:.,]+)\s*-->', block)
            if not m:
                continue
            body = block[m.end():].split('\n', 1)
            body = body[1] if len(body) > 1 else ''
            who = re.search(r'<v\s+([^>]+)>', body)
            words = text(body)
            # "Speaker 1: ..." written into the text, as iHeart's are
            said = re.match(r'^([A-Z][\w .\'-]{0,40}):\s+(.*)$', words)
            if said and not who:
                who, words = said, said.group(2)
            if words:
                lines.append({'t': stamp(m.group(1)), 's': who.group(1).strip() if who else '', 'x': words})
    elif kind == 'json':
        for seg in json.loads(raw).get('segments', []):
            words = text(seg.get('body', ''))
            if words:
                lines.append({'t': int(seg.get('startTime', 0)), 's': seg.get('speaker') or '', 'x': words})
    else:
        for para in re.split(r'\n\s*\n|</p>|<br\s*/?>\s*<br\s*/?>', raw):
            words = text(para)
            if words:
                lines.append({'t': 0, 'x': words})
        return {'timed': False, 'lines': lines}
    # Subtitle cues are a few words each; join them into sentences of up to ~25 s.
    merged = []
    for ln in lines:
        last = merged[-1] if merged else None
        if last and last['s'] == ln['s'] and ln['t'] - last['t'] < 25 and not re.search(r'[.!?]["”]?$', last['x']):
            last['x'] += ' ' + ln['x']
        else:
            merged.append(dict(ln))
    # The speaker only where it changes ('s'); none at all if there's only one.
    prev = None
    for ln in merged:
        if not ln['s'] or ln['s'] == prev:
            del ln['s']
        else:
            prev = ln['s']
    return {'timed': True, 'lines': merged}


def extras(show, eps, out):
    """Write extras for the newest episodes that have any; mark them with 'e': 1."""
    folder = os.path.join(out, 'extras')
    for ep in eps[:EXTRAS]:
        path = os.path.join(folder, ep['id'] + '.json')
        none = os.path.join(folder, ep['id'] + '.none')
        if os.path.exists(path):
            ep['e'] = 1
            continue
        if os.path.exists(none) or not (ep.get('c') or ep.get('tr') or show.get('id3chapters')):
            continue
        data = {}
        try:
            chapters = json_chapters(ep['c']) if ep.get('c') else (id3_chapters(ep['u']) if show.get('id3chapters') else [])
            if len(chapters) > 1:
                data['chapters'] = chapters
            if ep.get('tr'):
                tr = transcript(ep['tt'], ep['tr'])
                if tr['lines']:
                    data['transcript'] = tr
        except urllib.error.HTTPError as e:
            if 400 <= e.code < 500:          # refused or gone: don't ask again
                open(none, 'w').close()
            print('  extras failed for', show['title'], '-', ep['t'], '-', e)
            continue
        except Exception as e:     # noqa: BLE001 - try again next run
            print('  extras failed for', show['title'], '-', ep['t'], '-', e)
            continue
        if data:
            write_json(path, data)
            ep['e'] = 1
        else:
            open(none, 'w').close()


# ---------- YouTube ----------
# For shows with "youtube": {"channel": ..., "only": bool} in sources.json:
# the channel's own feed (its newest 15 videos) is read each run and kept in
# youtube/<show id>.json (the last 60). A video whose title matches an episode
# published within 10 days of it gives that episode 'v' (its video ID: a Watch
# button). With "only", the other videos (not Shorts) are listed too, as
# episodes with 'yt': 1 and no audio.

YT = '{http://www.youtube.com/xml/schemas/2015}'
ATOM = '{http://www.w3.org/2005/Atom}'
MEDIA = '{http://search.yahoo.com/mrss/}'
STOP = {'the', 'and', 'with', 'podcast', 'full', 'episode', 'for', 'you', 'are', 'from', 'this', 'that'}


def words(t):
    t = re.sub(r'#\d+|\bep\.?\s*\d+|\bepisode \d+|[^a-z0-9 ]', ' ', t.lower())
    return set(w for w in t.split() if len(w) > 2 and w not in STOP)


def same(a, b):
    wa, wb = words(a), words(b)
    if not wa or not wb:
        return False
    return len(wa & wb) >= max(2, 0.6 * min(len(wa), len(wb)))


def iso(v):
    try:
        return int(time.mktime(time.strptime(v[:19], '%Y-%m-%dT%H:%M:%S')) - time.timezone)
    except (TypeError, ValueError):
        return 0


def youtube(show, eps, out):
    yt = show.get('youtube')
    if not yt:
        return eps
    path = os.path.join(out, 'youtube', show['id'] + '.json')
    try:
        with open(path, encoding='utf-8') as f:
            known = {v['v']: v for v in json.load(f)}
    except (OSError, ValueError):
        known = {}
    try:
        root = ET.fromstring(fetch('https://www.youtube.com/feeds/videos.xml?channel_id=' + yt['channel']))
        for e in root.findall(ATOM + 'entry'):
            vid = e.findtext(YT + 'videoId')
            link = e.find(ATOM + 'link')
            if not vid or (link is not None and '/shorts/' in (link.get('href') or '')):
                continue
            group = e.find(MEDIA + 'group')
            known[vid] = {'v': vid, 't': text(e.findtext(ATOM + 'title')), 'd': iso(e.findtext(ATOM + 'published')),
                          'x': text(group.findtext(MEDIA + 'description') if group is not None else '', DESC)}
    except Exception as e:     # noqa: BLE001 - keep what we had
        print('  youtube failed for', show['title'], '-', e)
    videos = sorted(known.values(), key=lambda v: v['d'], reverse=True)[:60]
    write_json(path, videos)
    extra = []
    for v in videos:
        match = next((ep for ep in eps if 'v' not in ep and abs(ep['d'] - v['d']) < 10 * 86400 and same(ep['t'], v['t'])), None)
        if match:
            match['v'] = v['v']
        elif yt.get('only'):
            extra.append({'id': ep_id('yt:' + v['v']), 't': v['t'], 'd': v['d'], 's': 0, 'u': '', 'x': v['x'], 'v': v['v'], 'yt': 1})
    return sorted(eps + extra, key=lambda e: e['d'], reverse=True)


def write_json(path, data):
    tmp = path + '.tmp'
    with open(tmp, 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, separators=(',', ':'))
    os.replace(tmp, path)


def art(show, out):
    path = os.path.join(out, 'art', show['id'] + '.jpg')
    if os.path.exists(path):
        return True
    url = show.get('art')
    if not url:
        return False
    # Apple's art server sizes on request; any other host is fetched as is.
    url = re.sub(r'/\d+x\d+bb\.(jpg|png|webp)$', '/300x300bb.jpg', url)
    try:
        data = fetch(url)
    except Exception as e:     # noqa: BLE001 - keep going without art
        print('  art failed for', show['title'], e)
        return False
    with open(path + '.tmp', 'wb') as f:
        f.write(data)
    os.replace(path + '.tmp', path)
    return True


def one(show, out):
    path = os.path.join(out, 'shows', show['id'] + '.json')
    try:
        feed = parse(fetch(show['feed']))
    except Exception as e:     # noqa: BLE001 - one bad feed shouldn't stop the rest
        print('  FAILED', show['title'], '-', e)
        try:
            with open(path, encoding='utf-8') as f:
                return json.load(f)
        except (OSError, ValueError):
            return None
    extras(show, feed['eps'], out)
    feed['eps'] = youtube(show, feed['eps'], out)
    data = {
        'id': show['id'],
        'title': show.get('title') or feed['title'],
        'author': show.get('author') or feed['author'],
        'desc': feed['desc'],
        'eps': feed['eps'],
    }
    write_json(path, data)
    return data


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    sources, out = sys.argv[1], sys.argv[2]
    with open(sources, encoding='utf-8') as f:
        shows = json.load(f)['shows']
    for d in ('shows', 'art', 'extras', 'youtube'):
        os.makedirs(os.path.join(out, d), exist_ok=True)
    started = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
        results = list(pool.map(lambda s: one(s, out), shows))
        arts = list(pool.map(lambda s: art(s, out), shows))
    index = []
    for show, data, has_art in zip(shows, results, arts):
        if not data:
            continue
        eps = data['eps']
        index.append({
            'id': show['id'],
            'title': data['title'],
            'author': data['author'],
            'feed': show['feed'],
            'category': show.get('category', 'Other'),
            'id3chapters': bool(show.get('id3chapters')),
            'image': show.get('art', ''),   # the full-size original, for the phone away from home
            'art': 'art/' + show['id'] + '.jpg' if has_art else '',
            'newest': eps[0]['d'] if eps else 0,
            'count': len(eps),
            # Shorter copies for the rows: no description.
            # The 3 newest episodes and the 2 newest YouTube-only videos.
            'eps': [{k: e[k] for k in ('id', 't', 'd', 's', 'u', 'c', 'tr', 'tt', 'e', 'v', 'yt') if k in e}
                    for e in [x for x in eps if not x.get('yt')][:LATEST] + [x for x in eps if x.get('yt')][:2]],
        })
    write_json(os.path.join(out, 'podcasts.json'), {'updated': int(time.time()), 'shows': index})
    print('%d of %d shows in %.0f s' % (len(index), len(shows), time.time() - started))


if __name__ == '__main__':
    main()
