# Downloads holiday or month picks at 1920 px and writes their manifest entries.
# python3 -I fetch-seasonal.py candidates.json picks.txt raw/
# Each pick line: candidate index, holiday or month name, times of day
# (comma-separated). Months get `month: [n]`, holidays `holiday: name`.
import json, sys, time, re, os, urllib.parse, urllib.request, urllib.error
cands, picks, rawdir = json.load(open(sys.argv[1])), sys.argv[2], sys.argv[3]
MONTHS = ['january', 'february', 'march', 'april', 'may', 'june', 'july', 'august',
          'september', 'october', 'november', 'december']
UA = {'User-Agent': 'RocheHome/1.0 (personal TV wallpaper picker)'}
out, count = [], {}
for line in open(picks).read().split('\n'):
    line = line.split('#')[0].strip()
    if not line: continue
    idx, season, times = line.split()
    c = cands[int(idx)]
    count[season] = count.get(season, 0) + 1
    fn = '%s-%02d.jpg' % (season, count[season])
    name = c['title'].split(':', 1)[1]
    raw = os.path.join(rawdir, fn)
    if not os.path.exists(raw):
        url = 'https://commons.wikimedia.org/wiki/Special:FilePath/' + urllib.parse.quote(name) + '?width=1920'
        for wait in (1.5, 10, 30, 60):
            time.sleep(wait)
            try:
                open(raw, 'wb').write(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60).read()); break
            except urllib.error.HTTPError as e:
                if e.code != 429: print(fn, e.code, file=sys.stderr); break
    title = c['desc'] or re.sub(r'\.(jpe?g|png|tiff?)$', '', name, flags=re.I).replace('_', ' ')
    title = re.split(r'(?<=[a-z])\. ', title)[0][:90]
    e = {'file': fn, 'title': title, 'author': c['author'], 'licence': c['licence'], 'source': c['page']}
    if season in MONTHS: e['month'] = [MONTHS.index(season) + 1]
    else: e['holiday'] = season
    e['time'] = times.split(',')
    out.append(e)
json.dump(out, open(os.path.join(rawdir, 'seasonal-manifest.json'), 'w'), indent=1)
print(len(out), count)
