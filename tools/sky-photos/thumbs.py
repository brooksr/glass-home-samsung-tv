import json, sys, time, urllib.parse, urllib.request, urllib.error, os
cands, outdir = json.load(open(sys.argv[1])), sys.argv[2]
UA = {'User-Agent': 'RocheHome/1.0 (personal TV wallpaper picker)'}
for i, c in enumerate(cands):
    path = os.path.join(outdir, '%03d.jpg' % i)
    if os.path.exists(path): continue
    name = c['title'].split(':', 1)[1]
    url = 'https://commons.wikimedia.org/wiki/Special:FilePath/' + urllib.parse.quote(name) + '?width=320'
    for wait in (0.6, 5, 20):
        time.sleep(wait)
        try:
            data = urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30).read()
            open(path, 'wb').write(data); break
        except urllib.error.HTTPError as e:
            if e.code != 429: print(i, e.code, file=sys.stderr); break
