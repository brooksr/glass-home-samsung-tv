# Downloads holiday picks at 1920 px and writes their manifest entries.
# python3 -I fetch-holiday.py candidates.json holiday-picks.txt raw/
# Each pick line: candidate index, holiday, times of day (comma-separated).
import json, sys, time, re, os, urllib.parse, urllib.request, urllib.error
cands, picks, rawdir = json.load(open(sys.argv[1])), sys.argv[2], sys.argv[3]
UA = {'User-Agent': 'RocheHome/1.0 (personal TV wallpaper picker)'}
out, count = [], {}
for line in open(picks).read().split('\n'):
    line = line.split('#')[0].strip()
    if not line: continue
    idx, holiday, times = line.split()
    c = cands[int(idx)]
    count[holiday] = count.get(holiday, 0) + 1
    fn = '%s-%02d.jpg' % (holiday, count[holiday])
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
    out.append({'file': fn, 'title': title, 'author': c['author'], 'licence': c['licence'], 'source': c['page'],
                'holiday': holiday, 'time': times.split(',')})
json.dump(out, open(os.path.join(rawdir, 'holiday-manifest.json'), 'w'), indent=1)
print(len(out), count)
