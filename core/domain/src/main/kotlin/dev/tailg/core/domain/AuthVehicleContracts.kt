package dev.tailg.core.domain

/** Static DEX evidence: TailgRepository.login() builds these exact map keys. */
data class SmsLoginInput(
    val phone: String,
    val smsCode: String,
    val macCode: String = "",
) {
    fun validate(): String? = when {
        !phone.matches(Regex("1[3-9][0-9]{9}")) -> "请输入有效的中国大陆手机号"
        !smsCode.matches(Regex("[0-9]{4,8}")) -> "请输入 4–8 位短信验证码"
        macCode.length > 128 -> "设备标识长度超出限制"
        else -> null
    }

    /** autoCompleteUserDetail is a STRING "true" in the reference bytecode, not a JSON boolean. */
    fun requestBody(): Map<String, Any> {
        require(validate() == null) { "Invalid login input" }
        return linkedMapOf(
            "macCode" to macCode,
            "phone" to phone,
            "smsCode" to smsCode,
            "autoCompleteUserDetail" to "true",
        )
    }
}

/** Request/body keys and boolean type recovered from the original Repository methods. */
object VehicleListWireContract {
    fun requestBody(containShare: Boolean = true): Map<String, Any> =
        mapOf("containShare" to containShare)

    fun pageBody(containShare: Boolean, pageSize: Int = 10, pageIndex: Int): Map<String, Any> {
        require(pageSize in 1..100)
        require(pageIndex >= 1)
        return mapOf("containShare" to containShare, "pageSize" to pageSize, "nowPageIndex" to pageIndex)
    }
}

sealed interface SessionDecision {
    data class Accepted(val token: String) : SessionDecision {
        override fun toString() = "SessionDecision.Accepted([REDACTED])"
    }
    data class Incomplete(val reason: String) : SessionDecision
}

/**
 * Original interceptor reads Authorization response header; LoginBean also has a token field.
 * Neither field is sufficient unless the business code confirms success. Token wire format
 * (including any prefix) must be preserved, never synthesized by this client.
 */
object SessionReceiptPolicy {
    fun inspect(businessCode: String?, responseAuthorization: String?, dataToken: String?): SessionDecision {
        if (businessCode != "0") return SessionDecision.Incomplete("服务端未确认登录成功")
        val token = sequenceOf(responseAuthorization, dataToken)
            .mapNotNull { candidate ->
                candidate?.takeIf { '\n' !in it && '\r' !in it }?.trim()?.takeIf(String::isNotEmpty)
            }
            .firstOrNull { it.length <= 8192 }
        return if (token == null) {
            SessionDecision.Incomplete("登录响应中没有可验证的会话令牌；尚不能加载车辆")
        } else {
            SessionDecision.Accepted(token)
        }
    }
}

/**
 * Only userCarList's basic fields are mapped. Capability bits are deliberately absent:
 * a vehicle's list membership does NOT prove which remote commands are supported,
 * or that it is owned by the current account (shared cars can also be listed).
 */
data class VehicleListRecord(
    val carId: String?,
    val carName: String?,
    val carNickName: String?,
    val frame: String?,
    val online: String?,
    val electricQuantity: String?,
)

data class ListedVehicle(
    val summary: VehicleSummary,
    val frame: String?,
    val online: String?,
    val electricityText: String?,
)

object VehicleListMapper {
    fun map(record: VehicleListRecord): ListedVehicle? {
        val id = record.carId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val label = sequenceOf(record.carNickName, record.carName)
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull() ?: "未命名车辆"
        return ListedVehicle(
            summary = VehicleSummary(VehicleId(id), label, emptySet(), boundToCurrentAccount = false),
            frame = record.frame?.takeIf(String::isNotBlank),
            online = record.online?.takeIf(String::isNotBlank),
            electricityText = record.electricQuantity?.takeIf(String::isNotBlank),
        )
    }
}

/** Read-only vehicle list refresh states, no command state is inferred from these. */
sealed interface GarageLoadState {
    data object NotLoaded : GarageLoadState
    data object Loading : GarageLoadState
    data class Ready(val snapshot: GarageSnapshot) : GarageLoadState {
        val vehicles: List<ListedVehicle> get() = snapshot.vehicles
    }
    data class Failed(val message: String) : GarageLoadState
}
