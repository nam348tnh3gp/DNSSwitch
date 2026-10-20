package com.dnsswitch.config

import org.json.JSONObject

/**
 * ODoH configuration (RFC 9230).
 * targetUrl serves /.well-known/odohconfigs; proxyUrl receives encrypted queries.
 */
data class OdohConfig(
    val enabled: Boolean,
    val targetUrl: String,
    val proxyUrl: String
) {
    /** URL origin used to retrieve the Target's ODoH configuration. */
    val effectiveTargetUrl: String
        get() {
            val candidate = targetUrl.ifBlank { proxyUrl }
            if (candidate.isBlank()) return ""
            return runCatching {
                val u = java.net.URL(candidate)
                if (u.protocol != "https") "" else "${u.protocol}://${u.authority}"
            }.getOrDefault("")
        }

    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("targetUrl", targetUrl)
        .put("proxyUrl", proxyUrl)

    companion object {
        const val DEFAULT_TARGET_URL = "https://odoh.cloudflare-dns.com/"
        const val DEFAULT_PROXY_URL = "https://odoh1.surfdomeinen.nl/proxy?targethost=odoh.cloudflare-dns.com&targetpath=/dns-query"
        val DEFAULT = OdohConfig(false, DEFAULT_TARGET_URL, DEFAULT_PROXY_URL)

        fun parse(o: JSONObject): OdohConfig? {
            val enabled = o.optBoolean("enabled", false)
            val targetUrl = if (o.has("targetUrl")) o.optString("targetUrl").trim() else DEFAULT_TARGET_URL
            val savedProxy = if (o.has("proxyUrl")) o.optString("proxyUrl").trim() else DEFAULT_PROXY_URL
            // Migrate the invalid value previously used as a proxy (it is the target's
            // DoH path, not an ODoH proxy endpoint), and preserve the earlier compatible proxy.
            // Keep other user-supplied custom proxy URLs unchanged.
            val proxyUrl = if (
                savedProxy == "https://odoh.cloudflare-dns.com/dns-query" ||
                savedProxy == "https://odoh1.surfdomeinen.nl/proxy?targethost=odoh.cloudflare-dns.com&targetpath=/dns-query"
            ) {
                DEFAULT_PROXY_URL
            } else {
                savedProxy
            }
            if (enabled) {
                if (proxyUrl.isEmpty() || !Validators.isHttpsUrl(proxyUrl)) return null
                if (targetUrl.isNotEmpty() && !Validators.isHttpsUrl(targetUrl)) return null
            }
            return OdohConfig(enabled, targetUrl, proxyUrl)
        }
    }
}
