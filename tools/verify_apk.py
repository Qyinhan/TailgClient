#!/usr/bin/env python3
"""Verify an Android debug APK's basic structure and every packaged native ELF ABI.

This is not a substitute for apksigner or device installation tests.
"""
import sys
import zipfile
from pathlib import Path

ARM64_MACHINE = 183


def is_arm64_elf(payload: bytes) -> bool:
    return (
        len(payload) >= 20
        and payload[:4] == b'\x7fELF'
        and payload[4] == 2  # ELF64
        and payload[5] == 1  # little endian
        and int.from_bytes(payload[18:20], 'little') == ARM64_MACHINE
    )


def verify(path: Path) -> None:
    if not path.is_file():
        raise FileNotFoundError(path)
    with zipfile.ZipFile(path) as apk:
        names = set(apk.namelist())
        for required in ('AndroidManifest.xml', 'classes.dex', 'resources.arsc'):
            if required not in names:
                raise ValueError(f'Not a compiled Android APK: missing {required}')
        if not apk.read('classes.dex').startswith(b'dex\n'):
            raise ValueError('classes.dex is not a DEX binary')
        if apk.read('AndroidManifest.xml')[:4] != b'\x03\x00\x08\x00':
            raise ValueError('AndroidManifest.xml must be compiled binary XML')
        if apk.read('resources.arsc')[:2] != b'\x02\x00':
            raise ValueError('resources.arsc is not compiled Android resources')
        libs = sorted(name for name in names if name.startswith('lib/') and name.endswith('.so'))
        if not libs:
            raise ValueError('No native libraries; this APK is not restricted to arm64-v8a')
        bad = [name for name in libs if not name.startswith('lib/arm64-v8a/')]
        if bad:
            raise ValueError(f'Unexpected native ABIs: {bad}')
        marker = 'lib/arm64-v8a/libtailg_abi_guard.so'
        if marker not in libs:
            raise ValueError('Required arm64 ABI marker missing')
        malformed = [name for name in libs if not is_arm64_elf(apk.read(name))]
        if malformed:
            raise ValueError(f'Native libraries do not contain AArch64 ELF: {malformed}')
    print(f'APK structural verification passed: {len(libs)} ARM64 native libraries')


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('Usage: python3 tools/verify_apk.py path/to/app-debug.apk')
    verify(Path(sys.argv[1]))
