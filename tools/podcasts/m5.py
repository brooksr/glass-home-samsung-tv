#!/usr/bin/env python3
"""Roche Home podcasts: the M5 MacBook's local Qwen models, from the Ubuntu laptop.

    python3 m5.py wake          wake it (if asleep) and say how long that took
    python3 m5.py test [MODEL]  wake it and ask MODEL a one-line question
    python3 m5.py log           the wake and run history

The M5 runs ~/Projects/local_llm/podcast-bench/remote.sh (tools/podcasts/m5-remote.sh), the only
thing this laptop's key may run there: ping, run <model> (an OpenAI chat request on stdin), stop.
It sleeps when idle. Connecting usually wakes it (Wake for network access is on, and it's on AC);
if not, a Wake-on-LAN packet is sent and connecting is retried for WAKE_SECS. It also wakes at
3:00 every night (pmset repeat) for the regular shows' batch. Every wake and run is appended to
m5.jsonl in adfree's state folder, for checking how reliable waking is over time.

Models: FAST (Qwen3.6-35B-A3B: ~10-40 s an episode) and GOOD (Qwen3.8-27B: ~2.5-4 min, and closer
to Claude's answers in the 2026-11-11 bench).
"""
import json
import os
import socket
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
HOST = 'm5'                                   # ~/.ssh/config: HostName 192.168.254.154, BatchMode
MAC = 'c0:c7:db:33:f6:c0'                     # its Wi-Fi (en0)
FAST = 'Qwen3.6-35B-A3B-MLX-8bit'
GOOD = 'Qwen3.8-27B-MLX-8bit'
WAKE_SECS = 45
LOG = os.path.join(os.environ.get('ADFREE_STATE') or os.path.join(HERE, 'adfree-state'), 'm5.jsonl')


def record(rec):
    rec['at'] = int(time.time())
    os.makedirs(os.path.dirname(LOG), exist_ok=True)
    with open(LOG, 'a', encoding='utf-8') as f:
        f.write(json.dumps(rec) + '\n')


def ssh(args, data=None, timeout=15):
    return subprocess.run(['ssh', '-o', 'ConnectTimeout=6', HOST] + args, input=data, capture_output=True, timeout=timeout)


def magic_packet():
    payload = b'\xff' * 6 + bytes.fromhex(MAC.replace(':', '')) * 16
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        for addr in ('192.168.254.255', '255.255.255.255'):
            for port in (9, 7):
                s.sendto(payload, (addr, port))


def wake():
    """True once the M5 answers. Tries connecting, then Wake-on-LAN, for up to WAKE_SECS."""
    t0 = time.time()
    tries, sent = 0, False
    while time.time() - t0 < WAKE_SECS:
        tries += 1
        try:
            if ssh(['ping'], timeout=10).stdout.strip() == b'ok':
                record({'what': 'wake', 'ok': True, 'secs': round(time.time() - t0, 1), 'tries': tries, 'wol': sent})
                return True
        except subprocess.TimeoutExpired:
            pass
        if not sent:
            magic_packet()
            sent = True
        time.sleep(3)
    record({'what': 'wake', 'ok': False, 'secs': round(time.time() - t0, 1), 'tries': tries, 'wol': sent})
    return False


def complete(model, messages, max_tokens=12000, think=False, timeout=1800):
    """(answer text, what happened), or (None, why) if the M5 can't be woken or fails."""
    if not wake():
        return None, {'model': model, 'error': 'asleep'}
    body = {'model': model, 'messages': messages, 'temperature': 0, 'max_tokens': max_tokens,
            'chat_template_kwargs': {'enable_thinking': think}}
    t0 = time.time()
    try:
        r = ssh(['run', model], data=json.dumps(body).encode(), timeout=timeout)
        j = json.loads(r.stdout)
        text = (j['choices'][0]['message'].get('content') or '').split('</think>')[-1]
        u = j.get('usage') or {}
        used = {'model': model, 'secs': round(time.time() - t0, 1), 'in': u.get('prompt_tokens'), 'out': u.get('completion_tokens')}
        record(dict(used, what='run', ok=True))
        return text, used
    except Exception as e:     # noqa: BLE001 - asleep again, out of memory, cut off: the caller falls back
        used = {'model': model, 'secs': round(time.time() - t0, 1), 'error': str(e)[:200]}
        record(dict(used, what='run', ok=False))
        return None, used


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else ''
    if cmd == 'wake':
        t0 = time.time()
        print('awake' if wake() else 'no answer', 'after %.0f s' % (time.time() - t0))
    elif cmd == 'test':
        model = sys.argv[2] if len(sys.argv) > 2 else FAST
        t0 = time.time()
        print(complete(model, [{'role': 'user', 'content': 'Reply with just: ok'}], max_tokens=20)[0], 'in %.0f s' % (time.time() - t0))
    elif cmd == 'log':
        for line in open(LOG, encoding='utf-8'):
            print(line.rstrip())
    else:
        sys.exit(__doc__)


if __name__ == '__main__':
    main()
