"""Builds Roche Home's library: catalog.json (Internet Archive items with their
playable files) and books/<id>.json (Project Gutenberg texts in chapters).

    python3 -I build.py sources.json OUT_DIR

Only public-domain material: the curated Internet Archive identifiers in
sources.json and Project Gutenberg books. Video files are the MP4s the TV can
play (h.264 / MPEG4, never HEVC); audio is the 64 kbps MP3 chapters."""
import html, json, os, re, subprocess, sys, time

UA = {'User-Agent': 'RocheHome/1.0 (personal TV library)'}
VIDEO_PREF = ['h.264', 'h.264 IA', 'MPEG4', '512Kb MPEG4']
AUDIO_PREF = ['64Kbps MP3', 'VBR MP3', '128Kbps MP3']


def get(url, binary=False):
    # curl, not urllib: this Mac's Python rejects some valid certificate chains.
    for wait in (0.5, 5, 20):
        time.sleep(wait)
        run = subprocess.run(['curl', '-sSfL', '--max-time', '120', '-A', UA['User-Agent'], url], capture_output=True)
        if run.returncode == 0:
            return run.stdout if binary else run.stdout.decode('utf-8', 'replace')
        last = RuntimeError(url + ': ' + run.stderr.decode()[:200])
    raise last


def clean(text, limit):
    text = html.unescape(re.sub(r'<[^>]+>', ' ', text if isinstance(text, str) else ' '.join(text or [])))
    text = re.sub(r'\s+', ' ', text).strip()
    return text if len(text) <= limit else text[:limit].rsplit(' ', 1)[0] + '…'


def seconds(v):
    try:
        if ':' in str(v):
            parts = [float(p) for p in str(v).split(':')]
            return round(sum(p * 60 ** i for i, p in enumerate(reversed(parts))))
        return round(float(v))
    except ValueError:
        return None


def episode_name(f, title):
    t = f.get('title') or re.sub(r'\.(mp4|mp3)$', '', f['name'].split('/')[-1], flags=re.I)
    t = re.sub(r'(_512kb|\.ia)$', '', t, flags=re.I).replace('_', ' ').strip()
    # "Dragnet (1951) - S01E01 - The Human Bomb" -> "S01E01 - The Human Bomb"
    parts = t.split(' - ')
    if len(parts) > 1 and parts[0].split(' ')[0].lower() == title.split(' ')[0].lower():
        t = ' - '.join(parts[1:])
    return t if t.lower() != title.lower() else ''


def item(identifier, kind):
    meta = json.loads(get('https://archive.org/metadata/' + identifier))
    m = meta.get('metadata', {})
    title = clean(m.get('title', identifier), 80)
    pref = AUDIO_PREF if kind == 'audiobooks' else VIDEO_PREF
    by_base = {}
    for f in meta.get('files', []):
        fmt = f.get('format', '')
        if fmt not in pref or (kind != 'audiobooks' and not f['name'].endswith('.mp4')):
            continue
        # LibriVox keeps each chapter twice (name.mp3 and name_64kb.mp3): one base.
        base = re.sub(r'(_512kb|_64kb|\.ia)?\.(mp4|mp3)$', '', f['name'], flags=re.I)
        if kind != 'audiobooks' and seconds(f.get('length')) is not None and seconds(f.get('length')) < 120:
            continue                          # DVD menus and trailers
        rank = pref.index(fmt)
        if base not in by_base or rank < by_base[base][0]:
            by_base[base] = (rank, f)
    files = [f for _, f in sorted(by_base.values(), key=lambda x: (x[1].get('track', '').split('/')[0].zfill(4), x[1]['name']))]
    if kind == 'audiobooks':
        files.sort(key=lambda f: (int(re.sub(r'\D.*', '', f.get('track', '') or '0') or 0), f['name']))
    if kind in ('tv', 'kids') and EXCLUDE:
        kept = [f for f in files if not EXCLUDE.search(f.get('title', '') + ' ' + f['name'])]
        for f in files:
            if f not in kept:
                print('  left out', identifier, f['name'], file=sys.stderr)
        files = kept
    # An item's ".ia" re-encode of a file it also has under another name: keep one.
    lengths = {}
    for f in files:
        lengths.setdefault(seconds(f.get('length')), []).append(f)
    def stem(f):
        return re.sub(r'[^a-z]', '', f['name'].split('/')[-1].lower())[:10]
    files = [f for f in files if not ('.ia.' in f['name'] and any(
        g is not f and stem(g) == stem(f) for g in lengths[seconds(f.get('length'))]))]
    if kind == 'movies' and files:           # one film: its longest copy (several are often the same film)
        files = [max(files, key=lambda f: seconds(f.get('length')) or 0)]
    out = [{'f': f['name'], 't': episode_name(f, title), 's': seconds(f.get('length'))} for f in files]
    year = str(m.get('year') or m.get('date') or '')[:4]
    return {'id': identifier, 'title': title, 'year': year if year.isdigit() else '',
            'desc': clean(m.get('description', ''), 320), 'files': out}


HEADING = re.compile(r'^(?:CHAPTER|Chapter|STAVE|Letter|LETTER)\s+[IVXLC\d]+\.?.*$|^[IVXLC]+\.\s+[A-Z][A-Z\'’\- ,]+$', re.M)
ROMAN = {'I': 1, 'V': 5, 'X': 10, 'L': 50, 'C': 100}


def roman(r):
    total = 0
    for a, b in zip(r, r[1:] + ' '):
        total += -ROMAN[a] if ROMAN.get(b, 0) > ROMAN[a] else ROMAN[a]
    return total


def heading(head, first):
    # "CHAPTER II." + "Down the Rabbit-Hole" -> "Chapter 2 · Down the Rabbit-Hole";
    # "VII. THE ADVENTURE OF ..." -> "7 · The Adventure of ..."
    m = re.match(r'(?i)(chapter|stave|letter)\s+([IVXLC]+|\d+)\.?\s*(.*)$', head)
    if m:
        n = m.group(2).upper()
        label = m.group(1).capitalize() + ' ' + str(int(n) if n.isdigit() else roman(n))
        rest = m.group(3).strip(' .[]')
    else:
        m = re.match(r'([IVXLC]+)\.\s+(.*)$', head)
        label, rest = str(roman(m.group(1))), m.group(2)
    if not rest and first:
        rest = first
    if rest.isupper():
        rest = ' '.join(w if w in ('OF', 'THE', 'A', 'IN', 'AND') and i else w.capitalize() for i, w in enumerate(rest.split())).replace(' OF ', ' of ').replace(' THE ', ' the ').replace(' IN ', ' in ').replace(' AND ', ' and ').replace(' A ', ' a ')
    return label + (' · ' + rest if rest else '')


def book(src, out_dir):
    n = src['gutenberg']
    text = get('https://www.gutenberg.org/cache/epub/%d/pg%d.txt' % (n, n))
    start = re.search(r'\*\*\* ?START OF .*?\*\*\*', text)
    end = re.search(r'\*\*\* ?END OF .*?\*\*\*', text)
    body = text[start.end() if start else 0:end.start() if end else len(text)].replace('\r\n', '\n')
    marks = [(m.start(), m.group(0).strip()) for m in HEADING.finditer(body)]
    chapters = []
    for i, (pos, head) in enumerate(marks):
        stop = marks[i + 1][0] if i + 1 < len(marks) else len(body)
        chunk = body[pos + len(head):stop]
        if len(chunk.strip()) < 300:          # a contents line, not a chapter
            continue
        chapters.append((head, chunk))
    if not chapters:
        chapters = [(src['title'], body)]
    result = []
    for head, chunk in chapters:
        # Gutenberg marks italics as _word_; the TV shows plain text.
        paras = [re.sub(r'_([^_]+)_', r'\1', re.sub(r'\s+', ' ', p)).strip() for p in re.split(r'\n\s*\n', chunk)]
        paras = [p for p in paras if p and not re.fullmatch(r'[\W_]+', p) and not p.startswith('[Illustration')]
        # A short first line without an ending ("Down the Rabbit-Hole") is the chapter's title.
        words = paras[0].split() if paras else []
        titled = words and sum(w[:1].isupper() for w in words if len(w) > 3) >= max(1, len([w for w in words if len(w) > 3])) * 0.6
        first = paras[0].strip('.') if paras and len(paras[0]) < 60 and re.search(r'[A-Za-z]{3}', paras[0]) and titled and not re.search(r'[.!"”]$', paras[0].rstrip('.') + ('' if paras[0].endswith('.') and paras[0][:-1].istitle() else '')) else ''
        if first:
            paras.pop(0)
        if head == src['title']:
            title = head
        else:
            title = heading(head, first)
        result.append({'title': title, 'paras': paras})
    os.makedirs(os.path.join(out_dir, 'books'), exist_ok=True)
    bid = 'pg%d' % n
    json.dump({'id': bid, 'title': src['title'], 'author': src['author'], 'chapters': result},
              open(os.path.join(out_dir, 'books', bid + '.json'), 'w'), separators=(',', ':'))
    cover = get('https://www.gutenberg.org/cache/epub/%d/pg%d.cover.medium.jpg' % (n, n), binary=True)
    open(os.path.join(out_dir, 'books', bid + '.jpg'), 'wb').write(cover)
    words = sum(len(p.split()) for c in result for p in c['paras'])
    return {'id': bid, 'title': src['title'], 'author': src['author'], 'chapters': len(result),
            'minutes': round(words / 230), 'audio': src.get('audio')}


EXCLUDE = None


def main():
    global EXCLUDE
    sources, out_dir = json.load(open(sys.argv[1])), sys.argv[2]
    EXCLUDE = re.compile(sources['exclude'], re.I) if sources.get('exclude') else None
    os.makedirs(out_dir, exist_ok=True)
    catalog = {'built': time.strftime('%Y-%m-%d'), 'rows': []}
    for kind, label in [('movies', 'Movies'), ('tv', 'TV'), ('kids', 'Kids'), ('audiobooks', 'Audiobooks')]:
        items = []
        for identifier in sources[kind]:
            try:
                it = item(identifier, kind)
            except Exception as error:
                print('skip', identifier, error, file=sys.stderr); continue
            if not it['files']:
                print('skip', identifier, 'no playable files', file=sys.stderr); continue
            it['title'] = sources.get('titles', {}).get(identifier, it['title'])
            items.append(it)
            print(kind, identifier, len(it['files']), 'files', file=sys.stderr)
        catalog['rows'].append({'kind': kind, 'label': label, 'items': items})
    catalog['rows'].append({'kind': 'books', 'label': 'Books', 'items': [book(b, out_dir) for b in sources['books']]})
    json.dump(catalog, open(os.path.join(out_dir, 'catalog.json'), 'w'), separators=(',', ':'))


if __name__ == '__main__':
    main()
