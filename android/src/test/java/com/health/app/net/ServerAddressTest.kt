package com.health.app.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Signing in over plain http warns only when the server may be out on the internet. */
class ServerAddressTest {
    @Test
    fun `plain http to a public address warns`() {
        listOf(
            "http://health.example.com",
            "http://health.example.com:8080/",
            "http://203.0.113.7",
            "http://8.8.8.8:80",
            "http://172.32.0.1",
            "http://192.169.1.1",
            "http://100.128.0.1",
            "http://[2001:db8::1]",
            "http://local.example.com",
            "http://lan.example.org",
        ).forEach { assertTrue(it, ServerAddress.unencrypted(it)) }
    }

    @Test
    fun `https never warns`() {
        listOf("https://health.example.com", "https://203.0.113.7:8443").forEach { assertFalse(it, ServerAddress.unencrypted(it)) }
    }

    @Test
    fun `plain http on the home network doesn't warn`() {
        listOf(
            // Private and local IP addresses.
            "http://10.0.0.5:8080", "http://172.16.4.2", "http://172.31.255.254", "http://192.168.1.20:8080",
            "http://127.0.0.1:8080", "http://169.254.10.10", "http://100.101.102.103",
            "http://[::1]:8080", "http://[fd12:3456:789a::1]", "http://[fc00::5]", "http://[fe80::1]",
            // Names only a home network knows.
            "http://localhost:8080", "http://health.local", "http://nas.lan", "http://server.home",
            "http://health.internal", "http://health.home.arpa:8080", "http://nas", "http://homeassistant:8123",
        ).forEach { assertFalse(it, ServerAddress.unencrypted(it)) }
    }

    @Test
    fun `an address that isn't one doesn't warn`() {
        assertFalse(ServerAddress.unencrypted(""))
        assertFalse(ServerAddress.unencrypted("not a server"))
    }
}
