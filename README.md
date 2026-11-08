# 电动车助手 / TailgClient

一个**非官方**的 Android 原生客户端研究工程，功能范围以用户提供的「台铃智能 3.6.2」为参照。当前不是可替代官方 App 的产品；不使用官方商标素材、不包含原始 APK、私有源码、认证凭据或厂商密钥。

**当前开发版：`0.9.0-dev`。** Kotlin + Jetpack Compose + Material 3。仅针对 `arm64-v8a` 生成 Android 安装包；将来若支持 iOS，使用 Swift/SwiftUI 的平台原生界面，核心模型与协议定义保持可移植。

## 现状（请先阅读）

| 功能 | 当前实现 | 实际验证 |
|---|---|---|
| 原版 APK 结构及 Retrofit API 索引 | 17 DEX、216 条接口注解、50 项初始功能条目 | 静态证据可复查；不是运行成功 |
| 短信登录、会话存储 | 实验性验证码票据流程、HTTPS、Keystore Token 存储 | **未通过官方验证码页面/在线登录验证** |
| 车辆列表与切换 | 只读分页、去重、手动选车 | **未经过真实车辆账号验证** |
| BLE | 广播扫描、候选设备识别、用户授权后只读 GATT UUID 检查 | **未在真机或实车上测试** |
| App 外观 | MD3、深色/浅色/系统主题、Android 12+ 动态配色，本地持久化 | 本地 Kotlin 测试桩通过；Android 运行未测 |
| 远程控制、蓝牙钥匙、定位、OTA、NFC、地图、支付、车机 | 保留功能清单与协议适配边界；**没有可用实现** | 需要账号、车型、真实设备和/或厂商 SDK 授权 |
| Android APK | 构建和 arm64 校验脚本已配置 | **未实际编译，未产生 APK** |

**完成度：50 项功能中 0 项完成在线及实车验收、4 项部分实现、46 项未实现。** 执行 `python3 tools/release_gate.py` 可查看最新分布；`--require-full` 用于独立阻止将未完成功能误标为正式发布。

> 将本仓库编译成 APK **不意味着原版功能已完整复现**。原版有多套不同的 BLE SDK、服务端鉴权、车型差异、厂商支付/登录组件。静态 API 路径无法取代正常授权和车端回执测试。

## 工程结构

```text
app/                Compose MD3 UI、Android ViewModel、本地主题偏好
core/domain/        与 Android 无关的车辆/认证/权限/状态模型及领域测试
core/protocol/      可替换的车辆通信协议接口；未提供未经验证的控制实现
core/cloud/         实验性 HTTPS + Android Keystore 令牌存储
core/ble/           Android BLE 扫描、只读 GATT 元信息检查
assets/native/      arm64 ABI 标识库源码（不包含控制代码）
docs/               功能矩阵、静态证据、接口注解、协议尚未验证事项
tools/              APK 检查、逆向证据核验及离线 Kotlin 测试
.github/workflows/  GitHub Actions ARM64 Debug APK 构建
AGENTS.md           AI 编程风格、边界和验收规范
```

## 构建 Android APK

在有网络的 Android Studio / Linux 构建机上准备：**JDK 17、Gradle 8.13、Android SDK Platform 36、Build Tools 35.0.0**。已核对 AGP 8.13.2、Kotlin 2.3.21 与 Compose BOM 2026.09.00 的官方兼容信息。项目未附 Gradle Wrapper JAR（当前执行环境无 Gradle 分发包且无法下载），因此本地需要先安装 Gradle，或者直接使用仓库自带的 GitHub Actions 工作流。

```bash
gradle --no-daemon :core:domain:smokeTest :app:assembleDebug
```

```bash
python3 tools/verify_apk.py app/build/outputs/apk/debug/app-debug.apk
```

输出：`app/build/outputs/apk/debug/app-debug.apk`。这是开发测试用的 Debug 签名包，**不是官方签名或可发布的完整复刻版本**。项目内自带 arm64-v8a ELF 标识库，且 `ndk.abiFilters` 锁定 `arm64-v8a`，APK 校验器还会检查二进制 Manifest、DEX、resources.arsc 与所有 `.so` 的真实 AArch64 机器架构。

也可执行 `bash tools/build_arm64.sh`。GitHub Actions 的 `Android arm64 build` 会进行 Kotlin 领域测试、Android 编译、Lint、APK ABI 检查及 Debug 签名验证，成功后上传 APK Artifact。此工作流尚未在外部 CI 环境实际运行过。

## 无 Android SDK 时的离线检查

```bash
bash tools/check_domain.sh
bash tools/check_cloud_syntax.sh
bash tools/check_ble_syntax.sh
bash tools/check_viewmodel_syntax.sh
bash tools/check_ui_prefs_syntax.sh
python3 tools/test_apk_verifier.py
python3 tools/check_repository.py
python3 tools/release_gate.py
```

上述 Kotlin Android 模块测试仅使用**本地模拟桩**编译/驱动部分行为，不验证 Jetpack Compose、Android 真机运行或实际云端服务。

## 原版 APK 的证据再生成

本仓库只保留非敏感的结构证据。要自行复查，请提供合法取得的安装包文件路径：

```bash
python3 tools/verify_auth_contract.py '/path/to/reference.apk'
python3 tools/verify_login_flow.py '/path/to/reference.apk' --output docs/login_flow_evidence.json
python3 tools/verify_garage_page.py '/path/to/reference.apk' --output docs/garage_page_evidence.json
python3 tools/verify_ble_reference.py '/path/to/reference.apk' --output docs/ble_reference_evidence.json
python3 tools/verify_ble_architecture.py '/path/to/reference.apk' --output docs/ble_architecture_evidence.json
python3 tools/verify_ble_flow.py '/path/to/reference.apk' --output docs/ble_flow_evidence.json
```

这些脚本只读取本地文件，不连接厂家服务器。API 注解索引见 `docs/api_inventory.csv` 与 `docs/api_signatures.json`。原版登录、会话、车库字段证据分别见 `docs/login_flow_contract.md`、`docs/auth_vehicle_contract.md`、`docs/garage_protocol.md`，蓝牙证据见 `docs/ble_auth_static.md` 和 `docs/ble_gatt_readme.md`。

## 已知验证阻碍

- **人工滑块授权组件**：APK 证明存在 `ticket`、`randstr` → `captchaPassToken` → 登录短信流程，但独立客户端没有官方挑战 UI 接入授权；仅有合法调试票据输入，不生成或绕过挑战。
- **用户与车辆权限**：必须在本人账号下确认接口真实响应、车辆绑定、能力位和车端回执。车辆列表中的条目一律不作为控制授权依据。
- **BLE 协议分支**：原版存在 Nordic、Kuyi 和旧版 Android GATT 三条路径；设备名称/UUID 是提示而非凭据。未掌握经过验证的车型路由、加密握手和指令回执，控车保持禁用。
- **第三方厂商功能**：地图密钥、微信/支付宝、NFC 安全凭据、固件签名、车机 SDK、支付和推送通常需要服务提供方授权。
- **编译工具链**：当前生成源码的环境没有 Android SDK、Gradle 和外网依赖下载通道。不能宣称 APK 已构建。

## 安全与隐私

仅使用本人账号或获授权车辆进行测试。禁止绕过车端绑定与鉴权；云端只允许固定 HTTPS 主机并使用系统 TLS 校验，敏感会话由 Android Keystore AES-GCM 保护；BLE 检查不读取/写入特征值，不上传设备地址；OTA 未完成签名及硬件安全校验前禁用。无后台账号爬取、无强制联系人/通话记录权限、无用户分析 SDK。任何服务器成功响应都不能代替实车执行确认。

## 贡献约定

参见 [AGENTS.md](AGENTS.md)。不维护分阶段计划 Markdown；根据功能矩阵推进、以可靠证据实施、统一集成与测试。**在 Android 编译、合法账号、车型和真机测试之前，不应将任何云端/控车业务状态标记为 VERIFIED。**
