#!/usr/bin/env python3
"""Reproduce stage-3 login contract from a locally supplied, unmodified APK.

Does not send network requests, decode/print credentials, or include the APK in source output.
"""
import argparse
import hashlib
import json
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from dex_reader import Dex


def method(dex, owner, name):
    return next((m for m in dex.classes[owner]['methods'] if m[3]['name'] == name), None)


def requirement(condition, reason):
    if not condition:
        raise ValueError(reason)


def analyze(apk_path):
    with zipfile.ZipFile(apk_path) as apk:
        net = Dex(apk.read('classes2.dex'))
        ui = Dex(apk.read('classes17.dex'))
    service = 'Lcom/tailg/run/intelligence/net/TailgService;'
    repo = 'Lcom/tailg/run/intelligence/net/TailgRepository;'
    code = method(net, service, 'sliderVerify')
    requirement(code is not None, 'Missing service sliderVerify')
    svc_method_id = code[0]
    annotations, param_annotations = net.method_annotations(service)
    requirement(any(a['type'].endswith('POST;') and a['values'].get('value') == 'app/captcha/slider/verify'
                    for a in annotations.get(svc_method_id, [])), 'Unexpected slider verification URL')
    requirement(any(a['type'].endswith('Body;') for p in param_annotations.get(svc_method_id, []) for a in p),
                'Expected a POST JSON body')
    repository = method(net, repo, 'sliderVerify')
    requirement(repository is not None, 'Missing Repository.sliderVerify')
    constants = [item[2] for item in net.code_strings(repository[2]) if item[1].startswith('const-string')]
    requirement('ticket' in constants and 'randstr' in constants, 'Missing challenge request keys')
    login_vm = 'Lcom/tailg/run/intelligence/model/login/viewmodel/CodeLoginViewModel$4;'
    vm_next = next(m for m in ui.classes[login_vm]['methods'] if m[3]['name'] == 'onNext' and
                   m[3]['params'] == ['Lcom/tailg/run/intelligence/model/login/bean/SliderVerifyBean;'])
    next_calls = [item[2] for item in ui.code_strings(vm_next[2]) if item[1] == 'invoke']
    requirement(any('SliderVerifyBean;->getCaptchaPassToken(' in inv for inv in next_calls),
                'Missing captcha pass token consumption')
    requirement(any('CodeLoginViewModel;->loginCode(' in inv for inv in next_calls),
                'Missing SMS request after slider verification')
    model = 'Lcom/tailg/run/intelligence/model/login/bean/SliderVerifyBean;'
    model_getters = {m[3]['name'] for m in ui.classes[model]['methods'] if m[3]['name'].startswith('get')}
    expected_getters = {'getCaptchaPassToken', 'getCaptchaPassTokenExpireIn', 'getPassed', 'getCaptchaCode', 'getCaptchaMsg'}
    requirement(expected_getters <= model_getters, 'Slider response fields have changed')
    mc = 'Lcom/tailg/run/intelligence/model/util/MacCodeUtils;'
    getmac = method(ui, mc, 'getMac')
    requirement(getmac is not None, 'Missing getMac')
    code_offset = getmac[2]
    size = ui.u32(code_offset + 12)
    instructions = ui.d[code_offset+16:code_offset+16+size*2]
    requirement(instructions[0] == 0x71, 'getMac no longer calls an immediate static method')
    target = ui.methods[int.from_bytes(instructions[2:4], 'little')]
    requirement(target['owner'] == 'Lcom/blankj/utilcode/util/DeviceUtils;' and
                target['name'] == 'getUniqueDeviceId', 'Device identifier source changed')
    interceptor = 'Lcom/tailg/run/intelligence/net/TailgRetrofit$1;'
    token_calls = [item[2] for item in net.code_strings(method(net, interceptor, 'intercept')[2])]
    requirement('Authorization' in token_calls and any('PrefsUtil;->saveToken(' in s for s in token_calls),
                'Authorization renewal evidence changed')
    return {
        'source': 'STATIC_APK',
        'apk_sha256': hashlib.sha256(apk_path.read_bytes()).hexdigest(),
        'live_test': 'NOT_RUN',
        'challenge': {
            'service_method': 'TailgService.sliderVerify',
            'method': 'POST',
            'path': 'app/captcha/slider/verify',
            'body_keys': ['ticket', 'randstr'],
            'response_getters': sorted(expected_getters),
            'next_operation': 'CodeLoginViewModel.loginCode(captchaPassToken)',
        },
        'device_identifier': {
            'entry_point': 'MacCodeUtils.getMac(Context)',
            'delegate': 'com.blankj.utilcode.util.DeviceUtils.getUniqueDeviceId()',
            'cross_application_compatibility': 'NOT_VERIFIED',
        },
        'session_header': 'Authorization',
        'session_header_capture': 'TailgRetrofit$1.intercept -> PrefsUtil.saveToken',
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('apk', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    data = analyze(args.apk)
    rendered = json.dumps(data, ensure_ascii=False, indent=2) + '\n'
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding='utf-8')
    else:
        print(rendered, end='')


if __name__ == '__main__':
    main()
