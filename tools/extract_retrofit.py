#!/usr/bin/env python3
"""Offline extraction of Retrofit interface method/parameter annotations.

Unknown or raw URL content is sanitized before writing any artifact.
This parser does not decompile proprietary app implementation code.
"""
import argparse
import json
import re
import zipfile
from pathlib import Path
from dex_reader import Dex

SERVICE = 'Lcom/tailg/run/intelligence/net/TailgService;'
HTTP_TYPES = {
    'Lretrofit2/http/GET;': 'GET',
    'Lretrofit2/http/POST;': 'POST',
    'Lretrofit2/http/PUT;': 'PUT',
    'Lretrofit2/http/DELETE;': 'DELETE',
    'Lretrofit2/http/PATCH;': 'PATCH',
}


def clean_path(path):
    """Never store client API keys accidentally embedded in annotation strings."""
    path = re.sub(r'(?i)(key|token|secret|password|api_key)=([^&\\s]+)', r'\1=[REDACTED]', path)
    return path


def scan(apk):
    endpoints = []
    with zipfile.ZipFile(apk) as z:
        dexnames = sorted(
            (n for n in z.namelist() if re.fullmatch(r'classes\d*\.dex', n)),
            key=lambda n: int(n[7:-4] or '1'),
        )
        for dexname in dexnames:
            # Header+strings+classes processing is local, no network calls.
            dex = Dex(z.read(dexname))
            if SERVICE not in dex.classes:
                continue
            annotated, params = dex.method_annotations(SERVICE)
            for idx, flags, offset, method in dex.classes[SERVICE]['methods']:
                annotations = annotated.get(idx, [])
                verbs = [(HTTP_TYPES[a['type']], a.get('values', {}).get('value', ''))
                         for a in annotations if a['type'] in HTTP_TYPES]
                if not verbs:
                    continue
                verb, path = verbs[0]
                arguments = []
                for i, typ in enumerate(method['params']):
                    annotations_for_param = params.get(idx, [])
                    param_annotations = annotations_for_param[i] if i < len(annotations_for_param) else []
                    arguments.append({
                        'type': typ,
                        'annotations': [
                            {'type': a['type'].split('/')[-1].strip(';'),
                             'name': clean_path(str(a.get('values', {}).get('value', '')))}
                            for a in param_annotations
                        ],
                    })
                endpoints.append({
                    'method': method['name'], 'http': verb,
                    'path': clean_path(str(path)), 'args': arguments,
                    'return_dex_type': method['ret'], 'source_dex': dexname,
                    'validated_request_schema': False,
                    'validated_response_schema': False,
                })
            break
    return sorted(endpoints, key=lambda a: a['method'])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('apk', type=Path)
    parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parents[1] / 'docs/api_signatures.json')
    args = parser.parse_args()
    rows = scan(args.apk)
    if len(rows) != 216:
        raise ValueError(f'Expected 216 Retrofit entries but found {len(rows)}')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(rows, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')
    print(f'Extracted {len(rows)} Retrofit method signatures into {args.output}')
    for name in ['login', 'loginCode', 'remoteLock', 'remoteUnLock', 'userCarList']:
        entry = next(x for x in rows if x['method'] == name)
        print(name, entry['http'], entry['path'], entry['args'])


if __name__ == '__main__':
    main()
