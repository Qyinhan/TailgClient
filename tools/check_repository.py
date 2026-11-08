#!/usr/bin/env python3
"""Offline repository consistency check (not an Android compile test)."""
import csv
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def must(test: bool, why: str) -> None:
    if not test:
        raise AssertionError(why)


def run() -> None:
    matrix = list(csv.DictReader((ROOT / 'docs/feature_matrix.csv').open(encoding='utf-8-sig')))
    api = list(csv.DictReader((ROOT / 'docs/api_inventory.csv').open(encoding='utf-8-sig')))
    signatures = json.loads((ROOT / 'docs/api_signatures.json').read_text(encoding='utf-8'))
    data = json.loads((ROOT / 'docs/apk_baseline.json').read_text(encoding='utf-8'))
    must(len(api) == 216, '216 Retrofit API entries expected')
    must(len(signatures) == 216, '216 annotation signatures expected')
    inv = {(a['retrofit_method'], a['http_method']) for a in api}
    sig = {(a['method'], a['http']) for a in signatures}
    must(inv == sig, 'API signatures/inventory mismatch')
    must(all(a['validated_request_schema'] is False for a in signatures), 'Unverified schema should not appear verified')
    must(len(matrix) >= 40, 'Too few discovered features')
    must(len({x['feature_id'] for x in matrix}) == len(matrix), 'Duplicate feature id')
    must(all(x['android_implemented'] in ('NO', 'PARTIAL', 'YES') for x in matrix), 'Unknown implementation state')
    must(all(x['device_test'] != 'PASS' for x in matrix), 'No live device tests were performed')
    must(all(x['android_implemented'] != 'YES' for x in matrix), 'No end-to-end feature is yet verified')
    partial = {x['feature_id'] for x in matrix if x['android_implemented'] == 'PARTIAL'}
    must(partial == {'account.sms', 'vehicle.list', 'ble.scan', 'settings.app'}, 'Unexpected partial implementation states')
    must(all(x['live_test'] == 'NOT_RUN' for x in api), 'Unexpected live-test evidence')
    must(data['dex_count'] == 17, 'Reference DEX baseline mismatch')
    manifest = (ROOT / 'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')
    must('android.permission.INTERNET' in manifest, 'Network integration needs INTERNET permission')
    must('android.permission.BLUETOOTH_SCAN' in manifest and 'android.permission.BLUETOOTH_CONNECT' in manifest, 'BLE permissions missing')
    must('android.permission.ACCESS_FINE_LOCATION' in manifest and 'android:maxSdkVersion="30"' in manifest, 'Legacy BLE permission missing')
    must('android:usesPermissionFlags="neverForLocation"' in manifest, 'BLE scans must not request inferred location')
    scanner = (ROOT / 'core/ble/src/main/java/dev/tailg/core/ble/AndroidBleScanner.kt').read_text(encoding='utf-8')
    must('startScan(' in scanner and 'stopScan(' in scanner and 'onScanResult' in scanner, 'BLE scan lifecycle missing')
    must('connectGatt(' not in scanner and 'writeCharacteristic(' not in scanner, 'BLE scanner must remain passive')
    inspector = (ROOT / 'core/ble/src/main/java/dev/tailg/core/ble/AndroidGattInspector.kt').read_text(encoding='utf-8')
    must('connectGatt(' in inspector and 'discoverServices()' in inspector and 'safelyClose(' in inspector,
         'GATT one-shot metadata inspection missing')
    must('BleGattSessionGate' in inspector and 'acceptServiceResult' in inspector and
         'acceptConnected' in inspector, 'No GATT callback ordering guard')
    must('12_000L' in inspector and 'device.connectGatt(appContext, false, callback' in inspector,
         'GATT timeout or explicit-connection policy missing')
    forbidden = ('readCharacteristic(', 'writeCharacteristic(', 'setCharacteristicNotification(',
                 'createBond(', 'writeDescriptor(', 'readDescriptor(')
    must(all(word not in inspector for word in forbidden), 'GATT inspector must never access characteristic data or pair')
    ble_reference = json.loads((ROOT / 'docs/ble_reference_evidence.json').read_text(encoding='utf-8'))
    must(ble_reference['evidence'] == 'STATIC_APK' and ble_reference['device_test'] == 'NOT_RUN',
         'BLE reference evidence must not claim device validation')
    must(len(ble_reference['device_names']) == 18 and len(ble_reference['gatt_uuids']) == 18,
         'Unverified BLE static catalog count')
    candidates = (ROOT / 'core/domain/src/main/kotlin/dev/tailg/core/domain/BleReference.kt').read_text(encoding='utf-8')
    must(all(f'"{value}"' in candidates for value in ble_reference['device_names'].values()),
         'BLE candidate names do not match APK evidence')
    must('BleReferencePolicy.inspect(observed)' in (ROOT / 'app/src/main/java/dev/tailg/client/MainActivity.kt').read_text(encoding='utf-8'),
         'BLE candidate classification not displayed')
    must('gattConsent' in (ROOT / 'app/src/main/java/dev/tailg/client/MainActivity.kt').read_text(encoding='utf-8'),
         'No user consent for GATT inspection')
    evidence = (ROOT / 'core/domain/src/main/kotlin/dev/tailg/core/domain/BleGattEvidence.kt').read_text(encoding='utf-8')
    must('anonymousReport' in evidence and 'matchedCharacteristics' in evidence,
         'Missing bounded UUID-only evidence report')
    ble_flow = json.loads((ROOT / 'docs/ble_flow_evidence.json').read_text(encoding='utf-8'))
    must(ble_flow['evidence'] == 'STATIC_APK' and ble_flow['auth_verified'] is False and
         ble_flow['device_test'] == 'NOT_RUN', 'BLE manager metadata is NOT a validated handshake')
    must(len(ble_flow['managers']) >= 3 and all('dex' in m for m in ble_flow['managers'].values()),
         'BLE variant evidence is incomplete')
    main_ui = (ROOT / 'app/src/main/java/dev/tailg/client/MainActivity.kt').read_text(encoding='utf-8')
    must('BleGattEvidence.anonymousReport(gatt.services, gatt.truncated)' in main_ui and
         'inspectBleGatt(observed.address, gattConsent)' in main_ui,
         'GATT export/explicit authorization not wired')
    must('var gattConsent by remember { mutableStateOf(false) }' in main_ui,
         'BLE inspection consent must not survive process recreation')
    must('import androidx.compose.foundation.layout.Spacer' in main_ui and
         'import androidx.compose.foundation.layout.height' in main_ui,
         'Missing Compose layout imports')
    must('catch (_: SecurityException)' in scanner and '权限已撤销' in scanner,
         'BLE scan callback must tolerate permission revocation')
    remote = (ROOT / 'core/cloud/src/main/java/dev/tailg/core/cloud/CloudGateway.kt').read_text(encoding='utf-8')
    vault = (ROOT / 'core/cloud/src/main/java/dev/tailg/core/cloud/SessionVault.kt').read_text(encoding='utf-8')
    must('HttpsURLConnection' in remote and 'instanceFollowRedirects = false' in remote, 'Require strict HTTPS without redirects')
    must('setHostnameVerifier' not in remote and 'TrustManager' not in remote, 'No TLS bypass')
    must('AndroidKeyStore' in vault and 'AES/GCM/NoPadding' in vault, 'Require Keystore AES-GCM')
    must((ROOT / 'docs/auth_contract_evidence.json').is_file(), 'Missing reproducible auth evidence')
    login_evidence = json.loads((ROOT / 'docs/login_flow_evidence.json').read_text(encoding='utf-8'))
    must(login_evidence['challenge']['path'] == 'app/captcha/slider/verify', 'Slider verify endpoint mismatch')
    must(login_evidence['challenge']['body_keys'] == ['ticket', 'randstr'], 'Slider verify request keys mismatch')
    must(login_evidence['live_test'] == 'NOT_RUN', 'Unexpected live login evidence')
    must('DeviceUtils.getUniqueDeviceId()' in login_evidence['device_identifier']['delegate'],
         'Original device ID call not documented')
    must('verifyChallengeAndSendSms' in remote and 'app/captcha/slider/verify' in remote,
         'Missing correct human-challenge verification before SMS')
    must('SessionRenewalPolicy.updatedToken' in remote, 'Missing successful-response token renewal')
    must('vault.rotateIfCurrent(token, newToken)' in remote, 'Session refresh must be compare-and-swap')
    must('vault.clearIfCurrent(token)' in remote, 'Late 401 must not destroy newer session')
    must('saveAfterLoginIfCurrent' in vault and 'SessionMutationGate' in vault, 'Stale login could restore session after logout')
    must('saveAfterLoginIfCurrent(expectedGeneration, decision.token)' in remote, 'Login response must be version-fenced')
    must('rotateIfCurrent' in vault and 'clearIfCurrent' in vault and '@Synchronized' in vault,
         'Missing atomic Token rotation and clearance')
    page_evidence = json.loads((ROOT / 'docs/garage_page_evidence.json').read_text(encoding='utf-8'))
    must(page_evidence['garage_page']['endpoint'] == 'app/userCarListPage', 'Garage endpoint mismatch')
    must(page_evidence['garage_page']['original_page_size_const'] == 10, 'Original garage page size mismatch')
    must(page_evidence['live_test'] == 'NOT_RUN', 'Unexpected vehicle pagination online test evidence')
    must('app/userCarListPage' in remote and 'GaragePageMerger' in (ROOT / 'app/src/main/java/dev/tailg/client/ClientViewModel.kt').read_text(encoding='utf-8'),
         'Garage page client or merge policy missing')
    must('boundToCurrentAccount = false' in (ROOT / 'core/domain/src/main/kotlin/dev/tailg/core/domain/AuthVehicleContracts.kt').read_text(encoding='utf-8'),
         'Garage listing must not assert verified ownership')
    view_model = (ROOT / 'app/src/main/java/dev/tailg/client/ClientViewModel.kt').read_text(encoding='utf-8')
    must('HumanChallengeReceipt' in view_model and 'lastSmsAttemptAt' in view_model,
         'Missing challenge receipt model and short attempt cooldown')
    ui = (ROOT / 'app/src/main/java/dev/tailg/client/MainActivity.kt').read_text(encoding='utf-8')
    must('challengeTicket' in ui and 'challengeRandstr' in ui and 'remember' in ui,
         'Missing slider developer UI or Compose remember import')
    must('var captchaToken' not in ui, 'Must not ask user for internal captchaPassToken in UI')

    must('android:usesCleartextTraffic="false"' in manifest, 'Cleartext must be disabled')
    must('android:allowBackup="false"' in manifest, 'Backup must be disabled until storage rules reviewed')
    must('android:exported="true"' in manifest, 'Launcher activity must be exported')
    guard = ROOT / 'app/src/main/jniLibs/arm64-v8a/libtailg_abi_guard.so'
    must(guard.is_file() and guard.read_bytes()[:4] == b'\x7fELF', 'arm64 marker missing')
    must(all(p.relative_to(ROOT / 'app/src/main/jniLibs').parts[0] == 'arm64-v8a' for p in (ROOT / 'app/src/main/jniLibs').glob('*/*.so')), 'Unexpected ABI')
    for filename in ['README.md', 'AGENTS.md', 'docs/ble_auth_static.md', 'tools/check_viewmodel_syntax.sh', '.github/workflows/android-arm64.yml']:
        must((ROOT / filename).is_file(), f'Missing {filename}')
    must('val truncated:' in (ROOT / 'core/domain/src/main/kotlin/dev/tailg/core/domain/BleReference.kt').read_text(), 'Truncation flag missing')
    ble_architecture = json.loads((ROOT / 'docs/ble_architecture_evidence.json').read_text(encoding='utf-8'))
    must(set(ble_architecture['backends']) == {'tlink_nordic', 'qgj_kuyi', 'legacy_android_gatt'}, 'BLE family index mismatch')
    must(not ble_architecture['pairing_verified'] and ble_architecture['live_test'] == 'NOT_RUN', 'BLE architecture must not claim live pairing')
    must(all(v['current_vehicle_route_verified'] is False for v in ble_architecture['backends'].values()), 'BLE routing not validated')
    prefs = (ROOT / 'app/src/main/java/dev/tailg/client/UiPreferences.kt').read_text(encoding='utf-8')
    must('UiThemeMode' in prefs and 'dynamic_color' in prefs and 'getSharedPreferences' in prefs, 'Theme preferences implementation missing')
    must('VehicleSelectionPolicy.select' in view_model, 'Vehicle selection not wired')
    must('UiThemeMode.entries.forEach' in ui, 'MD3 theme settings UI not wired')
    must('libtailg_abi_guard.so' in (ROOT / 'tools/verify_apk.py').read_text(), 'ABI validator missing')
    print(f'repository-check: OK; {len(matrix)} features, 4 partial; {len(api)} API annotations; stage-9 BLE guards, local settings and vehicle selection; 17 DEX')

if __name__ == '__main__':
    run()
