#!/usr/bin/env python3
"""Brookstream's Watch tab: the movies and shows on our streaming services, with IMDb ratings.

    TMDB_KEY=... python3 -I build.py OUT_DIR      (or TMDB_KEY in the repo's .env)

Writes OUT_DIR/watch.json. Where things come from:
- TMDB (themoviedb.org) lists what each US service carries, from JustWatch's data:
  the most popular titles per service plus its best-rated ones (see PAGES), then
  each title's details: genres, runtime or seasons, age rating, cast, trailer,
  IMDb ID, and every one of our services it's on.
- IMDb's free dataset (title.ratings.tsv.gz, refreshed daily) gives the IMDb
  rating and vote count.

Catalogues change weekly, so run it weekly on the Home Assistant laptop (README.md);
the app reads it from Home Assistant, falling back to the copy bundled at build time.
Responses are cached in OUT_DIR/cache for a day, so a rerun is quick. Standard library only.
"""
import concurrent.futures
import gzip
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
API = 'https://api.themoviedb.org/3'
IMDB_RATINGS = 'https://datasets.imdbws.com/title.ratings.tsv.gz'
UA = 'Brookstream/1.0 (personal watch list)'
REGION = 'US'

# Our services and the TMDB provider IDs that count as each (GET /watch/providers/movie?watch_region=US).
# Ad tiers count; rentals and purchases don't. YouTube is its free movies (and the few Premium originals).
SERVICES = [
    ('netflix', 'Netflix', [8, 1796]),
    ('prime', 'Prime Video', [9, 2100, 613]),
    ('disney', 'Disney+', [337]),
    ('hulu', 'Hulu', [15]),
    ('paramount', 'Paramount+', [2303, 2616]),
    ('tubi', 'Tubi', [73]),
    ('youtube', 'YouTube', [235, 188]),
]
PROVIDER = {pid: key for key, _, ids in SERVICES for pid in ids}

# Discover pages (20 titles each) per service: by popularity, then the best rated with enough votes.
PAGES = {'movie': (30, 10), 'tv': (15, 5)}
MIN_VOTES = 25          # TMDB votes, to leave out the obscure filler (Tubi and YouTube have thousands)
TOP_RATED_VOTES = 500   # ...and for the best-rated pass
OVERVIEW = 400          # characters of plot kept
CAST = 5
CACHE_DAYS = 1


def key():
    k = os.environ.get('TMDB_KEY', '')
    if not k and os.path.exists(os.path.join(ROOT, '.env')):
        for line in open(os.path.join(ROOT, '.env')):
            m = re.match(r'\s*(?:export\s+)?TMDB_KEY\s*=\s*(.*?)\s*$', line)
            if m:
                k = m.group(1).strip('"\'')
    if not k:
        sys.exit('No TMDB_KEY: set it in the environment or the repo .env.')
    return k


class Tmdb:
    def __init__(self, k, cache):
        self.k, self.cache = k, cache
        os.makedirs(cache, exist_ok=True)

    def get(self, path, **params):
        params['api_key'] = self.k
        name = re.sub(r'[^a-z0-9]+', '_', (path + '_' + '_'.join(f'{a}{b}' for a, b in sorted(params.items()) if a != 'api_key')).lower())
        f = os.path.join(self.cache, name[:180] + '.json')
        if os.path.exists(f) and time.time() - os.path.getmtime(f) < CACHE_DAYS * 86400:
            return json.load(open(f))
        url = API + path + '?' + urllib.parse.urlencode(params)
        for wait in (0, 2, 10, 30):
            time.sleep(wait)
            try:
                with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': UA}), timeout=30) as r:
                    data = json.load(r)
                break
            except urllib.error.HTTPError as e:
                if e.code == 404:
                    return None
                if e.code != 429 and e.code < 500:
                    raise
                last = e
            except (urllib.error.URLError, TimeoutError) as e:
                last = e
        else:
            raise last
        json.dump(data, open(f, 'w'))
        return data


def discover(t, kind):
    """{tmdb id: set of our services} for what each service lists."""
    found = {}
    popular, best = PAGES[kind]
    jobs = []
    for skey, _, ids in SERVICES:
        base = dict(watch_region=REGION, with_watch_providers='|'.join(map(str, ids)),
                    with_watch_monetization_types='flatrate|free|ads', include_adult='false')
        jobs += [(skey, dict(base, sort_by='popularity.desc', **{'vote_count.gte': MIN_VOTES}, page=p)) for p in range(1, popular + 1)]
        jobs += [(skey, dict(base, sort_by='vote_average.desc', **{'vote_count.gte': TOP_RATED_VOTES}, page=p)) for p in range(1, best + 1)]
    with concurrent.futures.ThreadPoolExecutor(8) as pool:
        for skey, data in zip([j[0] for j in jobs], pool.map(lambda j: t.get(f'/discover/{kind}', **j[1]), jobs)):
            for r in (data or {}).get('results', []):
                found.setdefault(r['id'], set()).add(skey)
    return found


def imdb_ratings(cache):
    f = os.path.join(cache, 'title.ratings.tsv.gz')
    if not os.path.exists(f) or time.time() - os.path.getmtime(f) > 86400:
        with urllib.request.urlopen(urllib.request.Request(IMDB_RATINGS, headers={'User-Agent': UA}), timeout=120) as r:
            open(f + '.part', 'wb').write(r.read())
        os.replace(f + '.part', f)
    out = {}
    with gzip.open(f, 'rt') as rows:
        next(rows)
        for row in rows:
            tconst, rating, votes = row.rstrip('\n').split('\t')
            out[tconst] = (float(rating), int(votes))
    return out


def trailer(videos):
    vs = [v for v in (videos or {}).get('results', []) if v.get('site') == 'YouTube' and v.get('type') in ('Trailer', 'Teaser')]
    vs.sort(key=lambda v: (v['type'] != 'Trailer', not v.get('official'), v.get('iso_639_1') != 'en'))
    return vs[0]['key'] if vs else ''


def age_rating(d, kind):
    if kind == 'movie':
        for c in d.get('release_dates', {}).get('results', []):
            if c['iso_3166_1'] == REGION:
                certs = [r['certification'] for r in c['release_dates'] if r.get('certification')]
                return certs[0] if certs else ''
    else:
        for c in d.get('content_ratings', {}).get('results', []):
            if c['iso_3166_1'] == REGION:
                return c.get('rating', '')
    return ''


def short(text, limit):
    text = re.sub(r'\s+', ' ', text or '').strip()
    return text if len(text) <= limit else text[:limit].rsplit(' ', 1)[0] + '…'


def title(t, kind, tid, listed):
    extra = 'external_ids,videos,credits,watch/providers,' + ('release_dates' if kind == 'movie' else 'content_ratings')
    d = t.get(f'/{kind}/{tid}', append_to_response=extra)
    if not d:
        return None
    us = d.get('watch/providers', {}).get('results', {}).get(REGION, {})
    on = {PROVIDER[p['provider_id']] for k in ('flatrate', 'free', 'ads') for p in us.get(k, []) if p['provider_id'] in PROVIDER}
    services = [s for s, _, _ in SERVICES if s in (on or listed)]
    if not services:
        return None
    crew = d.get('credits', {}).get('crew', [])
    if kind == 'movie':
        name, date = d.get('title', ''), d.get('release_date', '')
        by = [c['name'] for c in crew if c.get('job') == 'Director'][:2]
    else:
        name, date = d.get('name', ''), d.get('first_air_date', '')
        by = [c['name'] for c in d.get('created_by', [])][:2]
    out = {
        'id': ('m' if kind == 'movie' else 't') + str(tid),
        'kind': kind,
        'title': name,
        'year': int(date[:4]) if date[:4].isdigit() else 0,
        'genres': [g['name'] for g in d.get('genres', [])],
        'rated': age_rating(d, kind),
        'overview': short(d.get('overview'), OVERVIEW),
        'poster': d.get('poster_path') or '',
        'backdrop': d.get('backdrop_path') or '',
        'imdb': d.get('external_ids', {}).get('imdb_id') or '',
        'tmdb': round(d.get('vote_average') or 0, 1),
        'popularity': round(d.get('popularity') or 0, 1),
        'cast': [c['name'] for c in d.get('credits', {}).get('cast', [])[:CAST]],
        'by': by,
        'trailer': trailer(d.get('videos')),
        'services': services,
    }
    if kind == 'movie':
        out['runtime'] = d.get('runtime') or 0
    else:
        out['seasons'] = d.get('number_of_seasons') or 0
        last = d.get('last_air_date') or ''
        out['ended'] = d.get('status') in ('Ended', 'Canceled')
        out['lastYear'] = int(last[:4]) if last[:4].isdigit() else 0
    return out


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    out_dir = sys.argv[1]
    cache = os.path.join(out_dir, 'cache')
    t = Tmdb(key(), cache)
    # Expired responses go (they're ~400 MB a run, mostly cast lists).
    if os.path.isdir(cache):
        for n in os.listdir(cache):
            f = os.path.join(cache, n)
            if n.endswith('.json') and time.time() - os.path.getmtime(f) > CACHE_DAYS * 86400:
                os.remove(f)
    ratings = imdb_ratings(cache)
    print(f'IMDb ratings: {len(ratings)}')
    titles = []
    for kind in ('movie', 'tv'):
        listed = discover(t, kind)
        print(f'{kind}: {len(listed)} listed')
        with concurrent.futures.ThreadPoolExecutor(8) as pool:
            got = list(pool.map(lambda i: title(t, kind, i, listed[i]), listed))
        titles += [g for g in got if g]
    for x in titles:
        r = ratings.get(x['imdb'])
        if r:
            x['imdbRating'], x['imdbVotes'] = r
    titles.sort(key=lambda x: -x['popularity'])
    data = {
        'updated': int(time.time()),
        'images': 'https://image.tmdb.org/t/p/',
        'services': [{'key': k, 'label': label} for k, label, _ in SERVICES],
        'titles': titles,
    }
    path = os.path.join(out_dir, 'watch.json')
    with open(path + '.part', 'w') as f:
        json.dump(data, f, ensure_ascii=False, separators=(',', ':'))
    os.replace(path + '.part', path)
    per = {k: sum(k in x['services'] for x in titles) for k, _, _ in SERVICES}
    print(f'{len(titles)} titles ({os.path.getsize(path) // 1024} KB): ' + ', '.join(f'{k} {n}' for k, n in per.items()))


if __name__ == '__main__':
    main()
