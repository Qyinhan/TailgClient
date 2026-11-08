#!/usr/bin/env python3
"""Regenerate static API/function inventories from the supplied independent evidence.

This tool NEVER executes or contacts vendor APIs. It does not extract or save keys.
"""
import argparse
import csv
import hashlib
import json
import re
import zipfile
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FEATURE_SOURCE = ROOT / 'core/domain/src/main/kotlin/dev/tailg/core/domain/FeatureCatalog.kt'

# Domain classifications are human-reviewed heuristics, not proof of behavior.
AREA_RULES = [
    ('authentication', ('login', '/sms/', 'getcode', 'getsecret', 'phoneband', 'phonechange', 'captcha', 'userprofile', 'userdetail', 'avatar', 'infoperfect', 'accountmanagement', 'socialunbound', '/third/', 'platformauth', 'platrofm', 'platfromauth')),
    ('remote-control', ('app/device/cmd/', 'ynlock', 'carstatuschange', '/device/statdetail')),
    ('firmware', ('firmversion', 'ota', 'upgradation', '/version/', 'instrument', 'motorctrl')),
    ('connectivity', ('app/sim/', 'netlink', 'getnetstatus', 'simdevice')),
    ('sharing', ('share', 'cartransfer', 'transfercode', 'transfer/')),
    ('map-and-trips', ('travel', 'riding', 'gpsproducer', 'fencedata', 'fenceconfig', 'fence', 'stores/city', '/stores', 'weather')),
    ('device', ('centralcontrol', 'bms', 'tirepressure', 'nfc', 'battery', 'listparts', 'saveparts')),
    ('commerce', ('apppay', 'shop/app', 'buypeel', 'chargecard', 'pays', 'order/')),
    ('messaging', ('app/msg/', 'unread')),
    ('service-and-community', ('feedback', 'evaluate', 'social/app', 'service/', '/store/', 'social/', 'tools/ocr', 'live/', 'wallpaper')),
    ('vehicle', ('car/', 'bike', 'carlist', 'usercar', 'carinfo', 'carstatus', 'sync', 'getability', 'setability')),
]


def classify(method: str, path: str) -> str:
    text = (method + ' ' + path).lower()
    for area, terms in AREA_RULES:
        if any(term in text for term in terms):
            return area
    return 'other-needs-review'


def emit_api_inventory(source: Path, target: Path) -> Counter:
    with source.open(encoding='utf-8-sig', newline='') as f:
        source_rows = list(csv.DictReader(f))
    if len(source_rows) != 216:
        raise ValueError(f'Unexpected endpoint count: {len(source_rows)}; review baseline')
    rows = []
    for r in source_rows:
        method = r['Retrofit方法'].strip()
        http = r['HTTP方法'].strip()
        path = r['接口路径（静态提取）'].strip()
        # The pre-signed object storage upload uses a runtime URL rather than a static path.
        if http not in {'POST', 'GET', 'PUT'}:
            raise ValueError(f'Unexpected method: {http}')
        rows.append({
            'area': classify(method, path), 'retrofit_method': method, 'http_method': http,
            'static_path': path, 'discovery': 'APK static Retrofit annotation',
            'request_schema': 'UNVERIFIED', 'auth': 'UNVERIFIED',
            'response_schema': 'UNVERIFIED', 'live_test': 'NOT_RUN',
        })
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open('w', encoding='utf-8-sig', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    return Counter(r['area'] for r in rows)


def emit_feature_matrix(target: Path) -> int:
    src = FEATURE_SOURCE.read_text(encoding='utf-8')
    entries = re.findall(r'feature\("([\w.]+)", "([^"]+)", FeatureGroup\.([A-Z_]+)\)', src)
    if len(entries) < 40:
        raise ValueError(f'Unexpected feature inventory size: {len(entries)}')
    if len(set(e[0] for e in entries)) != len(entries):
        raise ValueError('Duplicate feature id')
    with target.open('w', encoding='utf-8-sig', newline='') as f:
        w = csv.writer(f)
        w.writerow(['feature_id', 'feature_name', 'category', 'static_discovery', 'protocol_verified', 'android_implemented', 'device_test', 'notes'])
        for fid, name, group in entries:
            w.writerow([fid, name, group, 'YES', 'NO', 'NO', 'NOT_RUN', 'Reference functions may differ by model'])
    return len(entries)


def emit_apk_snapshot(apk: Path, target: Path) -> dict:
    h = hashlib.sha256()
    with apk.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    with zipfile.ZipFile(apk) as z:
        names = z.namelist()
        dexes = [n for n in names if re.fullmatch(r'classes(\d+)?\.dex', n)]
        libs = [n for n in names if n.startswith('lib/') and n.endswith('.so')]
        abis = Counter(n.split('/')[1] for n in libs)
    snapshot = {
        'apk_name': apk.name, 'sha256': h.hexdigest(), 'bytes': apk.stat().st_size,
        'dex_count': len(dexes), 'native_abis': dict(sorted(abis.items())),
        'analysis': 'static-only', 'has_live_verification': False,
    }
    target.write_text(json.dumps(snapshot, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return snapshot


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--csv', type=Path, required=True, help='Previously extracted Retrofit inventory CSV')
    parser.add_argument('--apk', type=Path, default=None, help='Original APK for offline hash/ZIP inspection')
    args = parser.parse_args()
    counts = emit_api_inventory(args.csv, ROOT / 'docs/api_inventory.csv')
    n = emit_feature_matrix(ROOT / 'docs/feature_matrix.csv')
    print(f'Endpoints: {sum(counts.values())}; feature catalog: {n}')
    print('Endpoint groups:', dict(sorted(counts.items())))
    if args.apk:
        snapshot = emit_apk_snapshot(args.apk, ROOT / 'docs/apk_baseline.json')
        print('Reference SHA-256:', snapshot['sha256'])


if __name__ == '__main__':
    main()
