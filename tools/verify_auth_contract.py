#!/usr/bin/env python3
"""Offline DEX evidence checker for three read-only/auth integration contracts.

Does not extract secrets or send traffic. Input APK must be supplied by the owner.
"""
import argparse
import hashlib
import json
import zipfile
from dex_reader import Dex

REPO = 'Lcom/tailg/run/intelligence/net/TailgRepository;'
INTERCEPTOR = 'Lcom/tailg/run/intelligence/net/TailgRetrofit$1;'
USER_BEAN = 'Lcom/tailg/run/intelligence/model/mine_my_garage/bean/UserCarBean;'


def find_method(dex, owner: str, name: str):
    classes = dex.classes
    if owner not in classes:
        raise ValueError(f'Missing class {owner}')
    found = [(index, co, m) for index, _, co, m in classes[owner]['methods'] if m['name'] == name]
    if len(found) != 1:
        raise ValueError(f'Expected one {owner}->{name}; got {len(found)}')
    return found[0]


def check_strings(dex, cls, method, strings=(), calls=()):
    _, co, _ = find_method(dex, cls, method)
    evidence = dex.code_strings(co)
    constants = {item for _, kind, item in evidence if kind.startswith('const-string')}
    invocations = {item for _, kind, item in evidence if kind == 'invoke'}
    for string in strings:
        if string not in constants:
            raise AssertionError(f'Missing literal {cls}->{method}:{string}')
    for call in calls:
        if not any(call in inv for inv in invocations):
            raise AssertionError(f'Missing call {cls}->{method}:{call}')
    return {'class': cls, 'method': method, 'constants': list(strings), 'invocations': list(calls)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('apk', help='Owner-provided Tailg Smart 3.6.2 APK')
    args = parser.parse_args()
    with zipfile.ZipFile(args.apk) as z:
        primary = Dex(z.read('classes2.dex'))
        feature = Dex(z.read('classes17.dex'))
    found = [
        check_strings(primary, REPO, 'login',
                      ('macCode','phone','smsCode','autoCompleteUserDetail','true'),
                      ('TailgService;->login(',)),
        check_strings(primary, REPO, 'loginCode', calls=('TailgService;->loginCode(',)),
        check_strings(primary, REPO, 'userCarList', ('containShare',), ('TailgService;->userCarList(',)),
        check_strings(primary, REPO, 'userCarListPage',
                      ('containShare','pageSize','nowPageIndex'), ('TailgService;->userCarListPage(',)),
        check_strings(primary, INTERCEPTOR, 'intercept',
                      ('Authorization',), ('PrefsUtil;->saveToken(',)),
    ]
    fields = {f[2][2] for f in feature.classes[USER_BEAN]['fields']}
    expected_fields = {'carId','carName','carNickName','frame','online','electricQuantity'}
    assert expected_fields <= fields, 'UserCarBean missing expected fields'
    digest = hashlib.sha256()
    with open(args.apk, 'rb') as original:
        for block in iter(lambda: original.read(4 * 1024 * 1024), b''):
            digest.update(block)
    print(json.dumps({
        'evidence': 'STATIC_APK', 'live_test': 'NOT_RUN',
        'sha256': digest.hexdigest(),
        'verified_methods': found, 'user_car_fields': sorted(expected_fields),
    }, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
