package com.health.app.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Whether a server's address sends things unencrypted where they could be read on the way. Plain
 * http is fine to a server on the home network, and the app allows it anywhere (sign-in only says
 * so, calmly), but out on the internet it should be https.
 */
object ServerAddress {
    /** What sign-in says when [unencrypted] is true. */
    const val WARNING = "This address isn't encrypted. Use https:// if your server is reachable from outside your home."

    /** Names only a home network knows: mDNS, and what home routers and private zones use. */
    private val LOCAL_SUFFIXES = listOf(".local", ".lan", ".home", ".internal", ".home.arpa", ".localhost")

    /**
     * Whether [server] (as [ServerApi.normalize] makes it) is plain http to a host that may be out
     * on the internet. Not for a host on the home network: a private or local IP address,
     * localhost, a one-word name, or a name ending .local, .lan, .home, .internal or .home.arpa.
     */
    fun unencrypted(server: String): Boolean {
        val url = server.trim().toHttpUrlOrNull() ?: return false
        return url.scheme == "http" && !isLocal(url.host)
    }

    /** Whether [host] is on the home network (or this phone). */
    fun isLocal(host: String): Boolean {
        val h = host.lowercase().trim().removePrefix("[").removeSuffix("]").trimEnd('.')
        if (h.isEmpty()) return false
        if (h == "localhost" || LOCAL_SUFFIXES.any { h.endsWith(it) }) return true
        ipv4(h)?.let { return privateV4(it) }
        if (':' in h) return privateV6(h)
        // A one-word name ("nas", "homeassistant") is one only the home network can answer.
        return '.' !in h
    }

    private fun ipv4(h: String): IntArray? {
        val parts = h.split('.')
        if (parts.size != 4) return null
        val bytes = parts.map { p -> p.takeIf { it.isNotEmpty() && it.all(Char::isDigit) && it.length <= 3 }?.toInt()?.takeIf { it in 0..255 } ?: return null }
        return bytes.toIntArray()
    }

    /** 10/8, 172.16/12, 192.168/16, loopback, link-local, and 100.64/10 (carrier-grade NAT, which Tailscale uses). */
    private fun privateV4(b: IntArray): Boolean = when {
        b[0] == 10 || b[0] == 127 -> true
        b[0] == 172 && b[1] in 16..31 -> true
        b[0] == 192 && b[1] == 168 -> true
        b[0] == 169 && b[1] == 254 -> true
        b[0] == 100 && b[1] in 64..127 -> true
        else -> false
    }

    /** Loopback, unique local (fc00::/7), link-local (fe80::/10), and IPv4 addresses written as IPv6. */
    private fun privateV6(h: String): Boolean {
        val address = h.substringBefore('%')
        if (address == "::1") return true
        if (address.startsWith("::ffff:")) ipv4(address.removePrefix("::ffff:"))?.let { return privateV4(it) }
        val first = address.substringBefore(':').ifEmpty { return false }.toIntOrNull(16) ?: return false
        return (first and 0xfe00) == 0xfc00 || (first and 0xffc0) == 0xfe80
    }
}
