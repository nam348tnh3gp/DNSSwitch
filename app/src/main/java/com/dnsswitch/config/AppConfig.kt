package com.dnsswitch.config

import org.json.JSONObject

/** 分流策略 */
enum class Strategy(val id: String, val label: String) {
    ALL_TRADITIONAL("all_traditional", "全部走传统 DNS"),
    ALL_DOH("all_doh", "全部走 DoH"),
    ALL_DOT("all_dot", "全部走 DoT"),
    ALL_ODOH("all_odoh", "全部走 ODoH"),
    SMART_FAILOVER("smart_failover", "智能分流（故障转移）"),
    PARALLEL_RACE("parallel_race", "并行竞速");

    companion object {
        fun fromId(id: String?): Strategy = entries.firstOrNull { it.id == id } ?: SMART_FAILOVER
    }
}

data class TraditionalConfig(
    val enabled: Boolean,
    val server: String,
    val serverV6: String,
    val port: Int,
    val useTcp: Boolean
) {
    val hasV4: Boolean get() = server.isNotEmpty()
    val hasV6: Boolean get() = serverV6.isNotEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("server", server)
        .put("serverV6", serverV6)
        .put("port", port)
        .put("tcp", useTcp)

    companion object {
        val DEFAULT = TraditionalConfig(true, "223.5.5.5", "", 53, false)

        fun parse(o: JSONObject): TraditionalConfig? {
            val server = o.optString("server").trim()
            val serverV6 = o.optString("serverV6").trim()
            val port = o.optInt("port", 53)
            val enabled = o.optBoolean("enabled", true)
            if (enabled) {
                if (server.isNotEmpty() && !Validators.isIpv4(server)) return null
                if (serverV6.isNotEmpty() && !Validators.isIpv6(serverV6)) return null
                if (server.isEmpty() && serverV6.isEmpty()) return null
            }
            if (!Validators.isPort(port)) return null
            return TraditionalConfig(enabled, server, serverV6, port, o.optBoolean("tcp", false))
        }
    }
}

data class DohConfig(val enabled: Boolean, val url: String) {
    fun toJson(): JSONObject = JSONObject().put("enabled", enabled).put("url", url)

    companion object {
        val DEFAULT = DohConfig(true, "https://dns.alidns.com/dns-query")

        fun parse(o: JSONObject): DohConfig? {
            val url = o.optString("url").trim()
            val enabled = o.optBoolean("enabled", true)
            if (enabled && !Validators.isHttpUrl(url)) return null
            return DohConfig(enabled, url)
        }
    }
}

data class DotConfig(
    val enabled: Boolean,
    val server: String,
    val serverV6: String,
    val port: Int
) {
    val hasV6: Boolean get() = serverV6.isNotEmpty()

    fun toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("server", server)
        .put("serverV6", serverV6)
        .put("port", port)

    companion object {
        val DEFAULT = DotConfig(false, "dns.alidns.com", "", 853)

        fun parse(o: JSONObject): DotConfig? {
            val server = o.optString("server").trim()
            val serverV6 = o.optString("serverV6").trim()
            val port = o.optInt("port", 853)
            val enabled = o.optBoolean("enabled", false)
            if (enabled) {
                if (server.isEmpty()) return null
                if (!Validators.isHostOrIp(server)) return null
                if (serverV6.isNotEmpty() && !Validators.isIpv6(serverV6)) return null
            }
            if (!Validators.isPort(port)) return null
            return DotConfig(enabled, server, serverV6, port)
        }
    }
}

data class AppConfig(
    val traditional: TraditionalConfig,
    val doh: DohConfig,
    val dot: DotConfig,
    val odoh: OdohConfig,
    val strategy: Strategy
) {
    fun toJson(): JSONObject = JSONObject()
        .put("traditional", traditional.toJson())
        .put("doh", doh.toJson())
        .put("dot", dot.toJson())
        .put("odoh", odoh.toJson())
        .put("strategy", strategy.id)

    companion object {
        val DEFAULT = AppConfig(
            TraditionalConfig.DEFAULT,
            DohConfig.DEFAULT,
            DotConfig.DEFAULT,
            OdohConfig.DEFAULT,
            Strategy.SMART_FAILOVER
        )

        fun parse(o: JSONObject): AppConfig? {
            val traditional = TraditionalConfig.parse(o.optJSONObject("traditional") ?: JSONObject())
                ?: return null
            val doh = DohConfig.parse(o.optJSONObject("doh") ?: JSONObject()) ?: return null
            val dot = DotConfig.parse(o.optJSONObject("dot") ?: JSONObject()) ?: return null
            val odoh = OdohConfig.parse(o.optJSONObject("odoh") ?: JSONObject()) ?: return null
            return AppConfig(traditional, doh, dot, odoh, Strategy.fromId(o.optString("strategy")))
        }
    }
}

/** 配置校验 */
object Validators {

    fun isIpv4(s: String): Boolean {
        if (s.isEmpty()) return false
        val parts = s.split('.')
        if (parts.size != 4) return false
        return parts.all { p ->
            p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && p.toInt() <= 255
        }
    }

    fun isIpv6(s: String): Boolean {
        if (s.isEmpty() || s.length > 45) return false
        if (!s.contains(':')) return false
        val allowed: (Char) -> Boolean = {
            it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.'
        }
        if (!s.all(allowed)) return false
        val group: (String) -> Boolean = { g ->
            g.isNotEmpty() && g.length <= 4 && g.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        }
        fun splitTail(parts: List<String>): Pair<List<String>, Int>? {
            if (parts.isEmpty()) return parts to 0
            val last = parts.last()
            if (!last.contains('.')) return parts to 0
            if (!isIpv4(last)) return null
            return parts.dropLast(1) to 2
        }
        val idx = s.indexOf("::")
        if (idx >= 0) {
            if (s.indexOf("::", idx + 1) >= 0) return false
            val left = if (idx == 0) emptyList() else s.substring(0, idx).split(':')
            val rightRaw = if (idx + 2 == s.length) emptyList() else s.substring(idx + 2).split(':')
            val (right, tailGroups) = splitTail(rightRaw) ?: return false
            if (left.size + right.size + tailGroups > 7) return false
            return (left + right).all(group)
        }
        val (parts, tailGroups) = splitTail(s.split(':')) ?: return false
        if (parts.size + tailGroups != 8) return false
        return parts.all(group)
    }

    fun isIpAddress(s: String): Boolean =
        if (s.contains(':')) isIpv6(s) else isIpv4(s)

    fun isHostname(s: String): Boolean {
        if (s.isEmpty() || s.length > 253) return false
        val labels = s.split('.')
        if (labels.size < 2) return false
        return labels.all { lbl ->
            lbl.isNotEmpty() && lbl.length <= 63 &&
                lbl.all { it.isLetterOrDigit() || it == '-' } &&
                !lbl.startsWith("-") && !lbl.endsWith("-")
        }
    }

    fun isHostOrIp(s: String): Boolean = isIpAddress(s) || isHostname(s)

    fun isPort(p: Int): Boolean = p in 1..65535

    /** Secure DoH endpoints must use HTTPS; reject cleartext HTTP URLs. */
    fun isHttpUrl(s: String): Boolean =
        s.startsWith("https://", ignoreCase = true)

    /** ODoH bắt buộc HTTPS */
    fun isHttpsUrl(s: String): Boolean =
        s.startsWith("https://") && s.length > 8
}