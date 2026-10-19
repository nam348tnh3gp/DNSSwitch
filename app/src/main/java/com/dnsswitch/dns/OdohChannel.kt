package com.dnsswitch.dns

import com.dnsswitch.config.OdohConfig
import odohclient.Odohclient
import odohclient.QueryContext
import odohclient.Session
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * ODoH 通道（RFC 9230）。
 *
 * TLS 由 OkHttp 完成（继承 Android system trust store）。
 * HPKE 由 Go bridge (odoh-go) 完成 — 纯计算，无网络。
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
        val sess = ensureSession() ?: return null
        val qctx: QueryContext = try {
            sess.encryptQuery(query)
        } catch (e: Exception) {
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

        val respBytes = try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.bytes()
            }
        } catch (e: Exception) {
            return null
        } ?: return null

        return try {
            qctx.decryptResponse(respBytes)
        } catch (e: Exception) {
            null
        }
    }

    private fun ensureSession(): Session? {
        val now = System.currentTimeMillis()
        lock.withLock {
            if (session != null && now < sessionExpiresAt) return session
            val target = cfg.effectiveTargetUrl
            if (target.isEmpty()) return null
            val configUrl = "$target/.well-known/odohconfigs"
            val req = Request.Builder().url(configUrl).get().build()
            val bytes = try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return null
                    resp.body?.bytes()
                }
            } catch (e: Exception) {
                return null
            } ?: return null
            val s = try {
                Odohclient.newSession(bytes)
            } catch (e: Exception) {
                return null
            }
            session = s
            sessionExpiresAt = now + CONFIG_TTL_MS
            return s
        }
    }

    companion object {
        private const val CONFIG_TTL_MS = 5 * 60 * 1000L
        private const val ODOH_MEDIA_VALUE = "application/oblivious-dns-message"
        private val ODOH_MEDIA = ODOH_MEDIA_VALUE.toMediaType()
    }
}