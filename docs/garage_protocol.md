# 车辆分页接口：逆向证据与实现约束

证据来源：用户提供的 **台铃智能 3.6.2 APK**。本文件不是运行成功证明；线上验证：**NOT_RUN**。

## 已确认的静态事实

| 内容 | 原版证据 |
|---|---|
| HTTP | `TailgService.userCarListPage` 使用 `@POST("app/userCarListPage")`、`@Body` |
| 请求 | `TailgRepository.userCarListPage(Boolean, int)` 使用 `containShare`、`pageSize`、`nowPageIndex` |
| 单页数 | `pageSize` 的 `const/16` 值为 **10**，不是接口强制上限 |
| 返回模型 | `mine_family_sharing/bean/UserCarListPageBean` 含 `pageData: List`、`hasNext: String`、`total: String`、`pageSize: String`、`nowPageIndex: String` |
| 另一套车库接口 | `TailgRepository.userCarPage` 使用 `pageSize`、`nowPageIndex`、`frame`、`shareUserPhone`、`serial`，默认大小字符串 `"5"`，与此分页接口不混用 |

原版还存在 `userCarList`（非分页），不能把这三个接口的返回模型视为完全相同。

## 本工程实现

- 使用 `POST app/userCarListPage`，默认 `containShare=true`、`pageSize=10`、`nowPageIndex` 从 1 起；仅已有授权会话允许请求。
- 只读解析 `pageData`（数组）、`hasNext`（字符串或布尔）、`total`。若响应包裹层不匹配，就明确失败，不展示虚构数据。
- 手动点击“加载更多”，最多读取 50 页；以 `carId` 去重。服务端明确 `hasNext` 优先于数量推断；重复整页时停止以避免无限循环。
- 返回对象只有已证明的字段映射，且默认 `capabilities=emptySet()`、`boundToCurrentAccount=false`。特别是共享车辆不应被误认为当前账号的所有权。
- 用户退出登录后禁止迟到的 HTTP 响应复活 Token；旧请求返回 401 不得删除新登录会话。

## 仍未验证

- `UserCarListPageBean` 在当前生产环境的真实响应外层结构、`hasNext` 的实际值编码和分页起始页。
- 列表具体 `pageData` 项类型与不同车型的可选字段；当前字段是静态候选项。
- 账号归属、共享授权、车端指令能力的服务端验证，以及空页或非标准分页响应的线上行为。

**复现检查**：`python3 tools/verify_garage_page.py /path/to/台铃智能_3.6.2.apk`。
