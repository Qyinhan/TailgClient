# 静态逆向证据（初始基准）

数据来源：用户提供的 `台铃智能_3.6.2.apk`，SHA-256 和 ABI 见 `apk_baseline.json`。所有证据是静态文件与 DEX 类/注解分析，不包含账号、实车或在线请求结果。

## 结构

- 原应用包名 `com.tailg.run.intelligence`，versionName `3.6.2` / versionCode `1080`。
- 17 DEX，存在多个 ARM64 原生库。原版体积不能作为本客户端体积目标。
- `model/login`、`model/bind_car`、`model/home`、`model/control_ble`、`model/nfc`、`model/mine_historical_track`、`model/mine_family_sharing` 等业务包。
- 不同车型/供应商相关类：`tlink_ble`、`tlcp`、`blesdk`、`net.easyconn.carman` 等。

## REST

`TailgService` 中静态提取 216 条 Retrofit 注解：201 POST、14 GET、1 PUT。详见 `api_inventory.csv` 和 `api_signatures.json`（包含方法参数注解，尚无真实请求体字段）；最后一条 PUT 由运行时传入 URL，静态 path 留空。数量不等同独立业务能力数量，部分方法使用相同路径。

已知原版常量：`https://www.tailgdd.com/v1/api/` 和 `/v8/`，另有测试地址。注意不同模块不保证复用统一 Base URL，且地址可能变更。未经账号允许不做生产接口探测。

`TailgRetrofit.addHeaders()` 引用 `PrefsUtil.getToken()` 和 `Authorization` 请求头，也出现 `language`、`Forward-Service-Ip` 等字段。**尚未确定登录请求/响应的所有参数、Token 前缀/续期逻辑、请求签名、各接口权限。**

`app/device/cmd/lock|unlock|start|stop|search|openCushion|status` 等远控路径为静态注解。**未证明服务端授权条件、命令受理语义、车辆执行回执及不同车型兼容情况。**

## MQTT

存在 `MqttUtil.connect()`、Paho MQTT 连接和内置 `tcp://...:1883` 与 `ssl://...` 地址。在 `miTM` 检查服务器证书的方法中有不安全实现，不能复制到第三方客户端。**鉴权信息、Topic、QoS、车端响应关联键、上线下线状态尚未动态确认**。

## BLE

`tlink_ble/TailgBleConfig` 含大量 BLE 配置字段，静态字段名称出现 `AX`、`BB`、`HJ`、`JD`、`JW`、`XL`、`YY`、`QGJ`、`C39` 等标记。这只能说明原版存在多协议分支线索，**不能确认具体品牌、GATT UUID、密钥来源和指令载荷**。不得将从 APK 提取的静态密钥直接硬编码到客户端。

## OTA 与第三方服务

发现固件升级相关 `yyota`、`ota_ecu`、`firmVersionInfo`、本地 HTTP 固件服务，以及地图、车机互联、支付和推送类。OTA 操作必须通过车型匹配、厂商合法获取固件、完整性/签名校验及安全联动测试之后才能启用。厂商授权限制下的 SDK 不作为可擅自复制的代码。

## 下一轮必须获得的证据

1. 原版完整页面和操作录像/截图，形成页面—功能—条件映射，标注不同车型支持范围。
2. 自有账号登录的**去敏请求样例**：请求头结构、JSON 字段、响应错误码及 Token 生命周期（不保存真实 Token）。
3. 同一辆授权车辆的控制行为去敏回执，识别 `HTTP 接受` 和 `车辆动作完成` 的区别。
4. BLE 广播/GATT 服务清单及配对状态，使用实车确认车型分支（不复制隐私密钥）。
5. 需要厂商授权的 SDK、H5 业务、支付、NFC、OTA 的合法接入能力及替代方案。

未拿到上述证据时，只能实现框架、数据验证器、设计与测试工具，不能宣称已实现业务闭环。

### 可复核的核心接口参数注解

| 方法 | Retrofit 形态 | 已知静态参数 |
|---|---|---|
| `login` | POST `app/login` | `@Body Map`，字段未知 |
| `loginCode` | POST `app/sms/loginCode` | `@Query phone`、`@Query captchaPassToken` |
| `userCarList` | POST `app/userCarList` | `@Body Map`，字段未知 |
| `remoteLock` | POST `app/device/cmd/lock` | `@Body Map`，字段未知 |
| `remoteUnLock` | POST `app/device/cmd/unlock` | `@Body Map`，字段未知 |

以上是从 DEX 方法签名和 Retrofit 注解直接提取，不能推断 Map 内的字段或服务端鉴权行为。


## 车辆分页补充（0.4.0-dev）

离线脚本 `tools/verify_garage_page.py` 从原 APK 核查到：
`TailgRepository.userCarListPage` 的 `@Body` 键为 `containShare`、`pageSize`、`nowPageIndex`，
`pageSize` 的 DEX `const/16` 值为 10；`UserCarListPageBean` 包含 `pageData`、`hasNext`、`total` 等字段。
详细证据和 **NOT_RUN** 线上状态见 `garage_page_evidence.json`、`garage_protocol.md`。

`DeviceUtils.getUniqueDeviceId()` 使用 `SPUtils` 的 `KEY_UDID` 缓存，独立 App 不能读取原版私有偏好；
跨应用设备标识是否一致仍未验证。原版登录验证码 WebView 存在名为
`tailgAppJsInterface` 的 JS 桥接，需厂商/验证码平台授权才能安全集成，不应直接照搬桥接。

## 0.5.0-dev Android BLE 被动扫描

本轮新增客户端自己的系统 BLE 发现代码，**不是原版 BLE 车型协议逆向成果**。当前无已验证的厂家特征 UUID、设备身份映射或鉴权密钥，禁止将广播结果视作可控车辆。新增 `SessionMutationGate` 防止登出后的旧登录回包恢复本地 Token；这是独立实现的安全补强，不等于官方协议证据。

## 0.6.0-dev BLE identifier evidence

See `docs/ble_reference_evidence.json`, generated using `tools/verify_ble_reference.py` from `classes2.dex` `TailgBleConfig` static values. Found 18 `BIND_BLE_NAME_*` strings and 18 `GATT_UUID_*` strings. These are static identity *hints*, not proof of vehicle ownership or a verified GATT service layout. The client intentionally does not use the class's `AES_KEY_*` constants or any device-control command values. Runtime BLE/GATT/device verification: **NOT_RUN**.
