package dev.tailg.client

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tailg.core.domain.BleGattState
import dev.tailg.core.domain.BleGattEvidence
import dev.tailg.core.domain.BleReferencePolicy
import dev.tailg.core.domain.FeatureCatalog
import dev.tailg.core.domain.FeatureGroup
import dev.tailg.core.domain.FeatureSpec
import dev.tailg.core.domain.GarageLoadState
import dev.tailg.core.domain.ImplementationState

/**
 * MD3 shell + experimental authorized login/vehicle pagination.
 * All unverified actions are disabled to prevent misleading success indications.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = UiPreferences(this)
        setContent {
            val ui by preferences.state.collectAsState()
            ClientTheme(ui) {
                ClientApp(
                    preferences = ui,
                    onSetTheme = preferences::setTheme,
                    onSetDynamicColor = preferences::setDynamicColor,
                )
            }
        }
    }
}

@Composable
private fun ClientTheme(preferences: UiPreferencesState, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = when (preferences.theme) {
        UiThemeMode.SYSTEM -> isSystemInDarkTheme()
        UiThemeMode.LIGHT -> false
        UiThemeMode.DARK -> true
    }
    val colors = when {
        preferences.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dark -> dynamicDarkColorScheme(context)
        preferences.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF92D9BD), secondary = Color(0xFFB5D3C5))
        else -> lightColorScheme(primary = Color(0xFF006C4F), secondary = Color(0xFF50685B))
    }
    MaterialTheme(colorScheme = colors, content = content)
}

private enum class MainTab(val title: String, val glyph: String) {
    VEHICLE("车辆", "▣"), BLUETOOTH("蓝牙", "ᛒ"), LOCATION("位置", "⌖"), SERVICES("服务", "◇"), PROFILE("我的", "◉")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClientApp(
    preferences: UiPreferencesState,
    onSetTheme: (UiThemeMode) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
) {
    val vm: ClientViewModel = viewModel()
    val state by vm.state.collectAsState()
    var tabName by rememberSaveable { mutableStateOf(MainTab.VEHICLE.name) }
    val selected = MainTab.entries.firstOrNull { it.name == tabName } ?: MainTab.VEHICLE
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = {
                Text(if (selected == MainTab.VEHICLE) "电动车助手" else selected.title)
            })
        },
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selected == tab,
                        onClick = { tabName = tab.name },
                        icon = { Text(tab.glyph, style = MaterialTheme.typography.titleLarge) },
                        label = { Text(tab.title) },
                    )
                }
            }
        },
    ) { insets ->
        when (selected) {
            MainTab.VEHICLE -> VehicleScreen(
                modifier = Modifier.padding(insets),
                state = state,
                onOpenLogin = { tabName = MainTab.PROFILE.name },
                onRefresh = vm::refreshGarage,
                onLoadMore = vm::loadMoreGarage,
                onSelectVehicle = vm::selectVehicle,
            )
            MainTab.BLUETOOTH -> BleDiscoveryScreen(Modifier.padding(insets), state, vm)
            MainTab.LOCATION -> CatalogScreen(
                modifier = Modifier.padding(insets),
                subtitle = "车辆位置与历史轨迹需要实际账号及定位接口验证。",
                groups = listOf(FeatureGroup.LOCATION, FeatureGroup.NAVIGATION),
            )
            MainTab.SERVICES -> CatalogScreen(
                modifier = Modifier.padding(insets),
                subtitle = "服务、订单、社区及车辆附加功能正在建立协议索引。",
                groups = listOf(
                    FeatureGroup.DEVICE, FeatureGroup.OTA, FeatureGroup.CARLINK,
                    FeatureGroup.CONNECTIVITY, FeatureGroup.SERVICES, FeatureGroup.SHARING,
                ),
            )
            MainTab.PROFILE -> AccountScreen(
                modifier = Modifier.padding(insets),
                state = state,
                onSendCode = vm::requestSms,
                onLogin = vm::login,
                onLogout = vm::logout,
                onRefresh = vm::refreshGarage,
                preferences = preferences,
                onSetTheme = onSetTheme,
                onSetDynamicColor = onSetDynamicColor,
            )
        }
    }
}

@Composable
private fun VehicleScreen(
    modifier: Modifier = Modifier,
    state: ClientUiState,
    onOpenLogin: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onSelectVehicle: (String) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("我的车辆", style = MaterialTheme.typography.labelLarge)
                    Text(if (state.hasSession) "已建立会话" else "尚未登录", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "车辆控制、状态及车型能力仍需协议验证。不会展示虚构的电量、位置或续航。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text("开发验证版本 · 0.9.0", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        item { SectionHeading("我的车库", "仅展示服务器实际返回的已授权车辆") }
        item {
            if (!state.hasSession) {
                OutlinedButton(onClick = onOpenLogin, modifier = Modifier.fillMaxWidth()) { Text("前往账号登录") }
            } else {
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !state.busy && !state.loadingMore && state.garage != GarageLoadState.Loading,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("刷新车辆列表") }
            }
        }
        when (val garage = state.garage) {
            GarageLoadState.Loading -> item { CircularProgressIndicator() }
            GarageLoadState.NotLoaded -> item { Text("尚未读取车辆", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            is GarageLoadState.Failed -> item { Text(garage.message, color = MaterialTheme.colorScheme.error) }
            is GarageLoadState.Ready -> {
                if (garage.vehicles.isEmpty()) {
                    item { Text("当前账号没有返回车辆", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else {
                    items(garage.vehicles, key = { it.summary.id.value }) { vehicle ->
                        Card(shape = RoundedCornerShape(18.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(vehicle.summary.displayName, style = MaterialTheme.typography.titleMedium)
                                Text("车辆 ID：" + vehicle.summary.id.value, style = MaterialTheme.typography.bodySmall)
                                vehicle.frame?.let { Text("车架号：$it", style = MaterialTheme.typography.bodySmall) }
                                vehicle.online?.let { Text("在线字段：$it（含义未验证）", style = MaterialTheme.typography.labelSmall) }
                                vehicle.electricityText?.let { Text("电量字段：$it（单位未验证）", style = MaterialTheme.typography.labelSmall) }
                                OutlinedButton(
                                    onClick = { onSelectVehicle(vehicle.summary.id.value) },
                                    enabled = state.selectedVehicleId != vehicle.summary.id.value,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(if (state.selectedVehicleId == vehicle.summary.id.value) "当前查看车辆" else "选择查看")
                                }
                            }
                        }
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(
                            "已载入 ${garage.snapshot.loadedPages} 页、${garage.vehicles.size} 辆" +
                                (garage.snapshot.totalHint?.let { " / 服务端总数 $it" } ?: ""),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (garage.snapshot.hasMore) {
                            OutlinedButton(
                                onClick = onLoadMore,
                                enabled = !state.busy && !state.loadingMore,
                            ) { Text(if (state.loadingMore) "加载中…" else "加载更多") }
                        } else {
                            Text("暂无更多", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (state.loadingMore) CircularProgressIndicator()
                }
            }
        }
        item {
            SectionHeading("车辆控制", "协议未验证前禁止发送真实控车指令")
            Text(
                if (state.selectedVehicleId == null) "尚未选择车辆" else "已选择车辆（仅本地查看，不代表控车授权）",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        item {
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ControlButton("上锁", Modifier.weight(1f))
                        ControlButton("解锁", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ControlButton("启动", Modifier.weight(1f))
                        ControlButton("熄火", Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ControlButton("寻车", Modifier.weight(1f))
                        ControlButton("开座桶", Modifier.weight(1f))
                    }
                }
            }
        }
        item { SectionHeading("车辆相关功能", "从原版 APK 静态识别，尚未完成实车验证") }
        items(listOf(FeatureGroup.VEHICLE, FeatureGroup.STATUS, FeatureGroup.BLUETOOTH, FeatureGroup.CONTROL, FeatureGroup.SAFETY)) { group ->
            CatalogGroupCard(group)
        }
        item { DevelopmentNotice() }
    }
}

@Composable
private fun AccountScreen(
    modifier: Modifier,
    state: ClientUiState,
    onSendCode: (String, String, String) -> Unit,
    onLogin: (String, String, String) -> Unit,
    onLogout: () -> Unit,
    onRefresh: () -> Unit,
    preferences: UiPreferencesState,
    onSetTheme: (UiThemeMode) -> Unit,
    onSetDynamicColor: (Boolean) -> Unit,
) {
    var phone by remember { mutableStateOf("") }
    var smsCode by remember { mutableStateOf("") }
    var challengeTicket by remember { mutableStateOf("") }
    var challengeRandstr by remember { mutableStateOf("") }
    var deviceCode by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionHeading("外观设置", "仅保存在本机，不依赖台铃服务器")
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("主题模式", style = MaterialTheme.typography.titleMedium)
                    UiThemeMode.entries.forEach { mode ->
                        OutlinedButton(
                            onClick = { onSetTheme(mode) },
                            enabled = preferences.theme != mode,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (preferences.theme == mode) "✓ ${mode.label}" else mode.label) }
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = preferences.dynamicColor, onCheckedChange = onSetDynamicColor)
                        Text("使用系统动态配色（Android 12+）")
                    }
                    Text("不收集设备地址、位置信息或车辆数据用于分析。当前没有后台定位和自动控车。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { SectionHeading("账号与车辆", "实验性接口接入 · 不会自动登录或抓取账号数据") }
        item {
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.hasSession) {
                        Text("已保存加密会话", style = MaterialTheme.typography.titleMedium)
                        Text("Token 保存在 Android Keystore 保护的本地存储中，不会显示或输出日志。")
                        OutlinedButton(onClick = onRefresh, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                            Text("加载已绑定车辆")
                        }
                        OutlinedButton(onClick = onLogout, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                            Text("退出并清除会话")
                        }
                    } else {
                        Text("短信验证码登录", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "原版请求字段已静态确认，但滑块验证码、设备标识和服务端兼容性尚未验证。只有主动点击按钮才会访问台铃服务器。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = phone, onValueChange = { phone = it.take(11) },
                            label = { Text("手机号") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "原版要求先完成人工滑块挑战，再由台铃服务器把 ticket、randstr 换成短信令牌。" +
                                "本版尚未集成获授权的验证码组件；以下字段仅供已合法取得挑战票据的开发验证使用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = challengeTicket, onValueChange = { challengeTicket = it.take(8192) },
                            label = { Text("人工挑战 ticket（开发验证）") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = challengeRandstr, onValueChange = { challengeRandstr = it.take(8192) },
                            label = { Text("人工挑战 randstr（开发验证）") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(
                            onClick = {
                                onSendCode(phone, challengeTicket, challengeRandstr)
                                challengeTicket = ""
                                challengeRandstr = ""
                            },
                            enabled = consent && !state.busy && phone.length == 11 &&
                                challengeTicket.isNotBlank() && challengeRandstr.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("核验挑战并请求短信（实验性）") }
                        OutlinedTextField(
                            value = smsCode, onValueChange = { smsCode = it.filter(Char::isDigit).take(8) },
                            label = { Text("短信验证码") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = deviceCode, onValueChange = { deviceCode = it.take(128) },
                            label = { Text("macCode（可留空，语义待验证）") },
                            supportingText = { Text("原版此字段由 DeviceUtils.getUniqueDeviceId() 生成；尚未确认跨 App 兼容性，不伪造身份") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked = consent, onCheckedChange = { consent = it })
                            Text("我仅使用本人账号和授权车辆，同意进行实验性接口测试", style = MaterialTheme.typography.bodySmall)
                        }
                        Button(
                            onClick = { onLogin(phone, smsCode, deviceCode); smsCode = "" },
                            enabled = consent && !state.busy && smsCode.length in 4..8 && phone.length == 11,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("登录（实验性）") }
                    }
                    if (state.busy) CircularProgressIndicator()
                    if (state.notice.isNotBlank()) {
                        Text(state.notice, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item { SectionHeading("其他账号功能", "原版功能需要继续验证及适配") }
        items(listOf(FeatureGroup.ACCOUNT, FeatureGroup.MESSAGE, FeatureGroup.SETTINGS)) { CatalogGroupCard(it) }
        item { DevelopmentNotice() }
    }
}

/** Discovery is read-only; authorization to control a vehicle is never inferred from an ad. */
@Composable
private fun BleDiscoveryScreen(modifier: Modifier, state: ClientUiState, vm: ClientViewModel) {
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.isNotEmpty() && result.values.all { it }) vm.startBleScan()
        else vm.blePermissionDenied()
    }
    DisposableEffect(Unit) { onDispose { vm.leaveBleScreen() } }
    // Consent cannot survive process recreation. Re-acknowledge before each device session.
    var gattConsent by remember { mutableStateOf(false) }
    var copyStatus by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { SectionHeading("附近蓝牙设备", "广播可被伪装；只能识别可能的设备，不代表已绑定") }
        item {
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("BLE 设备发现", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "需要蓝牙权限；Android 11 及以下还需定位授权。设备名称和服务 UUID 只来自广播，不能证明它是台铃车辆。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = {
                            val missing = vm.blePermissions()
                            if (missing.isEmpty()) vm.startBleScan() else permissionLauncher.launch(missing)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.bleRunning,
                    ) { Text(if (state.bleRunning) "扫描中…" else "开始扫描（15 秒）") }
                    if (state.bleRunning) {
                        OutlinedButton(onClick = vm::stopBleScan, modifier = Modifier.fillMaxWidth()) {
                            Text("停止扫描")
                        }
                    }
                    Text(state.bleNotice, style = MaterialTheme.typography.bodySmall)
                    Text("发现 ${state.bleDevices.size} 台设备", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = gattConsent, onCheckedChange = { gattConsent = it })
                        Text(
                            "我拥有或获授权检查所选设备。允许短暂连接并枚举服务（不读取数据）。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        items(state.bleDevices, key = { it.address }) { observed ->
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(observed.advertisedName ?: "未提供设备名称", style = MaterialTheme.typography.titleMedium)
                    Text("设备地址：${observed.address}", style = MaterialTheme.typography.bodySmall)
                    Text("信号：${observed.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                    if (observed.serviceUuids.isNotEmpty()) {
                        Text("广播服务 UUID：" + observed.serviceUuids.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                    }
                    val match = BleReferencePolicy.inspect(observed)
                    Text(
                        if (match.isCandidate) "与原版静态标识匹配（仅为候选设备）" else "未匹配原版已知广播标识",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    if (match.nameHint != null) Text("名称线索：${match.nameHint}", style = MaterialTheme.typography.bodySmall)
                    if (match.uuidHints.isNotEmpty()) Text("UUID 线索：${match.uuidHints.size} 项", style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = {
                            copyStatus = ""
                            vm.inspectBleGatt(observed.address, gattConsent)
                        },
                        enabled = gattConsent && state.gattState !is BleGattState.Connecting &&
                            state.gattState !is BleGattState.Discovering,
                    ) { Text("连接并查看 GATT 服务") }
                }
            }
        }
        item {
            Card(shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("GATT 元信息", style = MaterialTheme.typography.titleMedium)
                    state.gattTarget?.let { Text("检查设备：$it", style = MaterialTheme.typography.bodySmall) }
                    when (val gatt = state.gattState) {
                        BleGattState.Idle -> Text("选择一台设备并主动开始连接", style = MaterialTheme.typography.bodySmall)
                        BleGattState.Connecting -> { Text("连接中（最多 12 秒）"); CircularProgressIndicator() }
                        BleGattState.Discovering -> { Text("正在发现服务，不读取特征值"); CircularProgressIndicator() }
                        is BleGattState.Failed -> Text(gatt.message, color = MaterialTheme.colorScheme.error)
                        is BleGattState.Discovered -> {
                            Text("已获取 ${gatt.services.size} 个 GATT 服务；连接已自动断开。", style = MaterialTheme.typography.bodySmall)
                            if (gatt.truncated) Text(
                                "结果超出安全限额，已截断；未显示的服务不代表设备不支持。",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            val evidence = BleGattEvidence.inspect(gatt.services)
                            Text(
                                "原版 APK UUID 静态命中：服务 ${evidence.matchedServices.size} 项、特征 ${evidence.matchedCharacteristics.size} 项（不能证明车辆身份）。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(onClick = {
                                clipboard.setText(AnnotatedString(BleGattEvidence.anonymousReport(gatt.services, gatt.truncated)))
                                copyStatus = "已复制 UUID 与属性摘要；请检查内容后决定是否分享。"
                            }) { Text("复制匿名化 GATT 信息") }
                            if (copyStatus.isNotEmpty()) Text(copyStatus, style = MaterialTheme.typography.bodySmall)
                            gatt.services.forEach { service ->
                                Text(service.uuid, style = MaterialTheme.typography.labelMedium)
                                service.characteristics.forEach { item ->
                                    val flags = buildList {
                                        if (item.supportsRead) add("R")
                                        if (item.supportsWrite) add("W")
                                        if (item.supportsNotify) add("N")
                                        if (item.supportsIndicate) add("I")
                                    }
                                    Text("  ${item.uuid}  [${flags.joinToString("/")}]", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    if (state.gattState is BleGattState.Connecting || state.gattState is BleGattState.Discovering) {
                        OutlinedButton(onClick = vm::cancelGattInspection) { Text("取消检查并断开") }
                    }
                }
            }
        }
        item { SectionHeading("待适配", "设备身份、配对、自动重连与控车仍需实车验证") }
        item { CatalogGroupCard(FeatureGroup.BLUETOOTH) }
        item { DevelopmentNotice() }
    }
}

@Composable
private fun ControlButton(text: String, modifier: Modifier = Modifier) {
    Button(onClick = {}, enabled = false, modifier = modifier, shape = RoundedCornerShape(16.dp)) {
        Text(text)
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CatalogScreen(modifier: Modifier = Modifier, subtitle: String, groups: List<FeatureGroup>) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { SectionHeading("功能目录", subtitle) }
        items(groups) { group -> CatalogGroupCard(group) }
        item { DevelopmentNotice() }
    }
}

@Composable
private fun CatalogGroupCard(group: FeatureGroup) {
    val features = FeatureCatalog.byGroup(group)
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(group.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            for (feature in features) {
                FeatureLine(feature)
            }
        }
    }
}

@Composable
private fun FeatureLine(feature: FeatureSpec) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = "· ${feature.title}",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (feature.state == ImplementationState.IN_PROGRESS) "部分实现" else "待实现",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DevelopmentNotice() {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("当前为开发基线", style = MaterialTheme.typography.titleSmall)
            Text(
                "此界面仅展示已识别的业务范围，不代表这些功能已接入。所有控车动作必须经过授权验证及实车确认后才能启用。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
}
