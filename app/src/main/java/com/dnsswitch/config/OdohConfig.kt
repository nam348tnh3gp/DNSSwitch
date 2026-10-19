package com.dnsswitch.config

import org.json.JSONObject

/**
 * ODoH 配置（RFC 9230）。
 *
 * - [targetUrl]: GET `/.well-known/odohconfigs` 获取 public key
 * - [proxyUrl]:  POST encrypted query
 * - 两者必须是 HTTPS（TLS 由 OkHttp 处理 → 继承 system trust store）
 * - 若 targetUrl 为空，则使用 proxyUrl 的 origin + /.well-known/odohconfigs
 */
data class OdohConfig(
    val enabled: Boolean,
    val targetUrl: String,
    val proxyUrl: String
) {
    /** 实际用于 fetch config 的 URL */
    val effectiveTargetUrl: String
        get() {
            if (targetUrl.isNotEmpty()) return targetUrl.trimEnd('/')
            if (proxyUrl.isEmpty()) return ""
            return runCatching {
                val u = java.net.URL(proxyUrl)
                "${u.protocol}://${u.authority}"
            }.getOrDefault("")
        }

    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("targetUrl", targetUrl)
        .put("proxyUrl", proxyUrl)

    companion object {
        val DEFAULT = OdohConfig(false, "", "")

        fun parse(o: JSONObject): OdohConfig? {
            val enabled = o.optBoolean("enabled", false)
            val targetUrl = o.optString("targetUrl").trim()
            val proxyUrl = o.optString("proxyUrl").trim()
            if (enabled) {
                if (proxyUrl.isEmpty() || !Validators.isHttpsUrl(proxyUrl)) return null
                if (targetUrl.isNotEmpty() && !Validators.isHttpsUrl(targetUrl)) return null
            }
            return OdohConfig(enabled, targetUrl, proxyUrl)
        }
    }
}