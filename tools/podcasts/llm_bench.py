#!/usr/bin/env python3
"""Roche Home podcasts: try language models at finding ads and chapters in transcripts.

    python3 llm_bench.py OUT claude EPISODE_ID...   Claude Haiku through `claude -p` (fast; uses the subscription)
    python3 llm_bench.py OUT qwen EPISODE_ID...     Qwen in the local Ollama (slow on this CPU; free)
    python3 llm_bench.py OUT openai EPISODE_ID...   any OpenAI-compatible server: LLM_URL (default
                                                    http://127.0.0.1:8089/v1), LLM_MODEL, LLM_THINK=1 to let it reason
                                                    (e.g. mlx_lm.server on the M5; see ~/Projects/local_llm there)
    python3 llm_bench.py OUT report                 a table of everything recorded
    python3 llm_bench.py OUT power power.txt        a Mac: credit runs with the power powermetrics logged

OUT is build.py's folder; transcripts are read from OUT/extras/<id>.json (the show's
own, for now; Whisper's later). Every run is appended to llm-bench.jsonl beside this
script: the model's answer, time taken, tokens, cost at list prices, the subscription's
5-hour and weekly usage before and after each batch (for the whole account: other use
in the same minutes shows up too), and for comparison what adfree.py's phrase search
finds in the same transcript. For tuning prompts, chunking and which model does what.
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
LOG = os.path.join(HERE, 'llm-bench.jsonl')
CLAUDE = os.path.expanduser('~/.local/bin/claude')
QWEN = 'qwen3:4b-instruct-2507-q4_K_M'
OLLAMA = 'http://127.0.0.1:11434/api/generate'

SYSTEM = ('You read podcast transcripts and mark the advertising and the chapters. '
          'Answer with JSON only, no prose and no code fences.')
ASK = '''Below is the transcript of the podcast episode "{title}" from "{show}", one line per
sentence, each starting with its time in seconds in brackets.

1. Find every advertisement: sponsor reads by the host, produced ads, promos for other
   shows, and pleas to subscribe, join a Patreon or buy merchandise. Give where each
   starts (the time of its first line) and ends (the time of the first line after it,
   when the show resumes). Don't mark the show's own content, guests plugging their own
   book or work in conversation, or the opening and closing of the show.
2. Divide the rest of the episode into chapters by topic: usually 4 to 10, each with a
   short title (under 8 words), starting where the topic starts.

Answer as:
{{"ads": [{{"start": 123, "end": 185, "sponsor": "Name", "kind": "host-read|produced|promo|self"}}],
  "chapters": [{{"start": 0, "title": "..."}}]}}

Transcript:
{text}'''


def busy_cpu():
    """CPU seconds spent by the whole machine so far (Linux; None elsewhere): the model
    runs in another process (Ollama), so its work is measured machine-wide."""
    try:
        with open('/proc/stat') as f:
            v = [int(x) for x in f.readline().split()[1:]]
    except OSError:
        return None
    return (sum(v) - v[3] - v[4]) / os.sysconf('SC_CLK_TCK')


def attribute_power(log_path, power_path):
    """Credit each run on this Mac with the power powermetrics logged during it.

    Run powermetrics for the whole batch (piped, it holds samples back):
        sudo powermetrics --samplers cpu_power,gpu_power -i 1000 -o power.txt
    Each sample is headed "*** Sampled system activity (<date>) (<n>ms elapsed) ***" and
    has "Combined Power (CPU + GPU + ANE): <n> mW"."""
    text = open(power_path, encoding='utf-8', errors='replace').read()
    samples = []
    for m in re.finditer(r'Sampled system activity \((.+?)\) \(([\d.]+)ms elapsed\).*?Combined Power \(CPU \+ GPU \+ ANE\): (\d+) mW', text, re.S):
        t = time.mktime(time.strptime(m.group(1)[:24], '%a %b %d %H:%M:%S %Y'))
        samples.append((t, float(m.group(2)) / 1000, int(m.group(3)) / 1000))
    rows = [json.loads(l) for l in open(log_path, encoding='utf-8')]
    host = os.uname().nodename
    for r in rows:
        if r.get('host') != host or 'secs' not in r or 'at' not in r:
            continue
        inside = [w for t, _, w in samples if r['at'] <= t <= r['at'] + r['secs'] + 1]
        if inside:
            avg = sum(inside) / len(inside)
            r['power'] = {'avg_w': round(avg, 1), 'wh': round(avg * r['secs'] / 3600, 4), 'samples': len(inside)}
            r['est_wh'] = r['power']['wh']
    with open(log_path, 'w', encoding='utf-8') as f:
        f.write(''.join(json.dumps(r, ensure_ascii=False) + '\n' for r in rows))
    print('%d samples; power credited to %d runs' % (len(samples), sum(1 for r in rows if 'power' in r)))


def run_openai(prompt):
    url = os.environ.get('LLM_URL', 'http://127.0.0.1:8089/v1').rstrip('/') + '/chat/completions'
    body = {'model': os.environ.get('LLM_MODEL', 'default'), 'temperature': 0, 'max_tokens': 12000,
            'messages': [{'role': 'system', 'content': SYSTEM}, {'role': 'user', 'content': prompt}],
            'chat_template_kwargs': {'enable_thinking': os.environ.get('LLM_THINK') == '1'}}
    t = time.time()
    r = urllib.request.urlopen(urllib.request.Request(url, data=json.dumps(body).encode(),
                                                      headers={'Content-Type': 'application/json'}), timeout=3600)
    j = json.loads(r.read())
    u = j.get('usage') or {}
    text = j['choices'][0]['message'].get('content') or ''
    return {'secs': round(time.time() - t, 1), 'in': u.get('prompt_tokens'), 'out': u.get('completion_tokens'),
            'cost_usd': 0, 'raw': text.split('</think>')[-1]}


def config():
    try:
        return json.load(open(os.path.join(HERE, 'adfree.json')))
    except (OSError, ValueError):
        return {}


def transcript(out, eid):
    x = json.load(open(os.path.join(out, 'extras', eid + '.json')))['transcript']
    lines = []
    for ln in x['lines']:
        who = (ln.get('s') + ': ') if ln.get('s') else ''
        lines.append('[%d] %s%s' % (ln['t'], who, ln['x']))
    return x, '\n'.join(lines)


def episode(out, eid):
    for f in os.listdir(os.path.join(out, 'shows')):
        d = json.load(open(os.path.join(out, 'shows', f)))
        for e in d['eps']:
            if e['id'] == eid:
                return d, e
    raise SystemExit('no episode ' + eid)


def answer_json(text):
    m = re.search(r'\{.*\}', text or '', re.S)
    try:
        return json.loads(m.group(0)) if m else None
    except ValueError:
        return None


def usage():
    """The subscription's use as Claude Code reports it: {'session': %, 'week': %}."""
    try:
        r = subprocess.run([CLAUDE, '-p', '/usage'], capture_output=True, text=True, timeout=60, cwd='/tmp')
    except Exception:     # noqa: BLE001
        return None
    s = re.search(r'Current session: (\d+)%', r.stdout)
    w = re.search(r'Current week \(all models\): (\d+)%', r.stdout)
    return {'session': int(s.group(1)) if s else None, 'week': int(w.group(1)) if w else None}


def run_claude(prompt):
    cmd = [CLAUDE, '-p', '--model', 'haiku', '--output-format', 'json', '--system-prompt', SYSTEM,
           '--tools', '', '--disable-slash-commands', '--strict-mcp-config', '--setting-sources', '',
           '--no-session-persistence']
    t = time.time()
    r = subprocess.run(cmd, input=prompt, capture_output=True, text=True, timeout=600, cwd='/tmp')
    took = time.time() - t
    j = json.loads(r.stdout)
    u = j.get('usage', {})
    return {'secs': round(took, 1), 'in': u.get('input_tokens', 0) + u.get('cache_creation_input_tokens', 0) + u.get('cache_read_input_tokens', 0),
            'out': u.get('output_tokens'), 'cost_usd': round(j.get('total_cost_usd', 0), 5), 'raw': j.get('result', ''),
            'error': j.get('is_error') and j.get('result')}


def run_qwen(prompt):
    body = {'model': QWEN, 'system': SYSTEM, 'prompt': prompt, 'stream': False, 'format': 'json',
            'options': {'num_ctx': 24576, 'temperature': 0}}
    t = time.time()
    r = urllib.request.urlopen(urllib.request.Request(OLLAMA, data=json.dumps(body).encode(),
                                                      headers={'Content-Type': 'application/json'}), timeout=3600)
    j = json.loads(r.read())
    return {'secs': round(time.time() - t, 1), 'in': j.get('prompt_eval_count'), 'out': j.get('eval_count'),
            'prompt_secs': round(j.get('prompt_eval_duration', 0) / 1e9, 1), 'cost_usd': 0, 'raw': j.get('response', ''),
            'truncated': (j.get('prompt_eval_count') or 0) >= 24576 - 50}


def bench(out, model, ids):
    sys.path.insert(0, HERE)
    import adfree
    before = usage() if model == 'claude' else None
    for eid in ids:
        try:
            show, ep = episode(out, eid)
        except SystemExit as e:
            print(e)
            continue
        tr, text = transcript(out, eid)
        prompt = ASK.format(title=ep['t'], show=show['title'], text=text)
        name = {'claude': 'claude-haiku', 'qwen': QWEN}.get(model) or (os.path.basename(os.environ.get('LLM_MODEL', 'openai').rstrip('/'))
                                                                  + ('+think' if os.environ.get('LLM_THINK') == '1' else ''))
        rec = {'at': int(time.time()), 'model': name, 'ep': eid, 'show': show['title'],
               'title': ep['t'], 'dur': ep.get('s'), 'chars': len(text), 'lines': len(tr['lines']),
               'phrase_search': [[s, e, sc] for s, e, sc in adfree.transcript_ranges({'transcript': tr}, ep.get('s') or 99999)]}
        cpu0 = busy_cpu()
        try:
            rec.update({'claude': run_claude, 'qwen': run_qwen, 'openai': run_openai}[model](prompt))
        except Exception as e:     # noqa: BLE001
            rec['error'] = str(e)[:300]
        cpu1 = busy_cpu()
        cfg = config()
        rec['host'] = os.uname().nodename
        if cpu0 is not None:
            rec['busy_cpu_s'] = round(cpu1 - cpu0, 1)       # the whole machine, other work included
            rec['est_wh'] = round(rec['busy_cpu_s'] * cfg.get('watts_per_cpu_second', 4.5) / 3600, 3)
            rec['est_usd_power'] = round(rec['est_wh'] / 1000 * cfg.get('kwh_price', 0.35), 5)
        found = answer_json(rec.get('raw'))
        rec['ads'] = (found or {}).get('ads')
        rec['chapters'] = (found or {}).get('chapters')
        rec['parsed'] = found is not None
        with open(LOG, 'a', encoding='utf-8') as f:
            f.write(json.dumps(rec, ensure_ascii=False) + '\n')
        print('%-12s %-32.32s %5.0fs  in %6s out %5s  $%.4f list  ~%s Wh  %d ads %s chapters %s' % (
            rec['model'][:12], show['title'], rec.get('secs', 0), rec.get('in'), rec.get('out'), rec.get('cost_usd', 0), rec.get('est_wh'),
            len(rec['ads'] or []), len(rec['chapters'] or []), rec.get('error') or ''), flush=True)
    if model == 'claude':
        after = usage()
        with open(LOG, 'a', encoding='utf-8') as f:
            f.write(json.dumps({'at': int(time.time()), 'batch': ids, 'usage_before': before, 'usage_after': after}) + '\n')
        print('subscription use before %s, after %s (the whole account)' % (before, after))


def report():
    rows = [json.loads(l) for l in open(LOG, encoding='utf-8')]
    for r in rows:
        if 'batch' in r:
            print('  usage: before %s after %s for %d episodes' % (r['usage_before'], r['usage_after'], len(r['batch'])))
            continue
        print('%-12.12s %-30.30s %5ss in %6s out %5s $%-7s ~%sWh parsed %-5s ads %s' % (
            r['model'], r['show'], r.get('secs'), r.get('in'), r.get('out'), r.get('cost_usd'), r.get('est_wh'), r.get('parsed'),
            [(a.get('start'), a.get('end'), a.get('sponsor')) for a in (r.get('ads') or [])]))
        print('%44s phrase search: %s' % ('', [(s, e) for s, e, _ in r['phrase_search']]))


if __name__ == '__main__':
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    if sys.argv[2] == 'report':
        report()
    elif sys.argv[2] == 'power':
        attribute_power(LOG, sys.argv[3])
    else:
        bench(sys.argv[1], sys.argv[2], sys.argv[3:])
