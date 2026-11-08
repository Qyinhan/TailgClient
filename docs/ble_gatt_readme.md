# GATT 元信息检查：安全和验收边界

- **触发**：用户先扫描，勾选拥有设备或获授权确认，再选择本轮扫描到的设备；不能输入任意 MAC 地址进行连接。
- **行为**：GATT 短连接 → `discoverServices()` → 列出 Service/Characteristic UUID 及 `READ/WRITE/NOTIFY/INDICATE` *能力标志* → 立即断开。
- **禁止行为**：调用 `createBond`、`readCharacteristic`、`writeCharacteristic`、`setCharacteristicNotification`、读写 CCCD、发送车端控制/OTA 数据包。
- **超时/关闭**：12 秒连接超时；取消、切换页面、ViewModel 销毁、退出登录均主动关闭；迟到回调由 `BleGattSessionGate` 的 generation/phase 门禁拒绝，重复 CONNECTED 不得重复发现服务。
- **约束**：最多展示 24 个服务、每服务 24 项特征，总计最多 192 项特征；其余元信息截断且必须在页面和导出内容标记；不把扫描地址存储在磁盘，不持久化设备对象。
- **身份**：广播匹配仅作为候选提示，不证明台铃型号、所有权或已配对。UUID 属性提供的写入能力也不意味着操作许可。
- **已验证**：纯 Kotlin 策略测试、BLE Android API 测试桩编译及模拟回调行为测试；原版 APK 静态字段再生成。
- **未验证**：Gradle Android 构建、实机 BLE/GATT、车型兼容、后台重连和车辆控车。
