package dev.tailg.core.domain

/**
 * Static evidence catalog from the user-supplied 3.6.2 APK.
 * Discovered != implemented, tested or even available on a particular vehicle.
 */
enum class FeatureGroup(val displayName: String) {
    ACCOUNT("账号与认证"), VEHICLE("车辆管理"), CONTROL("车辆控制"),
    BLUETOOTH("蓝牙钥匙"), STATUS("车辆状态"), LOCATION("定位与轨迹"),
    SAFETY("车辆安全"), DEVICE("智能设备"), OTA("固件升级"),
    SHARING("共享与转让"), NAVIGATION("地图与导航"), CARLINK("车机互联"),
    MESSAGE("消息"), CONNECTIVITY("车联网服务"), SERVICES("用户与服务"),
    SETTINGS("设置与其他"),
}

enum class EvidenceLevel { STATIC_APK, REQUIRES_DYNAMIC_VALIDATION }
enum class ImplementationState { NOT_STARTED, IN_PROGRESS, VERIFIED }

data class FeatureSpec(
    val id: String,
    val title: String,
    val group: FeatureGroup,
    val evidence: EvidenceLevel,
    val state: ImplementationState = ImplementationState.NOT_STARTED,
)

object FeatureCatalog {
    val all: List<FeatureSpec> = listOf(
        feature("account.password", "账号密码登录", FeatureGroup.ACCOUNT),
        feature("account.sms", "短信验证码登录", FeatureGroup.ACCOUNT),
        feature("account.oneclick", "一键登录", FeatureGroup.ACCOUNT),
        feature("account.wx", "微信登录与绑定", FeatureGroup.ACCOUNT),
        feature("account.zfb", "支付宝登录与绑定", FeatureGroup.ACCOUNT),
        feature("account.profile", "用户资料与账号管理", FeatureGroup.ACCOUNT),
        feature("vehicle.list", "车辆列表与切换", FeatureGroup.VEHICLE),
        feature("vehicle.bind", "扫码与车辆绑定", FeatureGroup.VEHICLE),
        feature("vehicle.unbind", "车辆解绑", FeatureGroup.VEHICLE),
        feature("vehicle.ability", "车型能力与配件管理", FeatureGroup.VEHICLE),
        feature("vehicle.rename", "车辆资料修改", FeatureGroup.VEHICLE),
        feature("control.lock", "远程上锁", FeatureGroup.CONTROL),
        feature("control.unlock", "远程解锁", FeatureGroup.CONTROL),
        feature("control.start", "远程启动", FeatureGroup.CONTROL),
        feature("control.stop", "远程熄火", FeatureGroup.CONTROL),
        feature("control.find", "远程寻车", FeatureGroup.CONTROL),
        feature("control.cushion", "远程开座桶", FeatureGroup.CONTROL),
        feature("control.status", "远程车辆自检", FeatureGroup.CONTROL),
        feature("ble.scan", "BLE 扫描与连接", FeatureGroup.BLUETOOTH),
        feature("ble.pair", "BLE 配对与本地指令", FeatureGroup.BLUETOOTH),
        feature("ble.reconnect", "BLE 自动重连", FeatureGroup.BLUETOOTH),
        feature("status.battery", "电池电量与 BMS", FeatureGroup.STATUS),
        feature("status.mileage", "里程与骑行数据", FeatureGroup.STATUS),
        feature("status.tire", "胎压监测", FeatureGroup.STATUS),
        feature("status.temperature", "温度与故障状态", FeatureGroup.STATUS),
        feature("location.vehicle", "车辆实时位置", FeatureGroup.LOCATION),
        feature("location.history", "历史轨迹", FeatureGroup.LOCATION),
        feature("location.fence", "电子围栏", FeatureGroup.LOCATION),
        feature("safety.password", "密码解锁设置", FeatureGroup.SAFETY),
        feature("safety.alarm", "车辆安全提醒", FeatureGroup.SAFETY),
        feature("device.nfc", "NFC 钥匙", FeatureGroup.DEVICE),
        feature("device.bms", "电池及智能部件配置", FeatureGroup.DEVICE),
        feature("ota.check", "固件版本检测", FeatureGroup.OTA),
        feature("ota.update", "OTA 固件升级", FeatureGroup.OTA),
        feature("share.users", "车辆共享与家庭成员", FeatureGroup.SHARING),
        feature("share.transfer", "车辆转让", FeatureGroup.SHARING),
        feature("nav.map", "地图与车辆定位", FeatureGroup.NAVIGATION),
        feature("nav.stores", "服务网点与充电站", FeatureGroup.NAVIGATION),
        feature("carlink.easyconn", "车机互联", FeatureGroup.CARLINK),
        feature("carlink.wallpaper", "车机壁纸", FeatureGroup.CARLINK),
        feature("message.system", "系统消息", FeatureGroup.MESSAGE),
        feature("message.vehicle", "车辆消息与推送设置", FeatureGroup.MESSAGE),
        feature("sim.flow", "SIM 流量查询", FeatureGroup.CONNECTIVITY),
        feature("sim.package", "SIM 套餐与充值", FeatureGroup.CONNECTIVITY),
        feature("service.feedback", "反馈与评价", FeatureGroup.SERVICES),
        feature("service.payment", "订单与支付", FeatureGroup.SERVICES),
        feature("service.after_sales", "售后与客服", FeatureGroup.SERVICES),
        feature("service.social", "社区与社交", FeatureGroup.SERVICES),
        feature("settings.app", "应用设置与版本更新", FeatureGroup.SETTINGS),
        feature("settings.privacy", "协议与隐私设置", FeatureGroup.SETTINGS),
    )

    fun byGroup(group: FeatureGroup): List<FeatureSpec> = all.filter { it.group == group }

    private fun feature(id: String, title: String, group: FeatureGroup): FeatureSpec =
        FeatureSpec(
            id = id,
            title = title,
            group = group,
            evidence = EvidenceLevel.STATIC_APK,
            state = if (id in setOf("account.sms", "vehicle.list", "ble.scan", "settings.app")) {
                ImplementationState.IN_PROGRESS
            } else {
                ImplementationState.NOT_STARTED
            },
        )
}
