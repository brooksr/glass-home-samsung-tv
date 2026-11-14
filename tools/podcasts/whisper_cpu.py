"""The laptop's own transcription, when the M5 can't be woken: faster-whisper on the CPU.

    .venv/bin/python whisper_cpu.py AUDIO MODEL   ->  JSON like m5-whisper.py's

Runs in its own Python 3.12 environment (~/roche-podcasts/.venv: `uv venv --python 3.12 .venv`,
`uv pip install --python .venv/bin/python faster-whisper`). Measured on the i7-6700HQ with 6
threads: base.en ~19x real time (an hour in ~3 min), small.en ~7.5x (~8 min). Audio is decoded
with ffmpeg here: faster-whisper's own decoder (PyAV) fails with the installed version.
"""
import json
import subprocess
import sys
import time

import numpy as np
from faster_whisper import WhisperModel

path, model = sys.argv[1], sys.argv[2]
pcm = subprocess.run(['ffmpeg', '-v', 'error', '-i', path, '-ac', '1', '-ar', '16000', '-f', 's16le', '-'],
                     capture_output=True, check=True).stdout
audio = np.frombuffer(pcm, np.int16).astype(np.float32) / 32768
t = time.time()
segs, _ = WhisperModel(model, device='cpu', compute_type='int8', cpu_threads=6).transcribe(audio, beam_size=1, vad_filter=True)
out = [[round(s.start, 2), round(s.end, 2), s.text.strip()] for s in segs if s.text.strip()]
json.dump({'model': 'faster-whisper ' + model, 'secs': round(time.time() - t, 1), 'segments': out}, sys.stdout)
