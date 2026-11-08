# 登录闭环证据（Stage 3，静态逆向）

**状态：STATIC_APK；线上协议测试 NOT_RUN；原版完整功能尚未复现。**

来源：用户提供的台铃智能 3.6.2 APK（SHA-256 见 `login_flow_evidence.json`）。可运行 `python3 tools/verify_login_flow.py /path/to/source.apk` 独立核验。仅分析允许持有的 APK，不向外部服务器发送请求。

## 已确认调用关系

| 阶段 | 原版代码或证据 | 工程行为 | 状态 |
|---|---|---|---|
| 人工挑战 | `WebDialog$MyJavascriptInterface.setSmsInfo()` 把 JSON 解析为 `SliderResBean`（ticket、randstr） | 人工挑战页面尚未移植，开发输入仅供授权测试 | 阻断 |
| 挑战服务端确认 | `TailgRepository.sliderVerify` 使用 `ticket`、`randstr` Map；`TailgService.sliderVerify` 注解 `POST app/captcha/slider/verify`、`@Body` | `CloudGateway.verifyChallengeAndSendSms()` 请求该接口 | 代码实现；未在线验证 |
| 令牌获取 | `SliderVerifyBean` 含 `captchaPassToken`、`captchaPassTokenExpireIn`、`passed`、`captchaCode`、`captchaMsg` | 拒绝无令牌、明确失败、明确到期；获取后立即使用，不持久化 | 代码实现；未在线验证 |
| 短信请求 | `CodeLoginViewModel$4.onNext()` 从 SliderVerifyBean 取 passToken 调用 `loginCode()` | `POST app/sms/loginCode?phone=...&captchaPassToken=...` | 代码实现；未在线验证 |
| 短信登录 | `TailgRepository.login()` 常量 `macCode`、`phone`、`smsCode`、`autoCompleteUserDetail` | 已实现同名 JSON body | 代码实现；未在线验证 |
| 设备标识 | `MacCodeUtils.getMac(Context)` 直接静态调用 `DeviceUtils.getUniqueDeviceId()` | 不伪造设备 MAC；暂保留用户自主输入的可空开发字段 | 语义未验证 |
| 会话生命周期 | `TailgRetrofit$1.intercept()` 从响应头 `Authorization` 调用 `PrefsUtil.saveToken()` | 成功认证响应才允许更新 Keystore 中令牌 | 代码实现；未在线验证 |

## 决策边界

- `passed` 字段若为 `false` 必拒绝；空值与 `captchaPassTokenExpireIn` 为空可能因后端版本差异出现。只有响应业务码 `0` 且令牌非空时才可继续；令牌仅一次性立即使用，不当成持久会话。
- 当前尚未找到一个**允许独立第三方应用合法使用**、可直接嵌入的人工验证码 SDK 配置或授权。不能复制官方 JS/专有密钥、绕过滑块验证或声称验证码已完整实现。
- `DeviceUtils.getUniqueDeviceId()` 使用的标识是否与独立安装包产生相同值，仍需对该库版本及授权行为进行分析；绝不读取原版私有数据。
- 未有用户真实授权的车辆、账号或在线测试结果，不能将任何 API 标成 `DEVICE_TESTED` 或 `DYNAMIC_CONFIRMED`。

## 需要实测

1. 官方可授权验证码组件以及它如何合法回传 ticket、randstr。
2. 验证接口对字段格式的要求、返回 `passed`/有效期实际类型。
3. 成功登录响应的实际 `Authorization` 和用户数据结构。
4. 新应用设备标识的接受条件、Token 是否续期、401 失效分支。
5. 车辆列表在不同账户和车型下的真实 JSON 结构。
