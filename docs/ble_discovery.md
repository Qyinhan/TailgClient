# 蓝牙被动发现：实现与验证说明

## 范围

- `core/ble/AndroidBleScanner.kt` 使用官方 Android `BluetoothLeScanner` 的低功耗扫描 API，**只读取 BLE 广播**。
- `app` 中「蓝牙」页面仅在用户主动点击时请求权限并启动扫描。持续 15 秒，用户可随时停止；离开页面或 ViewModel 清理时关闭。
- 读取的数据为广播名称、设备地址、RSSI、广播服务 UUID。最多 100 个地址去重，离开页面后清空内存数据，不保存到文件/数据库/日志。
- Android 12+：`BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`。Android 11 及以下：`ACCESS_FINE_LOCATION`（按 API 30 限制）。设备不支持 BLE 或权限拒绝时给出明确原因。
- 使用 `neverForLocation` 且无后台扫描，不推断用户定位；该选项可能过滤部分 BLE 广播设备，后续可在充分论证隐私用途后调整。

## 验收边界

- 已运行：纯 Kotlin `BleDiscoveryPolicy` 去重、信号排序、100 个设备上限测试；Android BLE 适配器通过 stub 语法检查。
- 尚未运行：Android SDK 编译、真机权限弹窗、扫描、不同系统后台与锁屏表现、具体台铃车型广告识别。
- 未实现：GATT 连接、配对、握手、指令、远程解锁、自动连接或 MQTT。**广播出现不等于车辆绑定成功，也不等于可以控车。**

## 后续实机验证

1. 在 Android 11、12、14/15 等设备上运行，用户主动授权扫描，核对权限和回调状态。
2. 在本人或明确获授权的车辆旁记录脱敏广播信息（不上传完整设备地址），确定真实车端广播名称、服务 UUID 和模型差异。
3. 仅在具有合法配对资格、车型协议与车端回执证据后，实现 GATT 会话；协议匹配应放在独立 Adapter 内。

## 0.6.0-dev: APK-derived identifier hints and one-shot GATT metadata inspection

`tools/verify_ble_reference.py` extracts only the 18 advertised-name constants and 18 GATT UUID constants from `TailgBleConfig` of the supplied APK into `docs/ble_reference_evidence.json`. No private AES keys, control opcode values or credential material are exported. Re-run locally with:

```bash
python3 tools/verify_ble_reference.py '/path/to/台铃智能_3.6.2.apk' \
  --output docs/ble_reference_evidence.json
```

The Android client uses the matching names and only 14 vendor-like UUIDs as **untrusted hints** for indicating a possible compatible device. The four standard 2Axx GATT attributes do not trigger a candidate match. Advertisements are attacker-controlled and **do not attest a model, serial number, pairing, account binding, or permission to operate a vehicle**. A name match may be a false positive and a real compatible vehicle may not advertise any matching data.

After scanning, the user may explicitly check a device they own or are authorized to inspect. `AndroidGattInspector` connects with `autoConnect=false`, invokes `discoverServices()` and returns bounded metadata (service UUID and characteristic UUID/properties), then **immediately disconnects and closes** the GATT connection. A 12-second timeout, cancel action, screen disposal, and view model teardown also close the link. No characteristic reads, writes, notifications, bonding or control commands are implemented. Scan results/device references are memory-only and discarded on page exit or logout.

Properties such as “supportsWrite” indicate the characteristic advertises a write capability; **the client never performs a write**. Actual service identities, mapping to models, encryption and authorized command workflow require a consented physical-device test, and are **NOT_RUN**. On some devices GATT connection may fail, require system pairing, or omit privileged services; there is no fallback that bypasses access restrictions.
