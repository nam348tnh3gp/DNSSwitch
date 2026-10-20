package com.dnsswitch.dns

import com.dnsswitch.config.OdohConfig
import odohclient.Odohclient
import odohclient.QueryContext
import odohclient.Session
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * ODoH channel (RFC 9230).
 * HTTPS is handled by OkHttp; HPKE and ODoH wire format by the Go mobile bridge.
 */
class OdohChannel(
    private val cfg: OdohConfig,
    private val client: OkHttpClient
) : DnsChannel {

    override val type = ChannelType.ODOH
    override val defaultTimeoutMs = 4000L

    private val lock = ReentrantLock()
    private var session: Session? = null
    private var sessionExpiresAt = 0L

    override fun query(query: ByteArray, timeoutMs: Long): ByteArray? {
        val requestClient = client.newBuilder()
            .callTimeout(timeoutMs.coerceIn(500, 10000), TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        // A Target may rotate its ODoH key while a client still has cached config.
        // On key mismatch (401) or a rejected request (400), refresh config once and retry.
        for (attempt in 0..1) {
            val sess = ensureSession(requestClient, forceRefresh = attempt == 1) ?: return null
            val qctx: QueryContext = try {
                sess.encryptQuery(query)
            } catch (_: Exception) {
                return null
            }
            val messageBytes = qctx.message()
            if (messageBytes.isEmpty()) return null

            val req = Request.Builder()
                .url(cfg.proxyUrl)
                .post(messageBytes.toRequestBody(ODOH_MEDIA))
                .header("Content-Type", ODOH_MEDIA_VALUE)
                .header("Accept", ODOH_MEDIA_VALUE)
                .build()

            val response = try {
                requestClient.newCall(req).execute().use { resp ->
                    if (attempt == 0 && (resp.code == 400 || resp.code == 401)) {
                        null // force one fresh Target config fetch below
                    } else {
                        if (!resp.isSuccessful) return null
                        val contentType = resp.header("Content-Type")
                            ?.substringBefore(';')
                            ?.trim()
                        if (!ODOH_MEDIA_VALUE.equals(contentType, ignoreCase = true)) return null
                        resp.body?.bytes() ?: return null
                    }
                }
            } catch (_: Exception) {
                return null
            }

            if (response == null) continue
            return try {
                qctx.decryptResponse(response).takeIf { isMatchingDnsResponse(query, it) }
            } catch (_: Exception) {
                null
            }
        }
        return null
    }

    private fun isMatchingDnsResponse(query: ByteArray, response: ByteArray): Boolean =
        query.size >= 12 && response.size >= 12 &&
            query[0] == response[0] && query[1] == response[1] &&
            (response[2].toInt() and 0x80) != 0

    private fun ensureSession(requestClient: OkHttpClient, forceRefresh: Boolean = false): Session? {
        val now = System.currentTimeMillis()
        lock.withLock {
            if (forceRefresh) {
                session = null
                sessionExpiresAt = 0L
            }
            if (session != null && now < sessionExpiresAt) return session
            val target = cfg.effectiveTargetUrl
            if (target.isEmpty()) return null
            val req = Request.Builder()
                .url("$target/.well-known/odohconfigs")
                .get()
                .build()
            val bytes = try {
                requestClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    resp.body?.bytes()
                }
            } catch (_: Exception) {
                return null
            } ?: return null
            val s = try {
                Odohclient.newSession(bytes)
            } catch (_: Exception) {
                return null
            }
            session = s
            sessionExpiresAt = System.currentTimeMillis() + CONFIG_TTL_MS
            return s
        }
    }

    companion object {
        private const val CONFIG_TTL_MS = 5 * 60 * 1000L
        private const val ODOH_MEDIA_VALUE = "application/oblivious-dns-message"
        private val ODOH_MEDIA = ODOH_MEDIA_VALUE.toMediaType()
    }
}
