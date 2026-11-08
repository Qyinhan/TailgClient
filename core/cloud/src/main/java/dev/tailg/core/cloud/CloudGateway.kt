package dev.tailg.core.cloud

import dev.tailg.core.domain.CaptchaDecision
import dev.tailg.core.domain.CaptchaVerificationPolicy
import dev.tailg.core.domain.HumanChallengeReceipt
import dev.tailg.core.domain.ListedVehicle
import dev.tailg.core.domain.SessionDecision
import dev.tailg.core.domain.SessionReceiptPolicy
import dev.tailg.core.domain.SessionRenewalPolicy
import dev.tailg.core.domain.SmsLoginInput
import dev.tailg.core.domain.VehicleListMapper
import dev.tailg.core.domain.VehicleListRecord
import dev.tailg.core.domain.VehicleListWireContract
import dev.tailg.core.domain.VehiclePage
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import org.json.JSONArray
import org.json.JSONObject

sealed interface CloudLoginResult {
    /** A token was actually returned by the server; NO device control is implied. */
    data class SessionReady(val userId: String?) : CloudLoginResult
    data class PendingVerification(val message: String) : CloudLoginResult
}

class CloudFailure(val displayMessage: String, val unauthorized: Boolean = false) : Exception(displayMessage)

/**
 * Experimental client for three statically evidenced endpoints. Uses HTTPS system trust store.
 * No hidden API probing, automatic retries, certificate bypass, or background login attempts.
 */
class CloudGateway(private val vault: SessionVault) {
    companion object {
        private const val API_ROOT = "https://www.tailgdd.com/v1/api/"
        private const val MAX_RESPONSE_BYTES = 1024 * 1024
    }

    fun hasSession(): Boolean = vault.read() != null
    fun sessionGeneration(): Long = vault.generation()
    fun logout() = vault.clear()

    /**
     * Two requests in the original order: interactive challenge receipt -> server verification
     * -> ephemeral pass token -> SMS. The user must obtain the receipt through an authorized
     * human-operated challenge; this client does not synthesize, intercept, or persist it.
     */
    fun verifyChallengeAndSendSms(phone: String, challenge: HumanChallengeReceipt) {
        if (!phone.matches(Regex("1[3-9][0-9]{9}"))) throw CloudFailure("手机号格式不正确")
        challenge.validate()?.let { throw CloudFailure(it) }
        val verified = call("app/captcha/slider/verify", challenge.requestBody(), token = null)
        val payload = verified.data as? JSONObject ?: throw CloudFailure("验证码验证结果格式不兼容")
        val expiry = if (payload.isNull("captchaPassTokenExpireIn")) null
            else payload.optInt("captchaPassTokenExpireIn", 0)
        val passed = when (payload.opt("passed")) {
            true, "true" -> true
            false, "false" -> false
            else -> null
        }
        val decision = CaptchaVerificationPolicy.inspect(passed, payload.optString("captchaPassToken", ""), expiry)
        val passToken = when (decision) {
            is CaptchaDecision.Accepted -> decision.passToken
            is CaptchaDecision.Rejected -> throw CloudFailure(decision.message)
        }
        // Token remains in this stack frame only, never persisted or written to logs.
        val query = "phone=${encode(phone)}&captchaPassToken=${encode(passToken)}"
        call("app/sms/loginCode?$query", null, token = null)
    }

    fun login(input: SmsLoginInput, expectedGeneration: Long): CloudLoginResult {
        input.validate()?.let { throw CloudFailure(it) }
        val response = call("app/login", input.requestBody(), token = null)
        val tokenInData = (response.data as? JSONObject)?.optString("token")
        return when (val decision = SessionReceiptPolicy.inspect(response.code, response.authorizationHeader, tokenInData)) {
            is SessionDecision.Accepted -> {
                if (!vault.saveAfterLoginIfCurrent(expectedGeneration, decision.token)) {
                    CloudLoginResult.PendingVerification("该登录请求已取消，未保存过期会话")
                } else {
                    val uid = (response.data as? JSONObject)?.optString("uid")?.takeIf(String::isNotBlank)
                    CloudLoginResult.SessionReady(uid)
                }
            }
            is SessionDecision.Incomplete -> CloudLoginResult.PendingVerification(decision.reason)
        }
    }

    /**
     * Fetches one read-only page, using the original userCarListPage request keys. Page size
     * 10 is confirmed from the Repository's const/16 bytecode. Response shape is not yet
     * dynamically verified; reject unfamiliar envelopes rather than inventing vehicles.
     */
    fun loadVehiclePage(pageIndex: Int): VehiclePage {
        require(pageIndex in 1..50) { "Page out of supported range" }
        val token = vault.read() ?: throw CloudFailure("请先登录", unauthorized = true)
        val pageSize = 10
        val response = try {
            call(
                "app/userCarListPage",
                VehicleListWireContract.pageBody(containShare = true, pageSize = pageSize, pageIndex = pageIndex),
                token,
            )
        } catch (failure: CloudFailure) {
            if (failure.unauthorized) vault.clearIfCurrent(token)
            throw failure
        }
        val items = extractVehicleArray(response.data)
        val meta = response.data as? JSONObject
        val serverHasNext = meta?.valueAsBoolean("hasNext")
        val serverTotal = meta?.valueAsNonnegativeInt("total")
        if (items.length() > pageSize) throw CloudFailure("车辆分页数量异常，请核对接口版本")
        val vehicles = ArrayList<ListedVehicle>(items.length())
        for (i in 0 until items.length()) {
            val record = items.optJSONObject(i) ?: throw CloudFailure("车辆列表包含无法解析的记录")
            val mapped = VehicleListMapper.map(
                VehicleListRecord(
                    carId = record.scalar("carId"),
                    carName = record.scalar("carName"),
                    carNickName = record.scalar("carNickName"),
                    frame = record.scalar("frame"),
                    online = record.scalar("online"),
                    electricQuantity = record.scalar("electricQuantity"),
                ),
            ) ?: throw CloudFailure("车辆记录缺少 carId，已停止加载")
            vehicles += mapped
        }
        return VehiclePage(pageIndex, pageSize, vehicles, serverHasNext, serverTotal)
    }

    private fun extractVehicleArray(data: Any?): JSONArray = when (data) {
        is JSONArray -> data
        is JSONObject -> {
            // Only known common envelope candidates; actual server JSON remains untested.
            val direct = data.optJSONArray("pageData") ?: data.optJSONArray("list")
            val nested = data.optJSONObject("pageData")
            direct ?: nested?.optJSONArray("list")
            ?: throw CloudFailure("分页数据结构尚未适配，未使用占位车辆")
        }
        else -> throw CloudFailure("服务器未返回可解析的车辆分页")
    }

    private data class Envelope(val code: String, val data: Any?, val authorizationHeader: String?)

    private fun call(pathAndQuery: String, body: Map<String, Any>?, token: String?): Envelope {
        val url = URL(API_ROOT + pathAndQuery)
        require(url.protocol == "https" && url.host == "www.tailgdd.com" && url.port == -1)
        val connection = (url.openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12000
            readTimeout = 15000
            instanceFollowRedirects = false
            doOutput = true
            useCaches = false
            setRequestProperty("Accept", "application/json")
            if (body != null) setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("language", "zh_CN")
            if (token != null) setRequestProperty("Authorization", token)
        }
        try {
            // Retrofit @POST without @Body sends an empty body for loginCode.
            val payload = body?.let { JSONObject(it).toString().toByteArray(Charsets.UTF_8) } ?: ByteArray(0)
            require(payload.size <= 65536)
            connection.outputStream.use { it.write(payload) }
            val httpCode = connection.responseCode
            if (httpCode == HttpURLConnection.HTTP_UNAUTHORIZED) throw CloudFailure("登录已失效，请重新登录", true)
            if (httpCode !in 200..299) throw CloudFailure("请求失败（HTTP $httpCode）")
            val responseBytes = connection.inputStream.use { input ->
                val sink = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_RESPONSE_BYTES) throw CloudFailure("服务器响应超过安全大小限制")
                    sink.write(buffer, 0, read)
                }
                sink.toByteArray()
            }
            val json = try { JSONObject(responseBytes.toString(Charsets.UTF_8)) }
                catch (_: Exception) { throw CloudFailure("服务器没有返回有效的 JSON") }
            val code = json.optString("code", "")
            if (code != "0") {
                val message = json.optString("msg", "服务端拒绝请求").take(160)
                throw CloudFailure(message.ifBlank { "服务端拒绝请求" }, code == "401")
            }
            val data = json.opt("data")
            val header = connection.getHeaderField("Authorization")
            // Original response interceptor rotates Authorization after subsequent calls too.
            SessionRenewalPolicy.updatedToken(code, token != null, header)?.let { newToken ->
                if (token != null) vault.rotateIfCurrent(token, newToken)
            }
            return Envelope(code, if (data == JSONObject.NULL) null else data, header)
        } catch (failure: CloudFailure) {
            throw failure
        } catch (_: java.net.SocketTimeoutException) {
            throw CloudFailure("连接超时，请检查网络后手动重试")
        } catch (_: javax.net.ssl.SSLException) {
            throw CloudFailure("TLS 连接验证失败，已拒绝不安全连接")
        } catch (_: java.io.IOException) {
            throw CloudFailure("无法连接台铃服务器，请检查网络")
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.valueAsBoolean(name: String): Boolean? = when (val value = opt(name)) {
        true, "true", "1", 1 -> true
        false, "false", "0", 0 -> false
        else -> null
    }

    private fun JSONObject.valueAsNonnegativeInt(name: String): Int? {
        val value = opt(name)
        val candidate = when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
        return candidate?.takeIf { it >= 0 }
    }

    private fun JSONObject.scalar(name: String): String? =
        when (val raw = opt(name)) {
            null, JSONObject.NULL -> null
            is String, is Number, is Boolean -> raw.toString().take(256)
            else -> null
        }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
}
