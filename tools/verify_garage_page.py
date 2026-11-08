#!/usr/bin/env python3
"""Verify read-only garage pagination evidence against the locally supplied APK.

Offline only. Does not invoke any server and never includes personal or private APK data.
"""
import argparse
import hashlib
import json
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from dex_reader import Dex


def required(condition, reason):
    if not condition:
        raise ValueError(reason)


def method(dex, owner, name):
    return next((item for item in dex.classes[owner]['methods'] if item[3]['name'] == name), None)


def code_evidence(dex, entry):
    return [value for _, kind, value in dex.code_strings(entry[2]) if kind.startswith('const-string')]


def analyze(apk_path):
    with zipfile.ZipFile(apk_path) as archive:
        network = Dex(archive.read('classes2.dex'))
        ui = Dex(archive.read('classes17.dex'))
        util = Dex(archive.read('classes13.dex'))

    repo_class = 'Lcom/tailg/run/intelligence/net/TailgRepository;'
    service_class = 'Lcom/tailg/run/intelligence/net/TailgService;'
    repo = method(network, repo_class, 'userCarListPage')
    service = method(network, service_class, 'userCarListPage')
    required(repo is not None and service is not None, 'Missing original garage pagination method')
    body_keys = ['containShare', 'pageSize', 'nowPageIndex']
    strings = code_evidence(network, repo)
    required(all(x in strings for x in body_keys), 'Garage page request keys changed')
    annotations, params = network.method_annotations(service_class)
    required(any(x['type'].endswith('/POST;') and x['values'].get('value') == 'app/userCarListPage'
                 for x in annotations.get(service[0], [])), 'Garage page REST path changed')
    required(any(x['type'].endswith('/Body;') for a in params.get(service[0], []) for x in a),
             'Garage page JSON body annotation missing')

    insn_size = network.u32(repo[2] + 12)
    bytecode = network.d[repo[2] + 16:repo[2] + 16 + insn_size * 2]
    # const/16 vX, #+10 is the repository's default page size, not a server guarantee.
    page_size_ten = any(bytecode[i] == 0x13 and int.from_bytes(bytecode[i+2:i+4], 'little', signed=True) == 10
                        for i in range(0, len(bytecode) - 3, 2))
    required(page_size_ten, 'Original default page size 10 not detected')

    model_name = 'Lcom/tailg/run/intelligence/model/mine_family_sharing/bean/UserCarListPageBean;'
    fields = {field[2][2]: field[2][1] for field in ui.classes[model_name]['fields']}
    required(all(x in fields for x in ['pageData', 'hasNext', 'total', 'nowPageIndex', 'pageSize']),
             'Original garage page response model changed')

    device_class = 'Lcom/blankj/utilcode/util/DeviceUtils;'
    device_method = next((x for x in util.classes[device_class]['methods']
                          if x[3]['name'] == 'getUniqueDeviceId' and len(x[3]['params']) == 2), None)
    required(device_method is not None, 'Missing original device ID cached method')
    refs = util.code_strings(device_method[2])
    required(any(v == 'KEY_UDID' for _, kind, v in refs if kind.startswith('const-string')),
             'Device identifier cache key changed')
    cache_insns = util.d[device_method[2]+16:device_method[2]+16+util.u32(device_method[2]+12)*2]
    # dex_reader.code_strings focuses on application-owned calls. Inspect other invokes
    # here to verify the SPUtils dependency (without printing or reading any ID values).
    cache_calls = []
    for pos in range(0, len(cache_insns)-3, 2):
        if cache_insns[pos] in range(0x6e, 0x79):
            index = int.from_bytes(cache_insns[pos+2:pos+4], 'little')
            if index < len(util.methods):
                cache_calls.append(util.methods[index])
    required(any(m['owner'].endswith('/SPUtils;') and m['name'] == 'getString'
                 for m in cache_calls), 'Device ID cache path changed')
    web = 'Lcom/tailg/run/intelligence/model/login/dialog/WebDialog;'
    web_method = method(ui, web, 'settingsWebView')
    required(web_method is not None and 'tailgAppJsInterface' in code_evidence(ui, web_method),
             'Original human challenge WebView bridge not found')
    return {
        'source': 'STATIC_APK',
        'apk_sha256': hashlib.sha256(apk_path.read_bytes()).hexdigest(),
        'live_test': 'NOT_RUN',
        'garage_page': {
            'endpoint': 'app/userCarListPage',
            'http_method': 'POST',
            'body_annotation': '@Body',
            'body_keys': body_keys,
            'original_page_size_const': 10,
            'response_model': 'UserCarListPageBean',
            'response_fields': {key: fields[key] for key in ['pageData', 'hasNext', 'total', 'nowPageIndex', 'pageSize']},
            'response_values_observed': False,
        },
        'device_id': {
            'cache_key': 'KEY_UDID',
            'cache_store': 'SPUtils',
            'cross_app_equivalence': 'NOT_VERIFIED',
        },
        'captcha_webview': {
            'class': 'WebDialog',
            'javascript_bridge': 'tailgAppJsInterface',
            'third_party_authorization': 'NOT_VERIFIED',
        },
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('apk', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    payload = json.dumps(analyze(args.apk), ensure_ascii=False, indent=2) + '\n'
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(payload, encoding='utf-8')
    else:
        print(payload, end='')


if __name__ == '__main__':
    main()
