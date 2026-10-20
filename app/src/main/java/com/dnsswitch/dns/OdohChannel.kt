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
 *
 * TLS is handled by OkHttp; HPKE and ODoH wire format by the Go mobile bridge.
 *
 * Instances MUST be reused across queries (see DnsResolver): the Target's ODoH config
 * is cached here, and a new instance per query would re-download it every time.
 */
class OdohChannel(
    private val cfg: OdohConfig,
    private val client: OkHttpClient
) : DnsChannel {

    override val type = ChannelType.ODOH
    override val defaultTimeoutMs = 3500L

    /** Most recent failure reason (null after a successful query); surfaced in tests and logs. */
    @Volatile
    var lastError: String? = null
        private set

    private val stateLock = Any()
    private val fetchLock = ReentrantLock()
    private var session: Session? = null
    private var sessionFetchedAt = 0L
    private var retryAfter = 0L
    private var lastForcedRefresh = 0L

    /** Result of one encrypt → proxy → decrypt round trip. */
    private sealed class Outcome {
        class Answer(val bytes: ByteArray) : Outcome()
        /** Target did not accept our key (HTTP 401 / empty body): the cached config is stale. */
        class KeyMismatch(val reason: String) : Outcome()
        class Failure(val reason: String) : Outcome()
    }

    override fun query(query: ByteArray, timeoutMs: Long): ByteArray? {
        var sess = ensureSession() ?: return null
        for (attempt in 0..1) {
            when (val r = exchange(sess, query, timeoutMs)) {
                is Outcome.Answer -> {
                    lastError = null
                    return r.bytes
                }
                is Outcome.Failure -> return fail(r.reason)
                is Outcome.KeyMismatch -> {
                    // RFC 9230: the Target answers 401 when the key id is unknown, i.e. the config
                    // was rotated or is stale. Refetch the config and retry once.
                    if (attempt == 1 || !allowForcedRefresh()) {
                        return fail(r.reason + "（已刷新 Target 配置仍被拒绝）")
                    }
                    invalidateSession()
                    sess = ensureSession() ?: return null
                }
            }
        }
        return null
    }

    private fun exchange(sess: Session, query: ByteArray, timeoutMs: Long): Outcome {
        val qctx: QueryContext = try {
            sess.encryptQuery(query)
        } catch (e: Exception) {
            return Outcome.Failure("加密查询失败：" + describe(e))
        }
        val messageBytes = qctx.message()
        if (messageBytes.isEmpty()) return Outcome.Failure("加密后的消息为空")

        val req = try {
            Request.Builder()
                .url(cfg.effectiveProxyUrl)
                .post(messageBytes.toRequestBody(ODOH_MEDIA))
                .header("Content-Type", ODOH_MEDIA_VALUE)
                .header("Accept", ODOH_MEDIA_VALUE)
                .build()
        } catch (e: IllegalArgumentException) {
            return Outcome.Failure("Proxy URL 无效")
        }

        val bounded = client.newBuilder()
            .callTimeout(timeoutMs.coerceIn(500, 10000), TimeUnit.MILLISECONDS)
            .build()
        val respBytes = try {
            bounded.newCall(req).execute().use { resp ->
                if (resp.code == 401) return Outcome.KeyMismatch("Target 拒绝了密钥：" + diag(resp))
                if (!resp.isSuccessful) return Outcome.Failure("Proxy 返回 " + diag(resp))
                resp.body?.bytes()
            }
        } catch (e: Exception) {
            return Outcome.Failure("连接 Proxy 失败：" + describe(e))
        }
        // Some proxies relay the Target's 401 as an empty 200 response.
        if (respBytes == null || respBytes.isEmpty()) {
            return Outcome.KeyMismatch("Proxy 返回空响应（可能是 Target 拒绝了密钥）")
        }

        val answer = try {
            qctx.decryptResponse(respBytes)
        } catch (e: Exception) {
            return Outcome.KeyMismatch("解密响应失败：" + describe(e))
        }
        if (answer == null || answer.size < 12) return Outcome.Failure("解密后的 DNS 报文无效")
        return Outcome.Answer(answer)
    }

    /** Limits forced config refreshes to one per [FORCED_REFRESH_INTERVAL_MS]. */
    private fun allowForcedRefresh(): Boolean = synchronized(stateLock) {
        val now = System.currentTimeMillis()
        if (now - lastForcedRefresh < FORCED_REFRESH_INTERVAL_MS) {
            false
        } else {
            lastForcedRefresh = now
            true
        }
    }

    /**
     * Returns a usable session, fetching the Target config at most once at a time.
     * After a failed fetch, further queries fail fast for [FAILURE_BACKOFF_MS] instead of each
     * blocking on its own timeout (which used to serialise all DNS queries behind the lock).
     * A previously fetched session is kept as a fallback while refresh is failing.
     */
    private fun ensureSession(): Session? {
        synchronized(stateLock) {
            val now = System.currentTimeMillis()
            val s = session
            if (s != null && now < sessionFetchedAt + CONFIG_TTL_MS) return s
            if (now < retryAfter) return s
        }
        fetchLock.withLock {
            synchronized(stateLock) {
                // Another thread may have finished the fetch while we waited for the lock.
                val now = System.currentTimeMillis()
                val s = session
                if (s != null && now < sessionFetchedAt + CONFIG_TTL_MS) return s
                if (now < retryAfter) return s
            }
            val fresh = fetchSession()
            synchronized(stateLock) {
                val now = System.currentTimeMillis()
                if (fresh != null) {
                    session = fresh
                    sessionFetchedAt = now
                    retryAfter = 0L
                } else {
                    retryAfter = now + FAILURE_BACKOFF_MS
                }
                return session
            }
        }
    }

    private fun fetchSession(): Session? {
        val target = cfg.effectiveTargetUrl
        if (target.isEmpty()) {
            report("Target URL 无效（必须是 https:// 开头）")
            return null
        }
        val req = try {
            Request.Builder().url("$target/.well-known/odohconfigs").get().build()
        } catch (e: IllegalArgumentException) {
            report("Target URL 无效")
            return null
        }
        val bounded = client.newBuilder()
            .callTimeout(CONFIG_FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        val bytes = try {
            bounded.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    report("获取 Target 配置失败：" + diag(resp))
                    return null
                }
                resp.body?.bytes()
            }
        } catch (e: Exception) {
            report("获取 Target 配置失败：" + describe(e))
            return null
        }
        if (bytes == null || bytes.isEmpty()) {
            report("Target 配置为空")
            return null
        }
        return try {
            Odohclient.newSession(bytes)
        } catch (e: Exception) {
            report("解析 Target 配置失败：" + describe(e))
            null
        }
    }

    private fun invalidateSession() {
        synchronized(stateLock) {
            session = null
            sessionFetchedAt = 0L
            retryAfter = 0L
        }
    }

    private fun fail(msg: String): ByteArray? {
        report(msg)
        return null
    }

    /** Remember the error and write it to the log page once per distinct message. */
    private fun report(msg: String) {
        val changed = lastError != msg
        lastError = msg
        if (changed) DnsStats.system("ODoH：$msg")
    }

    /** HTTP status plus the headers/body that identify WHO rejected the request (e.g. 401). */
    private fun diag(resp: okhttp3.Response): String {
        val hdrs = listOf("Location", "WWW-Authenticate", "Proxy-Status", "Server", "CF-Ray")
            .mapNotNull { n -> resp.header(n)?.let { "$n=$it" } }
        val ct = resp.body?.contentType()
        val textual = ct != null && (ct.type == "text" || ct.subtype.contains("json"))
        val body = if (textual) {
            runCatching { resp.peekBody(200).string().replace(Regex("\\s+"), " ").trim() }.getOrDefault("")
        } else ""
        return "HTTP ${resp.code}" +
            (if (hdrs.isNotEmpty()) " [" + hdrs.joinToString("; ") + "]" else "") +
            (if (body.isNotEmpty()) " $body" else "")
    }

    private fun describe(e: Exception): String =
        e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")

    companion object {
        private const val CONFIG_TTL_MS = 5 * 60 * 1000L
        private const val FAILURE_BACKOFF_MS = 10_000L
        private const val FORCED_REFRESH_INTERVAL_MS = 10_000L
        private const val CONFIG_FETCH_TIMEOUT_MS = 4000L
        private const val ODOH_MEDIA_VALUE = "application/oblivious-dns-message"
        private val ODOH_MEDIA = ODOH_MEDIA_VALUE.toMediaType()
    }
}