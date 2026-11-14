package com.health.server.util

import okhttp3.Dns
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Where the server will connect on someone's behalf (webhooks, Mealie, browser push). A name is
 * looked up once, each address is checked, and only the addresses that passed are connected to,
 * so the name can't point somewhere else between the check and the call (DNS rebinding).
 *
 * Never: "any address", multicast, or a cloud's metadata service. The server itself (loopback)
 * only where [loopback] allows it (tests). Private networks (home LANs, the container's host, a
 * VPN) only where [private] allows it: the admin's choice for webhooks and Mealie; never for push.
 */
class OutboundDns(private val private: () -> Boolean, private val loopback: () -> Boolean = { false }) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = Dns.SYSTEM.lookup(hostname)
        val allowed = all.filter { refusal(it, private(), loopback()) == null }
        if (allowed.isEmpty()) throw Refused(all.firstNotNullOfOrNull { refusal(it, private(), loopback()) } ?: "That address isn't allowed")
        return allowed
    }

    /** A name whose every address was refused, and why, in words for the person. */
    class Refused(message: String) : UnknownHostException(message)

    companion object {
        private const val PRIVATE = "This server doesn't connect to private addresses (an admin can allow it)"

        /** Why the server won't connect to [a], or null if it will. */
        fun refusal(a: InetAddress, private: Boolean, loopback: Boolean = false): String? = when {
            a.isAnyLocalAddress || a.isMulticastAddress || metadata(a) -> "That address isn't allowed"
            // Tests stand in for a home network with this machine, so it follows the same choice there.
            a.isLoopbackAddress -> if (!loopback) "This server doesn't call itself" else if (private) null else PRIVATE
            isPrivate(a) -> if (private) null else PRIVATE
            else -> null
        }

        /** Cloud metadata services (AWS, GCP, Azure, Oracle; Alibaba; AWS over IPv6). */
        fun metadata(a: InetAddress): Boolean {
            val b = a.address
            return when (a) {
                is Inet4Address -> (b[0].u() == 169 && b[1].u() == 254 && b[2].u() == 169 && b[3].u() == 254) ||
                    (b[0].u() == 100 && b[1].u() == 100 && b[2].u() == 100 && b[3].u() == 200)
                is Inet6Address -> a.hostAddress.substringBefore('%').let { it == "fd00:ec2:0:0:0:0:0:254" || it == "fd00:ec2::254" }
                else -> false
            }
        }

        /**
         * A home network, the container's host, a VPN: loopback, site- and link-local, unique local
         * IPv6 (fc00::/7), carrier-grade NAT (100.64.0.0/10, e.g. Tailscale), and the IPv6 forms that
         * carry an IPv4 address inside (NAT64 64:ff9b::/96, 6to4 2002::/16).
         */
        fun isPrivate(a: InetAddress): Boolean {
            if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress) return true
            val b = a.address
            if (a is Inet6Address) {
                val first = b[0].u()
                return (first and 0xFE) == 0xFC ||
                    (first == 0x20 && b[1].u() == 0x02) ||
                    (first == 0x00 && b[1].u() == 0x64 && b[2].u() == 0xFF && b[3].u() == 0x9B)
            }
            return b[0].u() == 100 && (b[1].u() and 0xC0) == 64
        }

        private fun Byte.u() = toInt() and 0xFF
    }
}
