#!/usr/bin/env python3
"""Offline negative-case tests for verify_apk (synthetic ZIP fixtures, not a real APK)."""
import tempfile
import zipfile
from pathlib import Path
from verify_apk import verify


def fixture(path: Path, abi='arm64-v8a', elf_machine=183, include_dex=True):
    elf = bytearray(32)
    elf[:6] = b'\x7fELF\x02\x01'
    elf[18:20] = int(elf_machine).to_bytes(2, 'little')
    with zipfile.ZipFile(path, 'w') as z:
        z.writestr('AndroidManifest.xml', b'\x03\x00\x08\x00' + bytes(20))
        z.writestr('resources.arsc', b'\x02\x00\x0c\x00' + bytes(20))
        if include_dex:
            z.writestr('classes.dex', b'dex\n035\x00' + bytes(32))
        z.writestr(f'lib/{abi}/libtailg_abi_guard.so', bytes(elf))


with tempfile.TemporaryDirectory() as tmp:
    archive = Path(tmp) / 'synthetic.apk'
    fixture(archive)
    verify(archive)
    for kwargs in ({'abi': 'armeabi-v7a'}, {'elf_machine': 40}, {'include_dex': False}):
        fixture(archive, **kwargs)
        try:
            verify(archive)
        except ValueError:
            pass
        else:
            raise AssertionError(f'Validator accepted invalid fixture {kwargs}')
print('apk-verifier-tests: OK (synthetic inputs)')
