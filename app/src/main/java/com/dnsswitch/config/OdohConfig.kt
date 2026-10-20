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
            val candidate = targetUrl.ifBlank { proxyUrl }
            if (candidate.isEmpty()) return ""
            return runCatching {
                val u = java.net.URL(candidate)
                "${u.protocol}://${u.authority}"
            }.getOrDefault("")
        }

    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("targetUrl", targetUrl)
        .put("proxyUrl", proxyUrl)

    companion object {
        // Public target and community-operated proxy; users may replace the proxy.
        const val DEFAULT_TARGET_URL = "https://odoh.cloudflare-dns.com/"
        const val DEFAULT_PROXY_URL = "https://odoh.cloudflare-dns.com/dns-query"
        val DEFAULT = OdohConfig(false, DEFAULT_TARGET_URL, DEFAULT_PROXY_URL)

        fun parse(o: JSONObject): OdohConfig? {
            val enabled = o.optBoolean("enabled", false)
            // Missing fields in older configs get defaults; explicit empty strings stay empty
            // so users can clear and replace endpoints without the parser restoring defaults.
            val targetUrl = if (o.has("targetUrl")) o.optString("targetUrl").trim() else DEFAULT_TARGET_URL
            val savedProxy = if (o.has("proxyUrl")) o.optString("proxyUrl").trim() else DEFAULT_PROXY_URL
            // Migrate only the previous bundled third-party proxy, not an intentionally blank field.
            val proxyUrl = if (savedProxy == "https://odoh1.surfdomeinen.nl/proxy?targethost=odoh.cloudflare-dns.com&targetpath=/dns-query") DEFAULT_PROXY_URL else savedProxy
            if (enabled) {
                if (proxyUrl.isEmpty() || !Validators.isHttpsUrl(proxyUrl)) return null
                if (targetUrl.isNotEmpty() && !Validators.isHttpsUrl(targetUrl)) return null
            }
            return OdohConfig(enabled, targetUrl, proxyUrl)
        }
    }
}