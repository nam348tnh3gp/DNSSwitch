package com.dnsswitch.config

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OdohConfigTest {

    private fun cfg(target: String, proxy: String) = OdohConfig(true, target, proxy)

    @Test
    fun defaultProxyGetsTargetParams() {
        val c = OdohConfig.DEFAULT
        assertEquals(
            "https://odoh1.surfdomeinen.nl/proxy?targethost=odoh.cloudflare-dns.com&targetpath=/dns-query",
            c.effectiveProxyUrl
        )
        assertFalse(c.isDirect)
    }

    @Test
    fun legacyProxyWithTargetParamsIsRebuiltFromTarget() {
        // Old configs stored targethost/targetpath inside the proxy URL; switching the target
        // must not leave them pointing at the previous target.
        val c = cfg(
            "https://odoh.crypto.sx/dns-query",
            "https://p.example.net/proxy?targethost=odoh.cloudflare-dns.com&targetpath=/dns-query"
        )
        assertEquals(
            "https://p.example.net/proxy?targethost=odoh.crypto.sx&targetpath=/dns-query",
            c.effectiveProxyUrl
        )
    }

    @Test
    fun emptyProxyMeansDirectMode() {
        val c = cfg("https://odoh.cloudflare-dns.com/dns-query", "")
        assertEquals("https://odoh.cloudflare-dns.com/dns-query", c.effectiveProxyUrl)
        assertTrue(c.isDirect)
    }

    @Test
    fun proxyEqualToTargetEndpointIsDirect() {
        val c = cfg("https://odoh.cloudflare-dns.com/", "https://odoh.cloudflare-dns.com/dns-query")
        assertEquals("https://odoh.cloudflare-dns.com/dns-query", c.effectiveProxyUrl)
        assertTrue(c.isDirect)
    }

    @Test
    fun proxyHostIsExposedForIpPinning() {
        assertEquals("odoh1.surfdomeinen.nl", OdohConfig.DEFAULT.proxyHost)
    }

    @Test
    fun parseAcceptsEmptyProxyWhenEnabled() {
        val o = JSONObject().put("enabled", true)
            .put("targetUrl", "https://odoh.cloudflare-dns.com/dns-query").put("proxyUrl", "")
        assertNotNull(OdohConfig.parse(o))
    }

    @Test
    fun parseRejectsBadTargets() {
        listOf(
            "http://odoh.example.com/dns-query",
            "https://odoh.example.com/dns-query?x=1",
            "https://user@odoh.example.com/dns-query",
            "https://odoh.example.com/dns query",
            "https://odoh.example.com/dns-query#frag"
        ).forEach { t ->
            val o = JSONObject().put("enabled", true).put("targetUrl", t).put("proxyUrl", "")
            assertNull(t, OdohConfig.parse(o))
        }
    }

    @Test
    fun parseProxyIpsFromArrayAndString() {
        val base = { JSONObject().put("enabled", true) }
        val a = OdohConfig.parse(base().put("proxyIps", JSONArray(listOf("1.2.3.4", "2001:db8::1"))))
        assertEquals(listOf("1.2.3.4", "2001:db8::1"), a!!.proxyIps)
        val b = OdohConfig.parse(base().put("proxyIps", "1.2.3.4, 5.6.7.8"))
        assertEquals(listOf("1.2.3.4", "5.6.7.8"), b!!.proxyIps)
        assertNull(OdohConfig.parse(base().put("proxyIps", "not-an-ip")))
    }

    @Test
    fun jsonRoundTripKeepsProxyIps() {
        val c = OdohConfig(true, "https://t.example.org/dns-query", "https://p.example.net/proxy", listOf("9.9.9.9"))
        assertEquals(c, OdohConfig.parse(c.toJson()))
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

}