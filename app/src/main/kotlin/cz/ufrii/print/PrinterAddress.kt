package cz.ufrii.print

import android.content.Context
import android.net.ConnectivityManager
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Picks the address a printer answers on right now: IPv4 first, its IPv6 when IPv4 is silent
 * (e.g. DHCP gave the printer a new IPv4, while the link-local IPv6 derived from the MAC stays).
 * Every network path (print, scan, status) goes through [resolve], so both families behave the same.
 */
object PrinterAddress {

    private const val CONNECT_MS = 1500
    private const val CACHE_MS = 60_000L

    /** printer id → (address that answered, when). */
    private val cache = ConcurrentHashMap<String, Pair<String, Long>>()

    /** [p] with `ip` set to the reachable address. Blocking (TCP connect) — call off the main thread. */
    fun resolve(ctx: Context, p: Printer): Printer {
        val primary = withZone(ctx, p.ip)
        val fallback = p.ipv6?.let { withZone(ctx, it) }?.takeIf { it != primary }
            ?: return p.copy(ip = primary)
        val now = System.currentTimeMillis()
        cache[p.id]?.let { (ip, t) -> if (now - t < CACHE_MS) return p.copy(ip = ip) }
        val ip = when {
            open(primary, p.port) -> primary
            open(fallback, p.port) -> fallback.also { AppLog.i("${p.ip} neodpovídá → IPv6 $it") }
            else -> primary   // neither answers: the caller reports the usual error for the primary address
        }
        cache[p.id] = ip to now
        return p.copy(ip = ip)
    }

    /** Forget the cached choice (after a failed job, so the next one tries IPv4 again). */
    fun invalidate(id: String) { cache.remove(id) }

    fun isIpv6(ip: String) = ':' in ip

    /**
     * A link-local IPv6 (fe80::/10) is only usable with the interface ("fe80::1%wlan0"). The
     * zone is not stored (it differs between phones, see QR sharing), so add the current Wi-Fi's.
     */
    fun withZone(ctx: Context, ip: String): String {
        if (!isIpv6(ip) || '%' in ip || !ip.lowercase().startsWith("fe8")) return ip
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val iface = cm?.activeNetwork?.let { cm.getLinkProperties(it)?.interfaceName } ?: return ip
        return "$ip%$iface"
    }

    /** Address without the zone, for storing and sharing. */
    fun stripZone(ip: String) = ip.substringBefore('%')

    private fun open(ip: String, port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(ip, port), CONNECT_MS) }
    }.isSuccess
}
