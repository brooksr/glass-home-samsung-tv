"""Roche Home podcasts: transcribe an episode and have a model mark its ads and chapters.

The second pass after adfree.py's cuts, for the sponsor reads the host records into the show
itself (which no comparison of downloads can find), and for chapters and a transcript.

Transcription: MLX Whisper on the M5 (~40-75x real time), woken if asleep (m5.py); otherwise
faster-whisper on the laptop's CPU (base.en when someone is listening now, ~19x; small.en in the
batch, ~7.5x). Reading it: the M5's Qwen (FAST for an episode playing now, GOOD in the nightly
batch), or Claude Haiku through `claude -p` when the M5 can't be reached and someone is waiting.

The episode's audio as the players hear it is what gets transcribed (the ad-free copy when
there is one), so every time found is exact in that audio: the ads become skips the players
make by themselves.
"""
import json
import os
import re
import subprocess
import time

import m5

HERE = os.path.dirname(os.path.abspath(__file__))
CLAUDE = os.path.expanduser('~/.local/bin/claude')

SYSTEM = ('You read podcast transcripts and mark the advertising and the chapters. '
          'Answer with JSON only, no prose and no code fences.')
VERSION = 2      # what the answer holds; episodes done by an older version are redone in the nightly batch
ASK = '''Below is the transcript of the podcast episode "{title}" from "{show}", one line per
sentence, each starting with its time in seconds in brackets, and the episode's description
from the podcast feed.

1. Find every advertisement: sponsor reads by the host, produced ads, promos for other
   shows, and pleas to subscribe, join a Patreon or buy merchandise. Give where each
   starts (the time of its first line) and ends (the time of the first line after it,
   when the show resumes). Don't mark the show's own content, guests plugging their own
   book or work in conversation, or the opening and closing of the show.
2. Divide the rest of the episode into chapters by topic: usually 4 to 10, each with a
   short title (under 8 words), starting where the topic starts. The first starts at 0. Give
   each 2 to 4 points: short phrases (under 15 words) saying what is said or claimed there.
3. Summarize the whole episode in 2 or 3 plain sentences: who is on it and what they cover.
4. Give the description back with the advertising taken out: sponsor lists and offers, promo
   codes, ad-choices and privacy notices, and links to anything but the show itself. Keep the
   rest word for word. Empty if nothing is left.

Answer as:
{{"ads": [{{"start": 123, "end": 185, "sponsor": "Name", "kind": "host-read|produced|promo|self"}}],
  "chapters": [{{"start": 0, "title": "...", "points": ["...", "..."]}}],
  "summary": "...",
  "description": "..."}}

Description:
{desc}

Transcript:
{text}'''


def lines(segments):
    """Whisper's phrases joined into sentences of up to ~8 s: [{'t', 'x'}]. Short, so an ad's start and end
    (given as the time of a line) land within a few seconds."""
    out = []
    for s, e, text in segments:
        text = re.sub(r'^Speaker \d+:?\s+', '', text)     # a turbo-model habit
        last = out[-1] if out else None
        if last and s - last['t'] < 8 and not re.search(r'[.!?]["”]?$', last['x']):
            last['x'] += ' ' + text
        else:
            out.append({'t': int(s), 'x': text})
    return out


def transcribe(audio, urgent):
    """(segments [[start, end, text]], what was used)."""
    t0 = time.time()
    if m5.wake():
        try:
            with open(audio, 'rb') as f:
                r = subprocess.run(['ssh', '-o', 'ConnectTimeout=6', m5.HOST, 'transcribe'], stdin=f,
                                   capture_output=True, timeout=1800)
            j = json.loads(r.stdout)
            return j['segments'], {'where': 'm5', 'model': j['model'], 'secs': round(time.time() - t0, 1)}
        except Exception as e:     # noqa: BLE001 - fell asleep, killed, out of memory: the laptop does it
            m5.record({'what': 'transcribe', 'ok': False, 'error': str(e)[:200]})
    model = 'base.en' if urgent else 'small.en'
    r = subprocess.run(['nice', '-n', '10', os.path.join(HERE, '.venv/bin/python'), os.path.join(HERE, 'whisper_cpu.py'), audio, model],
                       capture_output=True, timeout=7200)
    if r.returncode != 0:
        raise RuntimeError('whisper: ' + r.stderr.decode('utf-8', 'replace')[-300:])
    j = json.loads(r.stdout)
    return j['segments'], {'where': 'laptop', 'model': j['model'], 'secs': round(time.time() - t0, 1)}


def claude(prompt):
    """Claude Haiku, with Claude Code's own setup left out (~540 tokens instead of ~32k)."""
    t0 = time.time()
    r = subprocess.run([CLAUDE, '-p', '--model', 'haiku', '--output-format', 'json', '--system-prompt', SYSTEM,
                        '--tools', '', '--disable-slash-commands', '--strict-mcp-config', '--setting-sources', '',
                        '--no-session-persistence'], input=prompt, capture_output=True, text=True, timeout=600, cwd='/tmp')
    j = json.loads(r.stdout)
    if j.get('is_error'):
        raise RuntimeError(str(j.get('result'))[:200])
    u = j.get('usage', {})
    return j.get('result', ''), {'model': 'claude-haiku', 'secs': round(time.time() - t0, 1),
                                 'in': u.get('input_tokens', 0) + u.get('cache_creation_input_tokens', 0) + u.get('cache_read_input_tokens', 0),
                                 'out': u.get('output_tokens'), 'usd_list': round(j.get('total_cost_usd', 0), 5)}


def analyze(text_lines, title, show, desc, urgent, allow_claude):
    """({'ads', 'chapters', 'summary', 'description'}, what was used), or (None, why) when nothing answered."""
    prompt = ASK.format(title=title, show=show, desc=desc or '(none)', text='\n'.join('[%d] %s' % (l['t'], l['x']) for l in text_lines))
    model = m5.FAST if urgent else m5.GOOD
    answer, used = m5.complete(model, [{'role': 'system', 'content': SYSTEM}, {'role': 'user', 'content': prompt}])
    if answer is None and allow_claude:
        answer, used = claude(prompt)
    if answer is None:
        return None, used
    m = re.search(r'\{.*\}', answer, re.S)
    try:
        found = json.loads(m.group(0)) if m else None
    except ValueError:
        found = None
    if not isinstance(found, dict):
        raise RuntimeError('%s answered something other than JSON: %s' % (used.get('model'), answer[:120]))
    return found, used
