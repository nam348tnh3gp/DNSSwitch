package com.dnsswitch.config

import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/**
 * ODoH configuration (RFC 9230), modelled on RethinkDNS' ODoH endpoint:
 *
 * - [targetUrl]: the Target's DoH-style endpoint (RethinkDNS calls it `resolver`, e.g.
 *   https://odoh.cloudflare-dns.com/dns-query). Its origin serves /.well-known/odohconfigs.
 * - [proxyUrl]: the Proxy (relay) that receives the encrypted queries. Optional: when empty the
 *   encrypted query goes straight to the Target ("direct" mode), which works but gives up the
 *   two-hop privacy split because the Target then sees the client IP.
 * - [proxyIps]: optional IP literals for the Proxy host. When present they are used instead of
 *   resolving the Proxy host name (RethinkDNS `proxyIps`), so the bootstrap resolver never
 *   learns which relay is in use.
 */
data class OdohConfig(
    val enabled: Boolean,
    val targetUrl: String,
    val proxyUrl: String,
    val proxyIps: List<String> = emptyList()
) {
    /** URL origin used to retrieve the Target's ODoH configuration. */
    val effectiveTargetUrl: String
        get() {
            val candidate = targetUrl.ifBlank { proxyUrl }
            if (candidate.isBlank()) return ""
            return runCatching {
                val u = URL(candidate)
                if (u.protocol != "https") "" else "${u.protocol}://${u.authority}"
            }.getOrDefault("")
        }

    /** Host[:port] of the Target, sent to the Proxy as the `targethost` parameter. */
    val targetHost: String
        get() = runCatching { URL(effectiveTargetUrl).authority }.getOrDefault("")

    /** Path of the Target's DoH endpoint, sent to the Proxy as `targetpath` (defaults to /dns-query). */
    val targetPath: String
        get() {
            val path = runCatching { URL(targetUrl).path }.getOrDefault("")
            return if (path.isEmpty() || path == "/") "/dns-query" else path
        }

    /**
     * URL actually POSTed to.
     *
     * - Empty [proxyUrl]: direct mode, the Target's own ODoH endpoint.
     * - Otherwise the Proxy URL with `targethost` / `targetpath` rebuilt from the Target settings.
     *   Any such parameters typed into the Proxy URL are replaced, so switching the Target can never
     *   leave the Proxy pointing at the previous one (the keys would not match).
     * - A bare Proxy origin gets the reference RFC 9230 proxy path, /proxy.
     * - A Proxy URL equal to the Target endpoint is also direct mode and is posted as is.
     */
    val effectiveProxyUrl: String
        get() {
            val host = targetHost
            val raw = proxyUrl.trim()
            if (raw.isEmpty()) return if (host.isEmpty()) "" else effectiveTargetUrl + targetPath
            val u = runCatching { URL(raw) }.getOrNull() ?: return raw
            val path = if (u.path.isEmpty() || u.path == "/") "/proxy" else u.path
            val base = "${u.protocol}://${u.authority}$path"
            if (host.isNotEmpty() && u.query == null &&
                u.authority.equals(host, ignoreCase = true) && path == targetPath
            ) return base
            val kept = (u.query ?: "").split('&').filter { part ->
                val key = part.substringBefore('=').lowercase()
                part.isNotEmpty() && key != "targethost" && key != "targetpath"
            }
            val params = if (host.isEmpty()) kept else kept + "targethost=$host" + "targetpath=$targetPath"
            return if (params.isEmpty()) base else "$base?${params.joinToString("&")}"
        }

    /** True when queries go straight to the Target (no Proxy in between, so no privacy split). */
    val isDirect: Boolean
        get() {
            if (targetHost.isEmpty()) return false
            return effectiveProxyUrl.equals(effectiveTargetUrl + targetPath, ignoreCase = true)
        }

    /** Host name of [effectiveProxyUrl]; the one that [proxyIps] applies to. */
    val proxyHost: String
        get() = runCatching { URL(effectiveProxyUrl).host }.getOrDefault("")

    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("targetUrl", targetUrl)
        .put("proxyUrl", proxyUrl)
        .put("proxyIps", JSONArray(proxyIps))

    companion object {
        const val DEFAULT_TARGET_URL = "https://odoh.cloudflare-dns.com/"
        const val DEFAULT_PROXY_URL = "https://odoh1.surfdomeinen.nl/proxy"
        val DEFAULT = OdohConfig(false, DEFAULT_TARGET_URL, DEFAULT_PROXY_URL)

        fun parse(o: JSONObject): OdohConfig? {
            val enabled = o.optBoolean("enabled", false)
            val targetUrl = if (o.has("targetUrl")) o.optString("targetUrl").trim() else DEFAULT_TARGET_URL
            val proxyUrl = if (o.has("proxyUrl")) o.optString("proxyUrl").trim() else DEFAULT_PROXY_URL
            val ips = parseIps(o.opt("proxyIps")) ?: return if (enabled) null else {
                OdohConfig(false, targetUrl, proxyUrl)
            }
            if (enabled) {
                // Direct mode needs a Target; two-hop mode may derive it from the Proxy origin.
                if (targetUrl.isEmpty() && proxyUrl.isEmpty()) return null
                if (targetUrl.isNotEmpty() && !Validators.isOdohTarget(targetUrl)) return null
                if (proxyUrl.isNotEmpty() && !Validators.isOdohProxy(proxyUrl)) return null
            }
            return OdohConfig(enabled, targetUrl, proxyUrl, ips)
        }

        /** Accepts a JSON array or a comma-separated string; null when an entry is not an IP. */
        private fun parseIps(v: Any?): List<String>? = when (v) {
            null, JSONObject.NULL -> emptyList()
            is JSONArray -> Validators.parseIpList(
                (0 until v.length()).joinToString(",") { v.optString(it) }
            )
            else -> Validators.parseIpList(v.toString())
        }
    }
}
