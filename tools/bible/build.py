#!/usr/bin/env python3
"""Builds the Bible reader's texts (data/*.json) for the shareable phone build.

    python3 tools/bible/build.py        # downloads ~50 MB, a minute or so

Every text here is public domain: BSB (dedicated to the public domain in
2023), KJV, WEB, ASV and Young's Literal; the Westminster Leningrad Codex
(Hebrew Old Testament) and the Textus Receptus (Greek New Testament, the
text behind the KJV). Copyrighted versions (NIV, ESV, NLT...) aren't
bundled: the reader links to them on BibleGateway instead.

data/<code>/<book>.json (book 0-65, only those the text has): its chapters,
each a list of verse texts, verse n at index n-1 ("" where a version has no
such verse). The app loads one book at a time. books.json lists the versions
and the 66 books with how many chapters each has.

Standard library only.
"""
import io
import json
import os
import shutil
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, 'data')
SCROLLMAPPER = 'https://raw.githubusercontent.com/scrollmapper/bible_databases/master/formats/json/{}.json'
WEB_ZIP = 'https://ebible.org/Scriptures/eng-web_vpl.zip'

BOOKS = ['Genesis', 'Exodus', 'Leviticus', 'Numbers', 'Deuteronomy', 'Joshua', 'Judges', 'Ruth', '1 Samuel', '2 Samuel',
         '1 Kings', '2 Kings', '1 Chronicles', '2 Chronicles', 'Ezra', 'Nehemiah', 'Esther', 'Job', 'Psalms', 'Proverbs',
         'Ecclesiastes', 'Song of Solomon', 'Isaiah', 'Jeremiah', 'Lamentations', 'Ezekiel', 'Daniel', 'Hosea', 'Joel', 'Amos',
         'Obadiah', 'Jonah', 'Micah', 'Nahum', 'Habakkuk', 'Zephaniah', 'Haggai', 'Zechariah', 'Malachi',
         'Matthew', 'Mark', 'Luke', 'John', 'Acts', 'Romans', '1 Corinthians', '2 Corinthians', 'Galatians', 'Ephesians',
         'Philippians', 'Colossians', '1 Thessalonians', '2 Thessalonians', '1 Timothy', '2 Timothy', 'Titus', 'Philemon',
         'Hebrews', 'James', '1 Peter', '2 Peter', '1 John', '2 John', '3 John', 'Jude', 'Revelation']
# ebible.org's book codes, in the same order.
WEB_CODES = ('GEN EXO LEV NUM DEU JOS JDG RUT 1SA 2SA 1KI 2KI 1CH 2CH EZR NEH EST JOB PSA PRO ECC SOL ISA JER LAM EZE DAN '
             'HOS JOE AMO OBA JON MIC NAH HAB ZEP HAG ZEC MAL MAT MAR LUK JOH ACT ROM 1CO 2CO GAL EPH PHI COL 1TH 2TH 1TI '
             '2TI TIT PHM HEB JAM 1PE 2PE 1JO 2JO 3JO JUD REV').split()

# (code, name, language, scrollmapper file, books it covers)
VERSIONS = [
    ('BSB', 'Berean Standard Bible', 'en', 'BSB', range(66)),
    ('KJV', 'King James Version', 'en', 'KJV', range(66)),
    ('WEB', 'World English Bible', 'en', None, range(66)),
    ('ASV', 'American Standard Version', 'en', 'ASV', range(66)),
    ('YLT', "Young's Literal Translation", 'en', 'YLT', range(66)),
    ('WLC', 'Hebrew (Westminster Leningrad Codex)', 'he', 'WLC', range(39)),
    ('TR', 'Greek (Textus Receptus)', 'el', 'TR', range(39, 66)),
]


def fetch(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'RocheHome-bible/1.0'})
    with urllib.request.urlopen(req, timeout=120) as r:
        return r.read()


def chapters_from(verses_by_chapter):
    """{chapter: {verse: text}} -> [[text, ...], ...] with gaps as ""."""
    out = []
    for c in range(1, max(verses_by_chapter) + 1):
        vs = verses_by_chapter.get(c, {})
        out.append([vs.get(v, '') for v in range(1, max(vs, default=0) + 1)])
    return out


def canonical(name):
    """scrollmapper's book name -> BOOKS's ("II Samuel" -> "2 Samuel", "Revelation of John" -> "Revelation")."""
    for roman, n in (('III ', '3 '), ('II ', '2 '), ('I ', '1 ')):
        if name.startswith(roman):
            name = n + name[len(roman):]
    return {'Revelation of John': 'Revelation'}.get(name, name)


def scrollmapper(name, covers):
    # Matched by name: WLC lists its books in the Hebrew order (Chronicles before Psalms, and so on).
    data = json.loads(fetch(SCROLLMAPPER.format(name)))
    by_name = {canonical(b['name']): b for b in data['books']}
    out = [None] * 66
    for i in covers:
        b = by_name[BOOKS[i]]
        out[i] = chapters_from({c['chapter']: {v['verse']: v['text'].strip() for v in c['verses']} for c in b['chapters']})
    return out


def web():
    z = zipfile.ZipFile(io.BytesIO(fetch(WEB_ZIP)))
    text = z.read('eng-web_vpl.txt').decode('utf-8-sig')
    index = {code: i for i, code in enumerate(WEB_CODES)}
    acc = [dict() for _ in range(66)]
    for line in text.splitlines():
        code, _, rest = line.partition(' ')
        if code not in index:
            continue  # the Apocrypha
        ref, _, verse = rest.partition(' ')
        c, v = (int(x) for x in ref.split(':'))
        acc[index[code]].setdefault(c, {})[v] = verse.strip()
    return [chapters_from(a) for a in acc]


def main():
    shutil.rmtree(OUT, ignore_errors=True)
    os.makedirs(OUT)
    counts = None
    for code, name, lang, src, covers in VERSIONS:
        b = web() if src is None else scrollmapper(src, list(covers))
        if code == 'KJV':
            counts = [len(x) for x in b]
        os.makedirs(os.path.join(OUT, code), exist_ok=True)
        for i, chapters in enumerate(b):
            if chapters:
                with open(os.path.join(OUT, code, f'{i}.json'), 'w') as f:
                    json.dump(chapters, f, ensure_ascii=False, separators=(',', ':'))
        print(f'{code}: {sum(len(x) for x in b if x)} chapters')
    with open(os.path.join(OUT, 'books.json'), 'w') as f:
        json.dump({'versions': [[c, n, l] for c, n, l, _, _ in VERSIONS],
                   'books': [[n, k] for n, k in zip(BOOKS, counts)]}, f, ensure_ascii=False, separators=(',', ':'))


if __name__ == '__main__':
    main()
