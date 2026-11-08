#!/usr/bin/env python3
"""Feature-parity gate: an installable APK must never be mistaken for a completed client."""
import csv
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
rows = list(csv.DictReader((ROOT / 'docs/feature_matrix.csv').open(encoding='utf-8-sig')))
counts = Counter(r['android_implemented'] for r in rows)
devices = Counter(r['device_test'] for r in rows)
print(f'Feature matrix: {len(rows)} entries | {counts["YES"]} full, {counts["PARTIAL"]} partial, {counts["NO"]} not implemented')
print('Device tests:', dict(devices))
fully_ready = bool(rows) and all(
    r['android_implemented'] == 'YES' and r['protocol_verified'] == 'YES'
    and r['device_test'] == 'PASS'
    for r in rows
)
if not fully_ready:
    print('NOT RELEASE READY: compilation alone cannot provide missing vendor protocols, account validation or real vehicle tests.')
    if '--require-full' in sys.argv:
        raise SystemExit(1)
else:
    print('Feature parity matrix complete; still require final installation and release-signing review.')
