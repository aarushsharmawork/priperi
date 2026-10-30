#!/usr/bin/env python3
"""Run bounded local Android fixture batches on a disposable emulator."""
from pathlib import Path
import argparse
import json
import os
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--batch', action='append', help='Run only this batch; may be repeated')
args = parser.parse_args()
batches = json.loads((ROOT / 'tools/android-test-batches.json').read_text())
if args.batch and set(args.batch) - batches.keys():
    parser.error('Unknown batch')
output = ROOT / 'local-only/test-results'
output.mkdir(parents=True, exist_ok=True)
env = dict(os.environ, PRIPERI_ACCOUNT_TESTS='yes', PRIPERI_SPEED_TESTS='yes', PRIPERI_TRANSPORT_AUDIT='yes')
results = []
for name, classes in batches.items():
    if args.batch and name not in args.batch:
        continue
    env['PRIPERI_TEST_CLASS'] = ','.join('com.priperi.' + value for value in classes)
    started = time.monotonic()
    with (output / (name + '.log')).open('w') as log:
        run = subprocess.run(['node', 'backend/qa/android-fixture.js'], cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
    result = {'batch': name, 'code': run.returncode, 'seconds': round(time.monotonic() - started, 2)}
    results.append(result)
    (output / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
    print(json.dumps(result), flush=True)
    if run.returncode:
        raise SystemExit(run.returncode)
