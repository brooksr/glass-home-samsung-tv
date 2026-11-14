"""Installed on the M5 as ~/Projects/local_llm/podcast-bench/whisper.py; run by remote.sh "transcribe".

    .venv/bin/python whisper.py AUDIO   ->  JSON on stdout: {"model", "secs", "segments": [[start, end, text], ...]}

MLX Whisper large-v3-turbo (~40-55x real time on the M5 Pro: an hour of audio in 1-2 minutes).
Models are kept in ~/Projects/local_llm/models/hf.
"""
import json
import os
import sys
import time

os.environ.setdefault('HF_HOME', os.path.expanduser('~/Projects/local_llm/models/hf'))
os.environ.setdefault('HF_HUB_DISABLE_PROGRESS_BARS', '1')
import mlx_whisper  # noqa: E402

MODEL = 'mlx-community/whisper-large-v3-turbo'
t = time.time()
r = mlx_whisper.transcribe(sys.argv[1], path_or_hf_repo=MODEL, verbose=None, condition_on_previous_text=False)
json.dump({'model': MODEL, 'secs': round(time.time() - t, 1),
           'segments': [[round(s['start'], 2), round(s['end'], 2), s['text'].strip()] for s in r['segments'] if s['text'].strip()]},
          sys.stdout)
