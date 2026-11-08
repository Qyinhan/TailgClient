#!/usr/bin/env python3
"""Reproduce BLE manager method-name evidence from a user-supplied APK.

No command bytes, encrypted payloads, keys, credentials or application code
are extracted. Static method names do not establish the active vehicle protocol.
"""
import argparse
import json
import zipfile
from pathlib import Path
from dex_reader import Dex

TARGETS = {
    'classes2.dex': {
        'Lcom/tailg/run/intelligence/tlink_ble/TLinkBleManager;': [
            'connectDevice', 'disconnectDevice', 'getLoginConnectState', 'checkPassword',
            'readCode', 'readImei', 'readSystemId', 'getGattCallback',
            'read2002', 'writeDataFbb1', 'writeDataFcc1', 'readFirmwareVersion',
        ],
        'Lcom/tailg/run/intelligence/tlink_ble/TLinkBleManagerQgj;': [
            'connectDevice', 'disconnectDevice', 'getLoginConnectState', 'ecuBind',
            'onBleReady', 'read2002', 'writeData2002', 'writeDataFbb1',
        ],
    },
    'classes17.dex': {
        'Lcom/tailg/run/intelligence/ble/tailg/TailgBle;': [
            'doServicesDiscovered', 'executeGetToken', 'writeData2Bluetooth',
            'onCharacteristicChanged', 'onConnectionStateChange', 'scanCallback',
        ],
        'Lcom/tailg/run/intelligence/ble/tailg/TailgBleCmd;': [
            'getBleCommandData', 'getTokenCommandData', 'executeLock',
            'executeUnLock', 'executeRemotePowerOn', 'executeRemotePowerOff',
        ],
    },
}


def collect(apk: Path) -> dict:
    with zipfile.ZipFile(apk) as z:
        found = {}
        for dexfile, classes in TARGETS.items():
            dex = Dex(z.read(dexfile))
            for cls, named in classes.items():
                info = dex.classes.get(cls)
                if info is None:
                    raise ValueError(f'Class missing: {dexfile}:{cls}')
                actual = {method['name'] for _, _, _, method in info['methods']}
                seen = sorted(set(named) & actual)
                found[cls] = {
                    'dex': dexfile,
                    'total_method_definitions': len(info['methods']),
                    'selected_public_method_names': seen,
                    'not_found_from_probe': sorted(set(named) - actual),
                }
    if not found['Lcom/tailg/run/intelligence/tlink_ble/TLinkBleManager;']['selected_public_method_names']:
        raise RuntimeError('Primary BLE manager evidence missing')
    return {
        'evidence': 'STATIC_APK',
        'device_test': 'NOT_RUN',
        'auth_verified': False,
        'source_version': '3.6.2',
        'managers': found,
        'notes': 'Names indicate possible integration points only. Do not infer command bytes, authentication implementation, active protocol, ownership or control capability.',
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument('apk', type=Path)
    ap.add_argument('--output', type=Path)
    opts = ap.parse_args()
    data = collect(opts.apk)
    output = json.dumps(data, ensure_ascii=False, indent=2) + '\n'
    if opts.output:
        opts.output.parent.mkdir(parents=True, exist_ok=True)
        opts.output.write_text(output, encoding='utf-8')
        print(f'BLE manager static method index written: {opts.output}')
    else:
        print(output)


if __name__ == '__main__':
    main()
