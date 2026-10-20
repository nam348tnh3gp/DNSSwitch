package com.dnsswitch.dns

import com.dnsswitch.config.DohConfig
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * DoH 通道（RFC 8484）。
 * 优先 POST（application/dns-message），失败回退 GET（?dns=base64url），兼容只支持 GET 的服务器。
 * URL 可自定义，允许带端口，如 https://dns.alidns.com:8443/dns-query。
 */
class DohChannel(
    private val cfg: DohConfig,
    private val client: OkHttpClient
) : DnsChannel {

    override val type = ChannelType.DOH
    override val defaultTimeoutMs = 2500L

    override fun query(query: ByteArray, timeoutMs: Long): ByteArray? {
        val url = cfg.url.trim()
        if (url.isEmpty()) return null
        val boundedClient = client.newBuilder()
            .callTimeout(timeoutMs.coerceIn(500, 10000), TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        post(query, url, boundedClient)?.let { return it }
        return get(query, url, boundedClient)
    }

    private fun post(query: ByteArray, url: String, requestClient: OkHttpClient): ByteArray? = try {
        val body: RequestBody = query.toRequestBody(MEDIA_TYPE)
        val req = Request.Builder()
            .url(url)
            .post(body)
            .header("Accept", DNS_MESSAGE)
            .build()
        requestClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            val contentType = body.contentType() ?: return null
            if (!contentType.type.equals("application", ignoreCase = true) ||
                !contentType.subtype.equals("dns-message", ignoreCase = true)) return null
            body.bytes().takeIf { isMatchingDnsResponse(query, it) }
        }
    } catch (e: Exception) {
        null
    }

    private fun get(query: ByteArray, url: String, requestClient: OkHttpClient): ByteArray? = try {
        val b64 = android.util.Base64.encodeToString(
            query,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )
        val sep = if (url.contains('?')) "&" else "?"
        val req = Request.Builder()
            .url(url + sep + "dns=" + b64)
            .header("Accept", DNS_MESSAGE)
            .build()
        requestClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            val contentType = body.contentType() ?: return null
            if (!contentType.type.equals("application", ignoreCase = true) ||
                !contentType.subtype.equals("dns-message", ignoreCase = true)) return null
            body.bytes().takeIf { isMatchingDnsResponse(query, it) }
        }
    } catch (e: Exception) {
        null
    }

    private fun isMatchingDnsResponse(query: ByteArray, response: ByteArray): Boolean =
        query.size >= 12 && response.size >= 12 &&
            query[0] == response[0] && query[1] == response[1] &&
            (response[2].toInt() and 0x80) != 0

    companion object {
        private const val DNS_MESSAGE = "application/dns-message"
        private val MEDIA_TYPE: MediaType = DNS_MESSAGE.toMediaType()
    }
}
