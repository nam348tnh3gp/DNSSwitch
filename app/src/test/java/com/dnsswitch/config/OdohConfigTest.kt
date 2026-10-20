package com.dnsswitch.config

import org.junit.Assert.assertEquals
import org.junit.Test

class OdohConfigTest {

    private fun cfg(target: String, proxy: String) = OdohConfig(true, target, proxy)

    @Test
    fun defaultProxyUrlIsKeptAsIs() {
        val c = OdohConfig.DEFAULT
        assertEquals(OdohConfig.DEFAULT_PROXY_URL, c.effectiveProxyUrl)
    }

    @Test
    fun bareProxyGetsTargetParams() {
        val c = cfg("https://odoh.cloudflare-dns.com/", "https://odoh.example.com/proxy")
        assertEquals(
            "https://odoh.example.com/proxy?targethost=odoh.cloudflare-dns.com&targetpath=/dns-query",
            c.effectiveProxyUrl
        )
    }

    @Test
    fun customTargetPathIsUsed() {
        val c = cfg("https://t.example.org/odoh/dns", "https://p.example.net/relay")
        assertEquals(
            "https://p.example.net/relay?targethost=t.example.org&targetpath=/odoh/dns",
            c.effectiveProxyUrl
        )
    }

    @Test
    fun proxyWithOtherQueryParamsGetsAmpersand() {
        val c = cfg("https://odoh.cloudflare-dns.com/", "https://p.example.net/relay?key=1")
        assertEquals(
            "https://p.example.net/relay?key=1&targethost=odoh.cloudflare-dns.com&targetpath=/dns-query",
            c.effectiveProxyUrl
        )
    }

    @Test
    fun emptyProxyStaysEmpty() {
        assertEquals("", cfg("https://odoh.cloudflare-dns.com/", "").effectiveProxyUrl)
    }
}