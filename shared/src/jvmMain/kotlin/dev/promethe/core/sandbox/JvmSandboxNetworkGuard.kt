package dev.promethe.core.sandbox

import dev.promethe.api.SandboxPermissionProfile
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

data class PinnedSandboxTarget(
    val host: String,
    val port: Int,
    val addresses: List<String>,
)

class JvmSandboxNetworkGuard(
    private val resolver: (String) -> Array<InetAddress> = InetAddress::getAllByName,
) {
    fun authorize(
        host: String,
        port: Int,
        profile: SandboxPermissionProfile,
    ): PinnedSandboxTarget {
        val decision = SandboxDomainPolicy.evaluate(host, profile)
        if (!decision.allowed) throw SandboxNetworkDeniedException(decision.reason)
        if (port !in 1..65535) throw SandboxNetworkDeniedException("destination port is invalid")

        val addresses =
            runCatching { resolver(host).toList() }
                .getOrElse { throw SandboxNetworkDeniedException("destination cannot be resolved") }
        if (addresses.isEmpty()) throw SandboxNetworkDeniedException("destination cannot be resolved")
        if (addresses.any(::isNonPublicAddress)) {
            throw SandboxNetworkDeniedException("private, local, or special-use destinations are blocked")
        }
        return PinnedSandboxTarget(
            host = host.lowercase().removeSuffix("."),
            port = port,
            addresses = addresses.map { it.hostAddress.substringBefore('%') }.distinct(),
        )
    }

    private fun isNonPublicAddress(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }

        val bytes = address.address.map { it.toInt() and 0xff }
        return when (address) {
            is Inet4Address -> {
                bytes[0] == 0 ||
                    bytes[0] == 10 ||
                    bytes[0] == 127 ||
                    (bytes[0] == 100 && bytes[1] in 64..127) ||
                    (bytes[0] == 169 && bytes[1] == 254) ||
                    (bytes[0] == 172 && bytes[1] in 16..31) ||
                    (bytes[0] == 192 && bytes[1] == 0 && bytes[2] in 0..2) ||
                    (bytes[0] == 192 && bytes[1] == 168) ||
                    (bytes[0] == 198 && bytes[1] in 18..19) ||
                    (bytes[0] == 198 && bytes[1] == 51 && bytes[2] == 100) ||
                    (bytes[0] == 203 && bytes[1] == 0 && bytes[2] == 113) ||
                    bytes[0] >= 224
            }

            is Inet6Address -> {
                bytes.firstOrNull() in setOf(0xfc, 0xfd) ||
                    address.isLoopbackAddress ||
                    address.isLinkLocalAddress
            }

            else -> {
                true
            }
        }
    }
}

class SandboxNetworkDeniedException(
    message: String,
) : SecurityException(message)
