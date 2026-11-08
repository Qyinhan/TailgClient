#!/usr/bin/env python3
"""Static BLE backend/symbol signature index from user-provided APK (no secrets).

Only DEX class and method prototype metadata is read: NO method bodies, GATT
characteristic bytes, token material or network requests. A method's presence
never implies current use or a verified pairing mechanism.
"""
import argparse
import json
import zipfile
from pathlib import Path
from dex_reader import Dex

TARGETS = (
    ('tlink_nordic', 'classes2.dex', 'Lcom/tailg/run/intelligence/tlink_ble/TLinkBleManager;',
     ('connectDevice', 'disconnectDevice', 'getLoginConnectState', 'checkPassword', 'getGattCallback')),
    ('qgj_kuyi', 'classes2.dex', 'Lcom/tailg/run/intelligence/tlink_ble/TLinkBleManagerQgj;',
     ('connectDevice', 'disconnectDevice', 'getLoginConnectState', 'ecuBind', 'onBleReady')),
    ('legacy_android_gatt', 'classes17.dex', 'Lcom/tailg/run/intelligence/ble/tailg/TailgBle;',
     ('doServicesDiscovered', 'executeGetToken', 'onConnectionStateChange', 'onServicesDiscovered')),
)


def gather(path: Path) -> dict:
    entries = {}
    with zipfile.ZipFile(path) as apk:
        cache = {}
        for family, dex_filename, cls, methods in TARGETS:
            if dex_filename not in cache:
                cache[dex_filename] = Dex(apk.read(dex_filename))
            dex = cache[dex_filename]
            assert cls in dex.classes, f'Missing BLE backend {cls}'
            class_methods = [method for _, _, _, method in dex.classes[cls]['methods']]
            details = []
            for method_name in methods:
                found = [m for m in class_methods if m['name'] == method_name]
                assert found, f'Missing method {cls}->{method_name}'
                for m in found:
                    details.append({
                        'name': method_name,
                        'parameters': m['params'],
                        'return_type': m['ret'],
                    })
            # Runtime SDK relationships are only hints from declared signatures.
            signature_types = ' '.join(str(m['params']) + m['ret'] for m in class_methods)
            sdk_hints = []
            if 'Lno/nordicsemi/' in signature_types:
                sdk_hints.append('Nordic BLE Android')
            if 'Lcom/kuyi/' in signature_types:
                sdk_hints.append('Kuyi BLE SDK')
            if 'Landroid/bluetooth/' in signature_types:
                sdk_hints.append('Android Bluetooth GATT')
            entries[family] = {
                'dex': dex_filename,
                'class': cls,
                'sdk_type_hints': sdk_hints,
                'public_signature_probes': details,
                'current_vehicle_route_verified': False,
            }
    return {
        'source': 'user-supplied Tailg 3.6.2 APK',
        'evidence': 'DEX_METHOD_SIGNATURES_ONLY',
        'live_test': 'NOT_RUN',
        'pairing_verified': False,
        'firmware_commands_extracted': False,
        'backends': entries,
        'caution': 'These are static prototype definitions. No proof of active backend, authenticated session, ownership, or permission to transmit commands.',
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('apk', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    data = json.dumps(gather(args.apk), ensure_ascii=False, indent=2) + '\n'
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(data, encoding='utf8')
        print('BLE backend signature index written:', args.output)
    else:
        print(data)


if __name__ == '__main__':
    main()
