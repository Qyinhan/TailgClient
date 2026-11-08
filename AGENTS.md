# AGENTS.md — TailgClient

## 目标

独立 Android 客户端，目标对标用户提供的台铃智能 3.6.2 全部业务。Android 仅 Kotlin + Jetpack Compose + Material 3，构建产物限定 `arm64-v8a`。未来 iOS 用 SwiftUI 原生 UI；共享层禁止依赖 Android。项目不隶属于台铃官方。

## AI 工作方式

- 自主拆解与实现，不反复要求用户批准每一步；支持多代理时并行，最终统一集成、验证、交付。
- **不要创建** `STATE.md`、`TASKS.md`、`BLOCKERS.md` 等阶段计划文档。功能事实更新 `docs/feature_matrix.csv`；必要协议证据写入已有 `docs/`。
- 修改前阅读 `README.md`、`docs/feature_matrix.csv`、相关业务模块和原 APK 证据；不能根据接口名臆造载荷或车型支持。
- 不得将 Mock、静态注解、测试桩或 UI 占位当成「功能已实现」；每项功能需在线/实车验收才能提升到完成。
- 先修编译失败、安全问题和回归，再增加新功能；不要为扩展性过度拆模块。

## 工程边界

- `core/domain`：纯 Kotlin 车辆模型、授权策略、分页、状态机与用例。不得引入 `android.*` 或 Compose。
- `core/protocol`：通过接口隔离 BLE、MQTT、设备身份、车型协议和车端回执；未经验证的适配器保持 fail-closed。
- `core/cloud`：仅合法授权请求、固定 HTTPS 主机、系统 TLS、无重定向/盲目重试、限大小响应、短超时；Token 不进日志或 URL。
- `core/ble`：按最小权限进行用户触发扫描和只读 GATT 元信息检查。广播/UUID 匹配不是设备认证；不能凭静态常量实施配对、读取特征、写指令或订阅。
- `app`：Compose 只呈现状态与分发事件；Theme/外观本地偏好不得存储认证数据。`ClientViewModel` 管理任务和生命周期，退出后旧回调不得恢复会话。

## 安全不变量

- 仅针对本人拥有或明确获授权的账号、车辆、硬件进行测试。禁止绕过绑定、冒用设备凭据或跳过服务端认证。
- 不提交原版 APK、私有源代码、厂商密钥、Token、手机号、车辆地址或受版权保护的素材。
- 明文 HTTP、信任任意 TLS 证书、伪造验证码票据、持久化 BLE 地址均禁止。短信和登录由用户主动触发；人工滑块票据由合法组件产生，不绕过验证。
- 客户端控车安全门禁不是后端鉴权。`VehicleCommandResult.VehicleAcknowledged` 只可在**车端真实确认**后生成，绝不能将 HTTP 200 当成功。
- 车辆列表条目可能是共享车辆：不得默认设置 `boundToCurrentAccount=true` 或赋予能力位。
- 任何 OTA 必须先确认车型、签名/哈希、供电与车速、安全失败恢复；未验证时无升级入口。
- GATT 只在用户明确许可、当前扫描到的设备上连接，12 秒超时、去重、取消后拒绝旧回调，匿名化输出需用户主动触发。

## 代码与 UI 规范

- 包名小写，类用 PascalCase，函数变量 camelCase，常量 UPPER_SNAKE_CASE；公共 API 使用简洁 KDoc。
- 业务数据模型使用明确的数据类与 sealed 状态，避免随意 `Any`、魔法字符串、跨层 `Context`。
- UI 全面遵循 Material 3；支持系统/浅色/深色及动态配色，尊重无障碍与系统手势。控车失败/超时不能假装成功。
- 注释解释业务约束与原因而非逐行翻译代码；用户可见文案简体中文、简短、诚实。
- 任何外部调用具备明确超时、错误路径、权限验证、幂等策略。启动/解锁等敏感动作不得自动重试。

## 验收顺序

1. `bash tools/check_domain.sh`、`bash tools/check_cloud_syntax.sh`、`bash tools/check_ble_syntax.sh`、`bash tools/check_viewmodel_syntax.sh`、`bash tools/check_ui_prefs_syntax.sh`。
2. `python3 tools/check_repository.py`、`python3 tools/test_apk_verifier.py`、`python3 tools/release_gate.py`。失败必须修正，不能手改工具以掩盖问题。
3. 具备 Android SDK 与 Gradle 后：`gradle --no-daemon :core:domain:smokeTest :app:assembleDebug :app:lintDebug`。
4. 用 `python3 tools/verify_apk.py app/build/outputs/apk/debug/app-debug.apk` 确认 DEX、编译资源及**所有原生库**真实为 ARM64；再以 `apksigner verify` 和实机安装验证。
5. 在账号、车型、厂商 SDK 和设备授权下逐项对照原版；每次提升功能状态必须附真实测试证据。发布前执行 `python3 tools/release_gate.py --require-full`，未达到完整功能时应失败。

**重要：当前只有部分功能代码与静态证据，未产生 APK，也未完成任何原版业务的在线/实车验收；不要将构建脚本存在误写为编译成功。**
