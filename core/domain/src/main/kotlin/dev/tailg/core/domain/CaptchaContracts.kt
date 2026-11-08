package dev.tailg.core.domain

/**
 * The reference client receives ticket/randstr from an interactive slider challenge and submits
 * them to app/captcha/slider/verify. It does NOT use them directly as the SMS login code token.
 */
data class HumanChallengeReceipt(val ticket: String, val randstr: String) {
    fun validate(): String? = when {
        ticket.isBlank() || randstr.isBlank() -> "请先完成人工滑块验证"
        ticket.length > 8192 || randstr.length > 8192 -> "滑块验证凭据长度无效"
        '\r' in ticket || '\n' in ticket || '\r' in randstr || '\n' in randstr -> "滑块验证凭据格式无效"
        else -> null
    }

    /** Matches the original Repository.sliderVerify request JSON keys. */
    fun requestBody(): Map<String, String> {
        require(validate() == null) { "Invalid human challenge receipt" }
        return mapOf("ticket" to ticket, "randstr" to randstr)
    }

    override fun toString(): String = "HumanChallengeReceipt([REDACTED])"
}

sealed interface CaptchaDecision {
    /** This token is ephemeral: never persist, log, or display it. */
    data class Accepted(val passToken: String) : CaptchaDecision {
        override fun toString(): String = "CaptchaDecision.Accepted([REDACTED])"
    }
    data class Rejected(val message: String) : CaptchaDecision
}

/**
 * APK SliderVerifyBean has passed, captchaPassToken, captchaPassTokenExpireIn, captchaCode,
 * captchaMsg. A missing expires-in field is acceptable only for immediate one-shot use;
 * an explicit nonpositive lifetime or explicit failed verification is always rejected.
 */
object CaptchaVerificationPolicy {
    fun inspect(passed: Boolean?, token: String?, expiresInSeconds: Int?): CaptchaDecision {
        if (passed == false) return CaptchaDecision.Rejected("滑块验证未通过，请重新完成人工验证")
        if (expiresInSeconds != null && expiresInSeconds <= 0) {
            return CaptchaDecision.Rejected("滑块验证令牌已失效")
        }
        val value = token?.takeIf { it.isNotBlank() && it.length <= 8192 && '\r' !in it && '\n' !in it }
            ?: return CaptchaDecision.Rejected("滑块验证结果没有有效的短信请求令牌")
        return CaptchaDecision.Accepted(value)
    }
}

/** Only rotate a stored session on a successful, authenticated response with a valid header. */
object SessionRenewalPolicy {
    fun updatedToken(businessCode: String, authenticatedRequest: Boolean, responseAuthorization: String?): String? {
        if (!authenticatedRequest || businessCode != "0") return null
        return responseAuthorization?.takeIf { '\n' !in it && '\r' !in it }?.trim()?.takeIf {
            it.isNotEmpty() && it.length <= 8192
        }
    }
}
