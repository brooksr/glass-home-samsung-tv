import json, sys, time, re, os, urllib.parse, urllib.request, urllib.error
cand_dir, picks, rawdir = sys.argv[1], sys.argv[2], sys.argv[3]
cands = json.load(open(os.path.join(cand_dir, 'candidates.json')))
UA = {'User-Agent': 'RocheHome/1.0 (personal TV wallpaper picker)'}
out = []
for n, line in enumerate(open(picks).read().split('\n')):
    if not line.strip(): continue
    sheet, pos, times, weather = line.split()
    idx = int(open(os.path.join(cand_dir, 'sheets', 'grp_' + sheet)).read().split()[int(pos) - 1])
    c = cands[idx]
    name = c['title'].split(':', 1)[1]
    fn = 'ca-%02d.jpg' % (n + 1)
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
                'time': times.split(','), 'weather': weather.split(','), 'candidate': idx})
json.dump(out, open(os.path.join(rawdir, 'manifest.json'), 'w'), indent=1)
print(len(out))
