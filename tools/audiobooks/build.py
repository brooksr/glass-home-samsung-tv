#!/usr/bin/env python3
"""Builds audiobooks.json for the Android app's Audiobooks tab.

For each pick below it asks LibriVox for every English recording of the
book, drops dramatic readings, abridgements and excerpts, and keeps the one
downloaded most on the Internet Archive (where LibriVox keeps the MP3s). The
chapters (title, MP3, seconds) go in the file, so the app needs no lookups.

    python3 tools/audiobooks/build.py        # about a minute

Standard library only. Picks LibriVox doesn't have are listed at the end.
"""
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, 'audiobooks.json')

# (shelf, title, author's last name[, LibriVox's title, or its volumes in order])
# LibriVox leaves off a leading The/A and often keeps the subtitle.
PICKS = [
    ('Great Novels', 'Pride and Prejudice', 'Austen'),
    ('Great Novels', 'Emma', 'Austen'),
    ('Great Novels', 'Moby-Dick', 'Melville', 'Moby Dick, or the Whale'),
    ('Great Novels', 'The Great Gatsby', 'Fitzgerald'),
    ('Great Novels', 'Jane Eyre', 'Brontë'),
    ('Great Novels', 'Wuthering Heights', 'Brontë'),
    ('Great Novels', 'Middlemarch', 'Eliot'),
    ('Great Novels', 'Great Expectations', 'Dickens'),
    ('Great Novels', 'A Tale of Two Cities', 'Dickens'),
    ('Great Novels', 'Bleak House', 'Dickens'),
    ('Great Novels', 'Anna Karenina', 'Tolstoy'),
    ('Great Novels', 'War and Peace', 'Tolstoy', [f'War and Peace, Book {b}' for b in (
        '01: 1805', '02: 1805', '03: 1805', '04: 1806', '05: 1806-1807', '06: 1808-1810',
        '07: 1810-1811', '08: 1811-1812', '09: 1812', '10: 1812', '11: 1812', '12: 1812',
        '13: 1812', '14: 1812', '15: 1812-1813', '16: First Epilogue 1813-1820', '17: Second Epilogue')]),
    ('Great Novels', 'Crime and Punishment', 'Dostoyevsky'),
    ('Great Novels', 'The Brothers Karamazov', 'Dostoyevsky'),
    ('Great Novels', 'Les Misérables', 'Hugo', [f'Misérables, Volume {i}' for i in range(1, 6)]),
    ('Great Novels', 'Don Quixote', 'Cervantes'),
    ('Great Novels', 'Madame Bovary', 'Flaubert'),
    ('Great Novels', 'Adventures of Huckleberry Finn', 'Twain'),
    ('Great Novels', 'The Scarlet Letter', 'Hawthorne'),
    ('Great Novels', 'Heart of Darkness', 'Conrad'),
    ('Great Novels', 'The Picture of Dorian Gray', 'Wilde'),
    ('Great Novels', 'Mrs. Dalloway', 'Woolf'),
    ('Great Novels', 'To the Lighthouse', 'Woolf'),
    ('Great Novels', 'The Sun Also Rises', 'Hemingway'),
    ('Great Novels', 'The Age of Innocence', 'Wharton'),
    ('Great Novels', 'Little Women', 'Alcott'),
    ('Great Novels', 'The Metamorphosis', 'Kafka'),
    ('Great Novels', 'Ulysses', 'Joyce'),

    ('Adventure & Mystery', 'The Adventures of Sherlock Holmes', 'Doyle'),
    ('Adventure & Mystery', 'The Hound of the Baskervilles', 'Doyle'),
    ('Adventure & Mystery', 'The Moonstone', 'Collins'),
    ('Adventure & Mystery', 'The Woman in White', 'Collins'),
    ('Adventure & Mystery', 'The Count of Monte Cristo', 'Dumas'),
    ('Adventure & Mystery', 'The Three Musketeers', 'Dumas'),
    ('Adventure & Mystery', 'Treasure Island', 'Stevenson'),
    ('Adventure & Mystery', 'Robinson Crusoe', 'Defoe'),
    ('Adventure & Mystery', "Gulliver's Travels", 'Swift'),
    ('Adventure & Mystery', 'The Call of the Wild', 'London'),
    ('Adventure & Mystery', 'Around the World in Eighty Days', 'Verne'),

    ('Science Fiction & Horror', 'Frankenstein', 'Shelley', 'Frankenstein, or The Modern Prometheus'),
    ('Science Fiction & Horror', 'Dracula', 'Stoker'),
    ('Science Fiction & Horror', 'The Strange Case of Dr. Jekyll and Mr. Hyde', 'Stevenson'),
    ('Science Fiction & Horror', 'The War of the Worlds', 'Wells'),
    ('Science Fiction & Horror', 'The Time Machine', 'Wells'),
    ('Science Fiction & Horror', 'The Invisible Man', 'Wells'),
    ('Science Fiction & Horror', 'Twenty Thousand Leagues Under the Sea', 'Verne'),
    ('Science Fiction & Horror', 'Journey to the Center of the Earth', 'Verne', 'Journey to the Centre of the Earth'),
    ('Science Fiction & Horror', 'The Turn of the Screw', 'James'),
    ('Science Fiction & Horror', 'The Call of Cthulhu', 'Lovecraft'),

    ('Philosophy & Ideas', 'Meditations', 'Aurelius'),
    ('Philosophy & Ideas', 'The Art of War', 'Sun Tzu'),
    ('Philosophy & Ideas', 'The Prince', 'Machiavelli'),
    ('Philosophy & Ideas', 'The Republic', 'Plato'),
    ('Philosophy & Ideas', 'On the Origin of Species', 'Darwin', 'Origin of Species by Means of Natural Selection'),
    ('Philosophy & Ideas', 'Walden', 'Thoreau'),
    ('Philosophy & Ideas', 'Common Sense', 'Paine'),
    ('Philosophy & Ideas', 'The Federalist Papers', 'Hamilton'),
    ('Philosophy & Ideas', 'On Liberty', 'Mill'),
    ('Philosophy & Ideas', 'The Wealth of Nations', 'Smith', ['Wealth of Nations, Book 1', 'Wealth of Nations, Book 2 and 3', 'Wealth of Nations, Book 4', 'Wealth of Nations, Book 5']),
    ('Philosophy & Ideas', 'Democracy in America', 'Tocqueville', ['Democracy in America Vol. I', 'Democracy in America Vol. II']),
    ('Philosophy & Ideas', 'The Communist Manifesto', 'Marx'),
    ('Philosophy & Ideas', 'Thus Spake Zarathustra', 'Nietzsche', 'Thus Spake Zarathustra: A Book for All and None'),
    ('Philosophy & Ideas', 'Beyond Good and Evil', 'Nietzsche'),
    ('Philosophy & Ideas', 'Relativity: The Special and General Theory', 'Einstein'),
    ('Philosophy & Ideas', 'Moral Letters to Lucilius', 'Seneca', 'Moral letters to Lucilius'),
    ('Philosophy & Ideas', "A Room of One's Own", 'Woolf'),

    ('History & Lives', 'Narrative of the Life of Frederick Douglass', 'Douglass'),
    ('History & Lives', 'The Autobiography of Benjamin Franklin', 'Franklin'),
    ('History & Lives', 'Up from Slavery', 'Washington', 'Up from Slavery: An Autobiography'),
    ('History & Lives', 'The Souls of Black Folk', 'Du Bois'),
    ('History & Lives', 'The Histories', 'Herodotus', ["Herodotus' Histories Vol 1", "Herodotus' Histories Vol 2", "Herodotus' Histories Vol 3"]),
    ('History & Lives', 'The History of the Peloponnesian War', 'Thucydides'),
    ('History & Lives', 'The Jungle', 'Sinclair'),
    ('History & Lives', 'Personal Memoirs of U. S. Grant', 'Grant'),

    ('Poetry & Drama', 'The Odyssey', 'Homer'),
    ('Poetry & Drama', 'The Iliad', 'Homer'),
    ('Poetry & Drama', 'Beowulf', ''),
    ('Poetry & Drama', 'The Divine Comedy', 'Alighieri'),
    ('Poetry & Drama', 'Paradise Lost', 'Milton'),
    ('Poetry & Drama', 'Leaves of Grass', 'Whitman'),
    ('Poetry & Drama', 'The Waste Land', 'Eliot'),
    ('Poetry & Drama', 'Hamlet', 'Shakespeare'),
    ('Poetry & Drama', 'Macbeth', 'Shakespeare', 'Tragedy of Macbeth'),
    ('Poetry & Drama', 'The Canterbury Tales', 'Chaucer'),

    ("Children's Classics", "Alice's Adventures in Wonderland", 'Carroll'),
    ("Children's Classics", 'The Wonderful Wizard of Oz', 'Baum'),
    ("Children's Classics", 'Peter Pan', 'Barrie'),
    ("Children's Classics", 'Winnie-the-Pooh', 'Milne'),
    ("Children's Classics", 'The Wind in the Willows', 'Grahame'),
    ("Children's Classics", 'Anne of Green Gables', 'Montgomery'),
    ("Children's Classics", 'The Secret Garden', 'Burnett'),
    ("Children's Classics", 'The Jungle Book', 'Kipling'),
    ("Children's Classics", 'The Adventures of Tom Sawyer', 'Twain'),
    ("Children's Classics", 'Black Beauty', 'Sewell'),
]

SKIP = re.compile(r'dramatic|abridged|excerpt|selections|play\b|\bpoem\b|condensed|children|retold|young folks|study guide|'
                  r'commentary|summary|for boys|for girls|in latin|in french|in german|\(german|\(french|parody|sequel', re.I)


def get(url, tries=3):
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers={'User-Agent': 'RocheHome-audiobooks/1.0'})
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.load(r)
        except Exception:
            if i == tries - 1:
                return None
            time.sleep(2 * (i + 1))


def norm(s):
    s = re.sub(r'\(.*?\)', '', s.lower())
    s = s.replace('&', 'and').replace('ë', 'e').replace('é', 'e')
    return re.sub(r'[^a-z0-9]+', ' ', s).strip()


def strip_article(s):
    return re.sub(r'^(the|a|an) ', '', s)


def plain(html, limit=600):
    t = re.sub(r'<[^>]+>', '', html or '')
    t = re.sub(r'\s+', ' ', t).strip()
    t = re.sub(r'\s*\(?-? ?Summary by [^)]*\)?\s*$', '', t, flags=re.I)
    if len(t) > limit:
        t = t[:limit].rsplit(' ', 1)[0] + '…'
    return t


def candidates(title, author):
    want = strip_article(norm(title))  # (version 2) and the like are dropped by norm()
    # LibriVox's title search matches from the start; "The X" and "X" are both tried.
    found = {}
    for t in {title, re.sub(r'^(The|A|An) ', '', title)}:
        q = urllib.parse.quote('^' + t)
        data = get(f'https://librivox.org/api/feed/audiobooks/?title={q}&format=json&extended=1&limit=50') or {}
        for b in data.get('books') or []:
            found[b['id']] = b
    out = []
    for b in found.values():
        if b.get('language') != 'English' or SKIP.search(b['title']) or not b.get('sections'):
            continue
        if strip_article(norm(b['title'])) != want:
            continue
        names = ' '.join(f"{a.get('first_name', '')} {a.get('last_name', '')}" for a in b.get('authors') or [])
        if author and norm(author) not in norm(names):
            continue
        if not int(b.get('totaltimesecs') or 0):
            continue
        out.append(b)
    return out


def ia_id(b):
    m = re.search(r'archive\.org/details/([^/?#]+)', b.get('url_iarchive') or '')
    return m.group(1) if m else ''


def downloads(ids):
    ids = [i for i in ids if i]
    if not ids:
        return {}
    q = urllib.parse.quote('identifier:(' + ' OR '.join(ids) + ')')
    data = get(f'https://archive.org/advancedsearch.php?q={q}&fl[]=identifier&fl[]=downloads&rows=100&output=json') or {}
    return {d['identifier']: int(d.get('downloads') or 0) for d in data.get('response', {}).get('docs', [])}


def best_of(title, author):
    """The most-downloaded English recording of one LibriVox title."""
    books = candidates(title, author)
    if not books:
        return None, 0
    dl = downloads([ia_id(b) for b in books])
    best = max(books, key=lambda b: (dl.get(ia_id(b), 0), -int(b['id'])))
    return best, dl.get(ia_id(best), 0)


def chapters_of(b, prefix=''):
    out = []
    for s in sorted(b['sections'], key=lambda s: int(s.get('section_number') or 0)):
        url = (s.get('listen_url') or '').replace('://www.archive.org/', '://archive.org/').replace('http://', 'https://')
        if url:
            reader = ((s.get('readers') or [{}])[0]).get('reader_id') or ''
            # [title, MP3, seconds, reader, LibriVox's own title (for texts.py)]
            out.append([prefix + (plain(s.get('title'), 200) or f"Part {s.get('section_number')}"), url, int(s.get('playtime') or 0),
                        reader, plain(s.get('title'), 200)])
    return out


def cover(ia):
    """The recording's full cover (LibriVox's art, ~1200 px), else the Archive's 180 px tile."""
    files = (get(f'https://archive.org/metadata/{ia}/files') or {}).get('result') or []
    jpgs = [f for f in files if f.get('format') in ('JPEG', 'Item Image') and f['name'].lower().endswith('.jpg')]
    for f in sorted(jpgs, key=lambda f: -int(f.get('size') or 0)):
        url = f'https://archive.org/download/{ia}/' + urllib.parse.quote(f['name'])
        if loads(url):  # some listed files won't download (500)
            return url
    return f'https://archive.org/services/img/{ia}'


def loads(url):
    try:
        req = urllib.request.Request(url, method='HEAD', headers={'User-Agent': 'RocheHome-audiobooks/1.0'})
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status == 200 and r.headers.get('content-type', '').startswith('image/')
    except Exception:
        return False


def pick(entry):
    shelf, title, author = entry[:3]
    lv = entry[3] if len(entry) > 3 else title
    volumes = lv if isinstance(lv, list) else [lv]
    parts = [best_of(v, author) for v in volumes]
    if any(b is None for b, _ in parts):
        return entry, None
    first = parts[0][0]
    chapters = []
    sources = []   # the text each volume was read from, and its first part
    for i, (b, _) in enumerate(parts):
        sources.append({'url': b.get('url_text_source') or '', 'from': len(chapters)})
        word = 'Book' if re.search(r'Book \d+:', volumes[i]) else 'Vol.'  # Wealth of Nations' "Book 2 and 3" is one volume
        chapters += chapters_of(b, f'{word} {i + 1} · ' if len(parts) > 1 else '')
    # Latin script only: LibriVox adds the original ("Plato (Πλάτων)", "Sun Tzu 孙武").
    authors = [re.sub(r'\s*\(.*?\)|[^\x00-\u024f]+', '', f"{a.get('first_name', '')} {a.get('last_name', '')}").strip()
               for a in first.get('authors') or []]
    ia = ia_id(first)
    return entry, {
        'id': 'lv' + first['id'],
        'title': title,
        'author': ', '.join(a for a in authors if a and 'anonymous' not in a.lower()) or 'Anonymous',
        'shelf': shelf,
        'desc': plain(first.get('description')),
        'art': cover(ia) if ia else '',
        'secs': sum(int(b.get('totaltimesecs') or 0) for b, _ in parts),
        'librivox': first.get('url_librivox') or '',
        'downloads': sum(d for _, d in parts),
        'sources': sources,
        'chapters': chapters,
    }


def main():
    with ThreadPoolExecutor(6) as pool:
        results = list(pool.map(pick, PICKS))
    books = [b for _, b in results if b and b['chapters']]
    missing = [e for e, b in results if not b or not b['chapters']]
    with open(OUT + '.tmp', 'w') as f:
        json.dump({'_note': 'Made by build.py from LibriVox (public domain recordings); edit PICKS there, not this file.',
                   'books': books}, f, ensure_ascii=False, separators=(',', ':'))
    os.replace(OUT + '.tmp', OUT)
    print(f'{len(books)} books, {sum(len(b["chapters"]) for b in books)} chapters -> {OUT}')
    for e in missing:
        print(f'  not found: {e[1]} ({e[2]})', file=sys.stderr)


if __name__ == '__main__':
    main()
