#!/usr/bin/env python3
"""Extract public BLE advertisement-name and GATT UUID constants only.

Reads a user-supplied APK locally; intentionally never exports AES keys, commands,
network credentials or any other class fields. Evidence is STATIC_APK only.
"""
import argparse
import json
import re
import struct
import zipfile
from pathlib import Path
from dex_reader import Dex, uleb

CLASS = 'Lcom/tailg/run/intelligence/tlink_ble/TailgBleConfig;'


def extract(apk: Path) -> dict:
    with zipfile.ZipFile(apk) as z:
        for filename in sorted(name for name in z.namelist() if re.fullmatch(r'classes\d*\.dex', name)):
            raw = z.read(filename)
            if CLASS.encode() not in raw:
                continue
            dex = Dex(raw)
            cls = dex.classes.get(CLASS)
            if cls is None:
                continue
            static_off = None
            for index in range(dex.u32(96)):
                cid, _, _, _, _, _, _, values_off = struct.unpack_from('<8I', raw, dex.u32(100) + index * 32)
                if dex.types[cid] == CLASS:
                    static_off = values_off
                    break
            if not static_off:
                raise RuntimeError('BLE configuration has no encoded static values')
            count, cursor = uleb(raw, static_off)
            values = []
            for _ in range(count):
                value, cursor = dex.ev(cursor)
                values.append(value)
            # Only static fields are allowed; the class has static fields only.
            fields = [item[2][2] for item in cls['fields'] if item[1] & 0x8]
            if len(fields) < count:
                raise RuntimeError('BLE static field/value layout is unexpected')
            names = {}
            uuids = {}
            for field, value in zip(fields, values):
                if not isinstance(value, str):
                    continue
                if field.startswith('BIND_BLE_NAME_'):
                    names[field] = value
                if field.startswith('GATT_UUID_') and re.fullmatch(r'[0-9a-fA-F-]{36}', value):
                    uuids[field] = value.lower()
            if len(names) != 18 or len(uuids) != 18:
                raise RuntimeError(f'Unexpected BLE reference catalog sizes: names={len(names)}, uuids={len(uuids)}')
            return {
                'evidence': 'STATIC_APK',
                'source': f'{filename}:{CLASS}',
                'device_names': names,
                'gatt_uuids': uuids,
                'device_test': 'NOT_RUN',
                'notes': 'Names and UUIDs are untrusted BLE discovery hints. No vehicle binding, authentication, or control capability is established.',
            }
    raise RuntimeError('BLE class not found')


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('apk', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    data = extract(args.apk)
    rendered = json.dumps(data, indent=2, ensure_ascii=False) + '\n'
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding='utf-8')
        print(f"BLE static evidence written: {args.output} ({len(data['device_names'])} names, {len(data['gatt_uuids'])} UUIDs)")
    else:
        print(rendered)


if __name__ == '__main__':
    main()
