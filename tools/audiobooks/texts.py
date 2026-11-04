#!/usr/bin/env python3
"""Read-along text for the audiobooks: tools/audiobooks/texts/<book id>.json.

LibriVox records from a named edition, most often a Project Gutenberg text.
For those books this fetches that text, splits it into paragraphs and works
out which paragraphs each recorded part covers:

  - Every part starts at a paragraph; the parts follow each other in order.
  - A part's length in characters should match its length in seconds at its
    reader's pace (characters per second, measured across the book).
  - A part likely starts at a heading (CHAPTER VI, BOOK X, LETTER 4), above
    all one whose number or words match the part's title ("Chapter 6").

A beam search picks the start of every part that best fits all three, twice
(the second time with each reader's own pace from the first). Books whose
parts still don't fit (the recording follows another edition, say) get no
text rather than wrong text. Timing within a part is left to the app: it
spreads the part's paragraphs over its length by size, so the highlighted
paragraph is an estimate and a tap on any paragraph jumps the audio there.

    python3 tools/audiobooks/texts.py           # after build.py

Gutenberg texts are cached in tools/audiobooks/.cache. Standard library only.
"""
import json
import math
import os
import re
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
BOOKS = os.path.join(HERE, 'audiobooks.json')
OUT = os.path.join(HERE, 'texts')
CACHE = os.path.join(HERE, '.cache')

# Sources LibriVox gives as an old mirror path rather than an ebook number.
NUMBERS = {'wwhit10': 583}

PRE, POST = 18, 4   # seconds of LibriVox's spoken intro ("This is a LibriVox recording…") and outro
BEAM = 80

HEAD = re.compile(r'^(chapter|chap\.|book|part|letter|stave|canto|act|scene|preface|introduction|prologue|epilogue|'
                  r'conclusion|appendix|essay|section|volume|vol\.|no\.|number|the federalist|federalist)\b', re.I)
ROMAN = re.compile(r'^[IVXLC]+$')
STOP = {'chapter', 'chapters', 'part', 'book', 'the', 'and', 'of', 'a', 'an', 'in', 'to', 'pt', 'bk', 'vol', 'volume', 'section',
        'letter', 'letters', 'stave', 'canto', 'act', 'scene', 'with', 'from', 'end', 'his', 'her', 'for', 'on'}


def get_text(n):
    path = os.path.join(CACHE, f'pg{n}.txt')
    if os.path.exists(path):
        return open(path, encoding='utf-8').read()
    for i in range(3):
        try:
            req = urllib.request.Request(f'https://www.gutenberg.org/cache/epub/{n}/pg{n}.txt',
                                         headers={'User-Agent': 'RocheHome-audiobooks/1.0'})
            with urllib.request.urlopen(req, timeout=60) as r:
                text = r.read().decode('utf-8-sig', errors='replace')
            os.makedirs(CACHE, exist_ok=True)
            open(path, 'w', encoding='utf-8').write(text)
            time.sleep(1)   # gently
            return text
        except Exception:
            time.sleep(3 * (i + 1))
    return None


def ebook_number(url):
    m = re.search(r'gutenberg\.org/(?:etext|ebooks|files|cache/epub)/(\d+)', url or '')
    if m:
        return int(m.group(1))
    for k, n in NUMBERS.items():
        if k in (url or ''):
            return n
    return None


def roman(s):
    vals = {'I': 1, 'V': 5, 'X': 10, 'L': 50, 'C': 100}
    total = 0
    for i, ch in enumerate(s):
        v = vals[ch]
        total += -v if i + 1 < len(s) and vals[s[i + 1]] > v else v
    return total


def paragraphs(text):
    """The book's paragraphs, without Gutenberg's header, footer and illustrations. Verse keeps its lines."""
    text = text.replace('\r\n', '\n')
    m = re.search(r'\*\*\* ?START OF (THE|THIS) PROJECT GUTENBERG[^\n]*', text, re.I)
    if m:
        text = text[m.end():]
    m = re.search(r'\*\*\* ?END OF (THE|THIS) PROJECT GUTENBERG', text, re.I)
    if m:
        text = text[:m.start()]
    # Illustrations go, but a chapter heading set inside one (Pride and Prejudice's "Chapter I.") stays.
    def illustration(m):
        h = re.search(r'\b(chapter|book|letter|stave)\s+[IVXLC\d]+\.?', m.group(0), re.I)
        return '\n\n' + h.group(0) + '\n\n' if h else ''
    text = re.sub(r'\[Illustration[^\]]*\]', illustration, text, flags=re.S)
    out = []
    for block in re.split(r'\n\s*\n', text):
        lines = [ln.strip() for ln in block.split('\n') if ln.strip()]
        if not lines:
            continue
        lines = [ln.replace('_', '') for ln in lines]
        verse = len(lines) >= 2 and sum(len(ln) for ln in lines) / len(lines) < 52 and max(len(ln) for ln in lines) < 75
        p = '\n'.join(lines) if verse else re.sub(r'\s+', ' ', ' '.join(lines))
        if re.fullmatch(r'[\s*.\-_=]+', p):
            continue
        out.append(p)
    return out


KEYS = {'chapter': 'chapter', 'chapters': 'chapter', 'chap': 'chapter', 'ch': 'chapter', 'book': 'book', 'bk': 'book',
        'part': 'part', 'pt': 'part', 'letter': 'letter', 'stave': 'stave', 'canto': 'canto', 'act': 'act', 'scene': 'scene',
        'section': 'section', 'volume': 'volume', 'vol': 'volume', 'no': 'no', 'number': 'no', 'paper': 'no', 'essay': 'no'}
UNITS = 'one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen'.split()
TENS = 'twenty thirty forty fifty sixty seventy eighty ninety'.split()
SPELLED = r'(?:(?:' + '|'.join(TENS) + r')(?:[- ](?:' + '|'.join(UNITS[:9]) + r'))?|' + '|'.join(sorted(UNITS, key=len, reverse=True)) + r')\b'
KEYED = re.compile(r'\b(' + '|'.join(sorted(KEYS, key=len, reverse=True)) + r')\.?\s*([IVXLC]+\b|\d+|' + SPELLED + ')', re.I)


def num(tok):
    """12, XII or twelve (Little Women's "CHAPTER TWO")."""
    t = tok.lower()
    if tok.isdigit():
        return int(tok)
    if ROMAN.match(tok.upper()) and tok.isupper() or ROMAN.match(tok):
        return roman(tok.upper())
    parts = re.split(r'[- ]', t)
    if parts[0] in TENS:
        return 20 + 10 * TENS.index(parts[0]) + (UNITS.index(parts[1]) + 1 if len(parts) > 1 and parts[1] in UNITS else 0)
    return UNITS.index(t) + 1 if t in UNITS else None


def keyed(s):
    """(kind, number) pairs: "Bk 1, Inferno: Canto VI" -> [('book', 1), ('canto', 6)]."""
    return [(KEYS[k.lower()], num(n)) for k, n in KEYED.findall(s) if num(n) is not None]


def bare(s):
    """Numbers standing alone: "01 My Early Home", "XI. THE NINE SITUATIONS", "III"."""
    out = []
    for tok in re.findall(r'(?<![\w.])([IVXLC]+|\d+)(?=[.\s:]|$)', s):
        if tok.isdigit() or len(tok) > 1 or tok in ('I', 'V', 'X'):
            out.append(num(tok))
    return out


def words(s):
    return {w for w in re.findall(r'[a-z]+', s.lower().replace('’', "'")) if len(w) > 3 and w not in STOP}


def continuation(title):
    """A later part of a chapter ("Chapter 1, Part 2", "Mowgli's Brothers (part 2)"): starts mid-text."""
    k = keyed(title)
    later = any(kind == 'part' and n > 1 for kind, n in k)
    return later and (any(kind != 'part' for kind, _ in k) or bool(words(title)))


def heading(p):
    """A chapter heading: one short line, or a short label line with its title under it ("CHAPTER I.\nThe Period")."""
    lines = p.split('\n')
    if len(p) > 120 or len(lines) > 3:
        return False
    if len(lines) > 1:
        return heading(lines[0])
    if HEAD.match(p) or ROMAN.match(p.rstrip('.')) or re.fullmatch(r'\d+\.?', p):
        return True
    if len(p) < 70 and (keyed(p) or re.match(r'\d+\.?\s+[A-Z]', p)) and not re.search(r'[,;!?”"]$', p):
        return True
    letters = [c for c in p if c.isalpha()]
    if letters and sum(c.isupper() for c in letters) / len(letters) > 0.8 and len(p) < 70:
        return True
    # Title Case, short, no sentence punctuation: "Economy", "Mowgli’s Brothers".
    ws = [w for w in re.findall(r"[A-Za-z’']+", p) if len(w) > 3]
    return (len(p) < 50 and ws and not p[0] in '“"‘(' and not re.search(r'[,;:!?”"]$', p)
            and sum(w[0].isupper() for w in ws) / len(ws) >= 0.7)


def matches(title, p):
    """The heading `p` names the part titled `title`. A labelled number in the title ("Canto VI", "No. 1") must
    agree; otherwise a number ("01 - Playing Pilgrims", "I.II: The Mail") or a shared word will do."""
    if not heading(p):
        return False
    line = p.split('\n')[0]
    firsts = {}
    for kind, n in keyed(title):
        if kind != 'part':   # "(part 1)" numbers a recording, not the book
            firsts.setdefault(kind, n)
    hk = keyed(line) or keyed(p)
    hnums = [n for _, n in hk] or bare(line)[:1]
    if firsts:
        if hk:
            return any(firsts.get(kind) == n for kind, n in hk) or (not any(kind in firsts for kind, _ in hk) and hnums[0] in firsts.values())
        return bool(hnums) and hnums[0] in firsts.values()
    tnums = set(bare(title)[:2])
    return bool(tnums & set(hnums[:1])) or bool(words(title) & words(p))


def starts_at(title, paras, a, b):
    """The part's heading is at its start, or just after a book or part heading (\"Part I\" then \"01 My Early Home\")."""
    return any(matches(title, paras[i]) for i in range(a, min(a + 3, b)))


def signal(title):
    return bool(keyed(title) or bare(title) or words(title)) and not continuation(title)


def boundary_cost(p, title):
    """How plausible it is that a part titled `title` starts at paragraph text `p`."""
    if continuation(title):
        return 0.5 if heading(p) else 0.2
    if not signal(title):
        return 0.2 if heading(p) else 1.0
    if matches(title, p):
        return -1.0
    return 0.8 if heading(p) else 2.0


def align(paras, parts, readers):
    """Start paragraph of each part (and the end of the last), or None. parts: [(title, seconds, reader)]."""
    n, m = len(paras), len(parts)
    C = [0]
    for p in paras:
        C.append(C[-1] + len(p))
    speech = [max(s - PRE - POST, s * 0.6) for _, s, _ in parts]
    heads = [i for i, p in enumerate(paras) if heading(p)]
    rate = {r: readers.get(r) or readers['*'] for _, _, r in parts}

    def rc(j, a, b):
        chars = C[b] - C[a]
        if chars <= 0:
            return 99.0
        return 6 * math.log(chars / (rate[parts[j][2]] * speech[j])) ** 2

    # Start: a heading (or the very top) within the first quarter of the text.
    starts = [0] + [h for h in heads if C[h] < C[n] * 0.25]
    # Front matter (title page, contents) is skipped: the first part starts where its own heading is.
    beam = {s: (boundary_cost(paras[s], parts[0][0]), [s]) for s in starts}
    for j in range(m):
        exp = rate[parts[j][2]] * speech[j]
        nxt_title = parts[j + 1][0] if j + 1 < m else ''
        cand = {}
        for a, (cost, path) in beam.items():
            lo, hi = C[a] + 0.45 * exp, C[a] + 2.2 * exp
            qs = set(h for h in heads if lo <= C[h] <= hi)
            # Plain paragraphs near the expected end, for parts split mid-chapter.
            import bisect
            i0, i1 = bisect.bisect_left(C, C[a] + 0.8 * exp), bisect.bisect_right(C, C[a] + 1.25 * exp)
            qs.update(range(max(a + 1, i0), min(n, i1) + 1))
            if j == m - 1:
                qs.add(n)
            for q in qs:
                if q <= a or q > n:
                    continue
                c = cost + rc(j, a, q)
                if j + 1 < m:
                    c += boundary_cost(paras[q], nxt_title) if q < n else 99
                else:
                    c += 2 * (C[n] - C[q]) / C[n]   # unread text after the last part (notes, index)
                if q not in cand or c < cand[q][0]:
                    cand[q] = (c, path)
        if not cand:
            return None
        best = sorted(cand.items(), key=lambda kv: kv[1][0])[:BEAM]
        beam = {q: (c, path + [q]) for q, (c, path) in best}
    end, (cost, path) = min(beam.items(), key=lambda kv: kv[1][0])
    return path


def fit(paras, parts, path):
    """Each part's characters per second against its reader's pace (log ratios), and the paces."""
    C = [0]
    for p in paras:
        C.append(C[-1] + len(p))
    speech = [max(s - PRE - POST, s * 0.6) for _, s, _ in parts]
    by = {}
    for j, (_, _, r) in enumerate(parts):
        a, b = by.get(r, (0, 0))
        by[r] = (a + C[path[j + 1]] - C[path[j]], b + speech[j])
    total = (C[path[-1]] - C[path[0]]) / sum(speech)
    paces = {r: (a / b if b and sum(1 for _, _, x in parts if x == r) >= 3 else total) for r, (a, b) in by.items()}
    paces['*'] = total
    logs = [math.log(max(C[path[j + 1]] - C[path[j]], 1) / (paces[parts[j][2]] * speech[j])) for j in range(len(parts))]
    return logs, paces


def book_text(book):
    chapters = book['chapters']
    texts = [None] * len(chapters)
    srcs = book.get('sources') or []
    notes = []
    # Volumes read from the same text are aligned together.
    groups = []
    for k, s in enumerate(srcs):
        end = srcs[k + 1]['from'] if k + 1 < len(srcs) else len(chapters)
        if groups and groups[-1][0] == s['url']:
            groups[-1][2] = end
        else:
            groups.append([s['url'], s['from'], end])
    for url, a, b in groups:
        n = ebook_number(url)
        if not n:
            notes.append(f'not Gutenberg ({url or "no source"})')
            continue
        raw = get_text(n)
        if not raw:
            notes.append(f'#{n} unavailable')
            continue
        paras = paragraphs(raw)
        parts = [(c[4] if len(c) > 4 else c[0], c[2], c[3] if len(c) > 3 else '') for c in chapters[a:b]]
        total = sum(len(p) for p in paras) * 0.9 / sum(max(s - PRE - POST, s * 0.6) for _, s, _ in parts)
        path = align(paras, parts, {'*': total})
        if path:
            _, paces = fit(paras, parts, path)
            path = align(paras, parts, paces) or path
        if not path:
            notes.append(f'#{n}: no fit')
            continue
        logs, _ = fit(paras, parts, path)
        med = sorted(abs(x) for x in logs)[len(logs) // 2]
        bad = sum(abs(x) > 0.35 for x in logs) / len(logs)
        # The real test: parts whose titles name a chapter must start at that chapter's heading.
        # Continuations ("Chapter 1, Part 2") start mid-chapter and can't be checked this way.
        own = [j for j in range(len(parts)) if not continuation(parts[j][0])]
        named = [j for j in own if signal(parts[j][0])]
        hit = {j for j in named if starts_at(parts[j][0], paras, path[j], path[j + 1])}
        found = len(hit) / len(named) if named else 0
        verdict = (len(named) >= len(own) * 0.5 and found >= 0.75 and bad <= 0.15
                   and (med < 0.15 or (found >= 0.9 and med < 0.2)))
        # A part that misses its heading keeps no text (nor do its continuations): no text beats the wrong text.
        good, ok = set(), True
        for j in range(len(parts)):
            if not continuation(parts[j][0]):
                ok = j in hit or j not in named
            if ok:
                good.add(j)
        notes.append(f'#{n}: {len(parts)} parts, pace off {med:.2f} (median), {len(hit)}/{len(named)} named parts at their heading'
                     + (f', text for {len(good)}' if verdict else ' -> skipped'))
        if verdict:
            for j in good:
                texts[a + j] = paras[path[j]:path[j + 1]]
    return texts, notes


def main():
    books = json.load(open(BOOKS))['books']
    only = set(sys.argv[1:])
    if only:
        books = [b for b in books if b['id'] in only or b['title'] in only]
    os.makedirs(OUT, exist_ok=True)
    with ThreadPoolExecutor(4) as pool:
        results = list(pool.map(book_text, books))
    kept = 0
    for book, (texts, notes) in zip(books, results):
        path = os.path.join(OUT, book['id'] + '.json')
        have = sum(t is not None for t in texts)
        if have:
            kept += 1
            json.dump({'parts': texts}, open(path + '.tmp', 'w'), ensure_ascii=False, separators=(',', ':'))
            os.replace(path + '.tmp', path)
        elif os.path.exists(path) and not only:
            os.remove(path)
        print(f'{"✓" if have else "·"} {book["title"]}: {have}/{len(texts)} parts. ' + '; '.join(notes))
    print(f'{kept} of {len(books)} books have text -> {OUT}')


if __name__ == '__main__':
    main()
