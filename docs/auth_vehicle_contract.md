# 账号与车库协议证据（APK 3.6.2）

本文记录可复核的 **静态 DEX** 证据，不包含任何实测账号请求或真实令牌。状态：`STATIC_APK`；线上可用性与车型兼容性均为 `NOT_RUN`。不保存厂商密钥、不抓取非本人账号。

## 来源及确切字段

| 原版类/方法 | 静态证据 | 在独立客户端中的用途 | 未验证事项 |
|---|---|---|---|
| `net/TailgRepository.login(String,String,String)` (`classes2.dex`) | `POST app/login`、`@Body Map`；常量 `macCode`、`phone`、`smsCode`、`autoCompleteUserDetail`、字符串 `"true"` | `SmsLoginInput.requestBody()` | `macCode` 值来源/服务端校验、全部成功结果形态 |
| `net/TailgRepository.loginCode(String,String)` | `POST app/sms/loginCode`，`@Query phone`、`@Query captchaPassToken` | `CloudGateway.sendSms()` | 滑块令牌发放机制、频控规则 |
| `net/TailgRepository.userCarList(Boolean)` | `POST app/userCarList`、`@Body Map`；`containShare` | `VehicleListWireContract.requestBody()` | share flag 权限、Response 的完整分页形式 |
| `net/TailgRepository.userCarListPage(Boolean,int)` | `POST app/userCarListPage`、`@Body Map`；`containShare`、`pageSize`、`nowPageIndex` | 仅定义与测试字段契约，不主动分页 | 页码起点、服务端默认页大小 |
| `net/TailgRetrofit.addHeaders()` (`classes2.dex`) | `Authorization` 来自 `PrefsUtil.getToken()`；`language`、`Forward-Service-Ip` 字段名 | 只发送有值的 `Authorization`，显式 `language=zh_CN` | `Forward-Service-Ip` 的值、是否必需 |
| `net/TailgRetrofit$1.intercept()` | 读取 **响应头** `Authorization`，调用 `PrefsUtil.saveToken()` | 登录成功后优先检查响应头，不凭空拼 Bearer | 返回空值时原版续签逻辑 |
| `net/ResponseTransfer$ResponseFunction.apply()` | `ResponseBean.code` 与字符串 `"0"` 比较，成功返回 `data`，错误读 `msg` | 统一拒绝非 `0` 业务响应 | 其他独立服务可能以 `200` 为成功 |
| `model/login/bean/BindPhoneBean` (`classes17.dex`) | `uid`、`uphone`、`userName`、`phoneBanded`、`infoPerfected` | 登录结果仅作最小 UID 解析 | 真实返回是否包此对象 |
| `model/login/bean/LoginBean$DataBean` | 包含 `token`，但尚无法证明此模型是本登录接口的实际返回值 | 仅当服务端真实返回 `data.token` 才使用 | token 所属流程及格式 |
| `model/mine_my_garage/bean/UserCarBean` | `carId`、`carName`、`carNickName`、`frame`、`online`、`electricQuantity` 等 | 只读车辆列表，能力集默认**为空** | 字段值/单位/在线状态语义 |

### 原版相关调用路线

- `CodeLoginActivity.init()` → `MacCodeUtils.getMac(Context)`；现代 Android 硬件 MAC 获取受限制，当前客户端**不伪造 MAC**。
- `CodeLoginViewModel.login()` → `TailgRepository.login()` → `TailgService.login(@Body Map)`。
- `CodeLoginViewModel.loginCode()` → `TailgRepository.loginCode()` → 带滑块结果 `captchaPassToken` 的 API。
- `GarageViewModel.execUserCarList()` → `TailgRepository.userCarList(Boolean)`。
- `GarageViewModel$1.onNext(List)` 表明该车库流程接收车辆列表。
- `TailgRetrofit` 默认构建 Base URL 来自 `TailgHost`；APK 中生产常量是 `https://www.tailgdd.com/v1/api/`。未连接线上验证。

## 已写入代码

- `core/domain/AuthVehicleContracts.kt`：输入规则、请求键、成功判定、只读车辆映射及测试。
- `core/cloud/CloudGateway.kt`：用户主动发起三个已知 HTTP 请求；只允许固定 HTTPS 主机、限制超时/响应大小、拒绝重定向、无自动重试、不把 token 写日志。
- `core/cloud/SessionVault.kt`：Keystore AES-GCM 加密本地会话；未返回令牌时不登录。
- `app/ClientViewModel.kt` 和 `app/MainActivity.kt`：实验性短信登录、读取服务器返回的车库、退出登录，原本未验证的控车按钮继续禁用。

## 明确未完成

1. **原版滑块验证码组件**尚未恢复，因此普通用户还不能完整使用短信登录。目前仅允许用户自行提供官方验证令牌进行可控测试。
2. `macCode` 的真实格式及手机限制不明。当前 UI 允许留空，没有仿造 Wi-Fi MAC；服务端可能拒绝。
3. 登录鉴权响应头的值和生命周期未动态验证；状态显示遵循严格成功判定。
4. 未对服务器进行任何联网测试，也未得到真实车辆返回。`userCarList` 解析只覆盖静态见证的基础字段，车型能力始终未启用。
5. 远程控制 / BLE / MQTT / OTA / NFC 仍未连通。

安全原则：仅对用户本人授权的账号和车辆开展验证，不获取或硬编码原版设备密钥，不绕过服务端或车端授权。
