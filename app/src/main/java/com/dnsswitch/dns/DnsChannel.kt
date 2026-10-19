package com.dnsswitch.dns

import java.net.DatagramSocket
import java.net.Socket

/** DNS 通道类型 */
enum class ChannelType(val id: String, val label: String) {
    TRADITIONAL("traditional", "传统 DNS"),
    DOH("doh", "DoH"),
    DOT("dot", "DoT"),
    ODOH("odoh", "ODoH")
}

fun interface SocketProtector {
    fun protect(socket: Socket): Boolean
}

fun interface DatagramProtector {
    fun protect(socket: DatagramSocket): Boolean
}

val NO_OP_SOCKET_PROTECTOR = SocketProtector { false }
val NO_OP_DATAGRAM_PROTECTOR = DatagramProtector { false }

interface DnsChannel {
    val type: ChannelType
    val defaultTimeoutMs: Long
    fun query(query: ByteArray, timeoutMs: Long = defaultTimeoutMs): ByteArray?
}