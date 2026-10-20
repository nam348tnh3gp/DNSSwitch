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

    /** Host[:port] of the Target, sent to the Proxy as the `targethost` parameter. */
    val targetHost: String
        get() = runCatching { java.net.URL(effectiveTargetUrl).authority }.getOrDefault("")

    /** Path of the Target's DoH endpoint, sent to the Proxy as `targetpath` (defaults to /dns-query). */
    val targetPath: String
        get() {
            val path = runCatching { java.net.URL(targetUrl).path }.getOrDefault("")
            return if (path.isEmpty() || path == "/") "/dns-query" else path
        }

    /**
     * Proxy URL actually POSTed to. RFC 9230 proxies need `targethost` / `targetpath` query
     * parameters; if the user entered a bare proxy URL (e.g. https://odoh.example.com/proxy),
     * they are appended from the Target settings instead of silently sending a request the
     * proxy cannot route.
     */
    val effectiveProxyUrl: String
        get() {
            val raw = proxyUrl.trim()
            if (raw.isEmpty()) return ""
            // A bare origin (https://odoh.example.com) has no ODoH endpoint: the reference
            // RFC 9230 proxy implementation serves on /proxy, so use that path.
            val u = runCatching { java.net.URL(raw) }.getOrNull()
            val p = if (u != null && (u.path.isEmpty() || u.path == "/")) {
                "${u.protocol}://${u.authority}/proxy" + (u.query?.let { "?$it" } ?: "")
            } else {
                raw
            }
            // Direct mode: the "proxy" is the Target's own ODoH endpoint (same host and path).
            // POST the message there as-is, without relay-only targethost/targetpath parameters.
            // Note: no separate proxy means the Target sees the client IP (no ODoH privacy split).
            val pu = runCatching { java.net.URL(p) }.getOrNull()
            if (pu != null && pu.query == null &&
                pu.authority.equals(targetHost, ignoreCase = true) && pu.path == targetPath
            ) return p
            val query = p.substringAfter('?', "")
            val hasTargetHost = query.split('&').any {
                it.substringBefore('=').equals("targethost", ignoreCase = true)
            }
            if (hasTargetHost) return p
            val host = targetHost
            if (host.isEmpty()) return p
            val sep = if (p.contains('?')) "&" else "?"
            return "$p${sep}targethost=$host&targetpath=$targetPath"
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
            // User-supplied proxy URLs are kept exactly as entered (including the Target's own
            // /dns-query endpoint, which is used in direct mode).
            val proxyUrl = savedProxy
            if (enabled) {
                if (proxyUrl.isEmpty() || !Validators.isHttpsUrl(proxyUrl)) return null
                if (targetUrl.isNotEmpty() && !Validators.isHttpsUrl(targetUrl)) return null
            }
            return OdohConfig(enabled, targetUrl, proxyUrl)
        }
    }
}