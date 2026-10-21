import json, sys, urllib.parse, urllib.request, urllib.error, re, html, time
API = 'https://commons.wikimedia.org/w/api.php'
UA = {'User-Agent': 'RocheHome/1.0 (personal TV wallpaper picker)'}
OK = re.compile(r'^(public domain|cc0|pd|cc-zero)', re.I)
def query(q, n=40):
    p = {'action': 'query', 'format': 'json', 'generator': 'search', 'gsrnamespace': 6, 'gsrlimit': n,
         'gsrsearch': q + ' filetype:bitmap', 'prop': 'imageinfo', 'iiprop': 'url|size|extmetadata'}
    req = urllib.request.Request(API + '?' + urllib.parse.urlencode(p), headers=UA)
    for wait in (5, 15, 45, 90):
        time.sleep(wait)
        try:
            return json.load(urllib.request.urlopen(req, timeout=30)).get('query', {}).get('pages', {}).values()
        except urllib.error.HTTPError as e:
            if e.code != 429: raise
            print('429, backing off', file=sys.stderr)
    return []
def clean(s): return html.unescape(re.sub('<[^>]+>', '', s or '')).strip()
out = {}
for mood, q in [(l.split('|')[0], l.split('|')[1]) for l in sys.stdin.read().strip().splitlines()]:
    for pg in query(q):
        ii = (pg.get('imageinfo') or [{}])[0]; m = ii.get('extmetadata', {})
        lic = clean(m.get('LicenseShortName', {}).get('value'))
        w, h = ii.get('width', 0), ii.get('height', 0)
        if not OK.match(lic) or w < 1920 or not h or w / h < 1.4 or w / h > 2.2: continue
        t = pg['title']
        if t in out: continue
        out[t] = {'title': t, 'mood': mood, 'url': ii.get('url'), 'page': ii.get('descriptionurl'),
                  'licence': lic, 'author': clean(m.get('Artist', {}).get('value'))[:80],
                  'desc': clean(m.get('ImageDescription', {}).get('value'))[:160], 'w': w, 'h': h}
json.dump(list(out.values()), sys.stdout, indent=1)
