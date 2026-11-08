# BLE 连接与授权：3.6.2 静态证据（v0.7）

## 实际验证过的事实

从用户提供的 APK 中独立重提取 `classes2.dex` 和 `classes17.dex` 的类/方法 **名称**：

- `TLinkBleManager`：`connectDevice` / `disconnectDevice`、`getLoginConnectState`、`checkPassword`、`readCode`、`readImei`、`readSystemId`、`getGattCallback`、`read2002`、`writeDataFbb1`、`writeDataFcc1` 等方法存在。
- `TLinkBleManagerQgj`：`connectDevice`、`ecuBind`、`onBleReady`、`getLoginConnectState`、`read2002`、`writeData2002` 等方法存在。
- 较旧的 `ble/tailg/TailgBle`：有 `doServicesDiscovered`、`executeGetToken`、`onConnectionStateChange`、`onCharacteristicChanged`、`writeData2Bluetooth` 等方法。
- 与之相关的 `TailgBleCmd`：有 `getBleCommandData`、`getTokenCommandData` 及若干车辆操作相关方法。

**注意：**方法名只证明代码存在，不证明它们当前被调用。没有取得协议握手的数据样例、服务器颁发的车辆凭据、真实 GATT 回调，或车辆端 ACK，因此不能推断认证、配对和控制指令，也不能标为可使用。

## 工程对应实现

- 保留 `VehicleProtocolAdapter`/`DeviceCredentials` 插口，不伪造任何鉴权成功事件或发送指令。
- Android 端只允许 **用户主动** 扫描、选择当前发现的设备、确认自己拥有或获授权，然后短暂 `connectGatt` / `discoverServices` 获取公开的服务和特征 UUID/属性。自动断开连接。
- 对已经发现的 GATT UUID 及特征属性执行纯 Kotlin 的 `BleGattEvidence.inspect()` 静态匹配，结果仅为候选线索。
- 用户可以手动复制匿名化的 GATT UUID/属性摘要；其中不包含设备地址、广播名称、账号、Token、特征值、车辆 ID，且不会自动上传到任何服务器。**自定义 UUID 仍可能携带厂商标识或设备信息，分享前请自行确认。**
- 若 BLE 权限在扫描回调期间被撤销，停止扫描并显示权限提示，而不是允许异常终止应用。

## 接下来需要的动态证据

1. 在 **本人或已获授权的车辆** 上获取短连接的公开 GATT 信息，确认实际服务分布。
2. 通过合法的官方配对流程，验证设备身份、车型以及登录授权方式；不抓取他人设备或跳过配对。
3. 实际识别协议分支对应的服务、订阅关系及认证状态，建立可重放的**匿名测试样例**（不能包含厂商或用户密钥）。
4. 在受控环境验证完整车端应答、断线恢复与失败处理后，才允许开发控车传输。

## 复现静态证据

```bash
python3 tools/verify_ble_flow.py '/path/to/台铃智能_3.6.2.apk' --output docs/ble_flow_evidence.json
```

工具仅导出类与方法名称的白名单索引，不导出密钥、固件、数据包或私有代码。输出中的 `STATIC_APK`、`NOT_RUN`、`auth_verified: false` 不应在没有实机证据时修改。

## 第 8 轮：BLE 后端方法签名证据

`docs/ble_architecture_evidence.json` 包含从原始 APK 直接提取的三个类的**公开方法签名定义**：

- `TLinkBleManager` 的相关 API 签名出现 `no.nordicsemi.android.ble` 类型，连接入口 `connectDevice` 带 7 个参数；
- `TLinkBleManagerQgj` 出现 `com.kuyi.blesdk` 类型，`connectDevice` 带 8 个参数，`ecuBind` 需要 `LifecycleOwner`；
- 旧 `TailgBle` 的回调使用 Android `BluetoothGatt` / `BluetoothGattCharacteristic` 类型。

这些只说明代码路径及 SDK 依赖存在，**没有确认实际使用的车型、密码、令牌及授权条件**。不提取任何特征字节、密钥或真实请求。重生成命令：

```bash
python3 tools/verify_ble_architecture.py '/path/to/台铃智能_3.6.2.apk' --output docs/ble_architecture_evidence.json
```
