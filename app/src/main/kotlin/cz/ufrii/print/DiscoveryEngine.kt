package cz.ufrii.print

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue

private const val TAG = "CanonPrint"

/**
 * Runs three concurrent discovery prongs (mDNS/NSD, TCP subnet scan, Canon BJNP UDP)
 * and reports each newly-found printer via [onFound] on the main thread.
 *
 * Usage:
 *   val engine = DiscoveryEngine(ctx)
 *   engine.start { printer -> /* main thread */ }
 *   // later:
 *   engine.stop()
 *
 * [start] is safe to call once per session. [stop] tears everything down cleanly.
 */
class DiscoveryEngine(private val ctx: Context) {

    // -------------------------------------------------------------------------
    // State shared between prongs
    // -------------------------------------------------------------------------

    /** Set of "ip:port" strings already reported — prevents duplicate callbacks. */
    private val reported = ConcurrentHashMap<String, Boolean>()

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var stopped = false

    // -------------------------------------------------------------------------
    // Prong A — mDNS (NSD)
    // -------------------------------------------------------------------------

    private val nsdManager: NsdManager by lazy {
        ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    /** Multicast lock: Samsung (and many other OEMs) drop mDNS packets without it. */
    private val multicastLock: WifiManager.MulticastLock by lazy {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi.createMulticastLock("canonprint-mdns").also { it.setReferenceCounted(false) }
    }

    private val serviceTypes = listOf(
        "_pdl-datastream._tcp",
        "_printer._tcp",
        "_ipp._tcp",
        "_ipps._tcp"
    )

    /** Active NSD discovery listeners, one per service type. */
    private val nsdDiscoveryListeners = mutableListOf<NsdManager.DiscoveryListener>()

    /**
     * Serialize NSD resolves: NsdManager.resolveService can only handle one
     * concurrent resolve request on older APIs — queue extras and drain.
     */
    private val resolveQueue = LinkedBlockingQueue<NsdServiceInfo>()

    @Volatile
    private var resolveInFlight = false

    private fun startNsdDiscovery(onFound: (Printer) -> Unit) {
        try {
            multicastLock.acquire()
            Log.d(TAG, "[disc/mdns] multicast lock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "[disc/mdns] multicastLock.acquire failed: ${e.message}")
        }

        for (type in serviceTypes) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "[disc/mdns] startDiscoveryFailed type=$serviceType code=$errorCode")
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "[disc/mdns] stopDiscoveryFailed type=$serviceType code=$errorCode")
                }
                override fun onDiscoveryStarted(serviceType: String) {
                    Log.d(TAG, "[disc/mdns] started type=$serviceType")
                }
                override fun onDiscoveryStopped(serviceType: String) {
                    Log.d(TAG, "[disc/mdns] stopped type=$serviceType")
                }
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "[disc/mdns] found name=${serviceInfo.serviceName} type=${serviceInfo.serviceType}")
                    enqueueResolve(serviceInfo, onFound)
                }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "[disc/mdns] lost name=${serviceInfo.serviceName}")
                }
            }
            try {
                nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                synchronized(nsdDiscoveryListeners) { nsdDiscoveryListeners.add(listener) }
                Log.d(TAG, "[disc/mdns] discoverServices registered type=$type")
            } catch (e: Exception) {
                Log.e(TAG, "[disc/mdns] discoverServices failed type=$type: ${e.message}")
            }
        }
    }

    private fun enqueueResolve(info: NsdServiceInfo, onFound: (Printer) -> Unit) {
        resolveQueue.offer(info)
        drainResolveQueue(onFound)
    }

    private fun drainResolveQueue(onFound: (Printer) -> Unit) {
        if (stopped) return
        synchronized(this) {
            if (resolveInFlight) return
            val next = resolveQueue.poll() ?: return
            resolveInFlight = true
            doResolve(next, onFound)
        }
    }

    private fun doResolve(info: NsdServiceInfo, onFound: (Printer) -> Unit) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "[disc/mdns] resolveFailed name=${serviceInfo.serviceName} code=$errorCode")
                synchronized(this@DiscoveryEngine) { resolveInFlight = false }
                drainResolveQueue(onFound)
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "[disc/mdns] resolved name=${serviceInfo.serviceName} host=${serviceInfo.host} port=${serviceInfo.port}")
                handleResolvedService(serviceInfo, onFound)
                synchronized(this@DiscoveryEngine) { resolveInFlight = false }
                drainResolveQueue(onFound)
            }
        }
        try {
            nsdManager.resolveService(info, resolveListener)
        } catch (e: Exception) {
            Log.e(TAG, "[disc/mdns] resolveService threw: ${e.message}")
            synchronized(this) { resolveInFlight = false }
            drainResolveQueue(onFound)
        }
    }

    private fun handleResolvedService(serviceInfo: NsdServiceInfo, onFound: (Printer) -> Unit) {
        val host: InetAddress = serviceInfo.host ?: return
        val ip = host.hostAddress ?: return
        // Use RAW port 9100 for all types except _pdl-datastream which is already raw print.
        // For _pdl-datastream use the actual resolved port; for others default to 9100.
        val type = serviceInfo.serviceType ?: ""
        val port = if ("_pdl-datastream" in type) {
            serviceInfo.port.takeIf { it > 0 } ?: 9100
        } else {
            9100
        }
        val name = serviceInfo.serviceName ?: "Printer ($ip)"
        report(
            Printer(
                id = "disc:$ip:$port",
                name = name,
                ip = ip,
                port = port,
                colorCapable = true,
                source = PrinterSource.DISCOVERED,
                model = "Network Printer"
            ),
            onFound
        )
    }

    private fun stopNsdDiscovery() {
        try {
            if (multicastLock.isHeld) {
                multicastLock.release()
                Log.d(TAG, "[disc/mdns] multicast lock released")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[disc/mdns] multicastLock.release failed: ${e.message}")
        }
        val listeners = synchronized(nsdDiscoveryListeners) {
            val copy = nsdDiscoveryListeners.toList()
            nsdDiscoveryListeners.clear()
            copy
        }
        for (listener in listeners) {
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                Log.w(TAG, "[disc/mdns] stopServiceDiscovery failed: ${e.message}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Prong B — TCP port-9100 subnet scan
    // -------------------------------------------------------------------------

    private val scanExecutor = Executors.newFixedThreadPool(48)
    private val scanFutures = mutableListOf<Future<*>>()

    private fun startTcpScan(onFound: (Printer) -> Unit) {
        val (ownIp, prefix) = getLocalIpAndPrefix()
        if (ownIp == null) {
            Log.w(TAG, "[disc/scan] cannot determine local IP — skipping TCP scan")
            return
        }
        Log.d(TAG, "[disc/scan] scanning /24 of $ownIp (own=$ownIp)")

        // Derive network base: for /24 just zero the last octet
        val parts = ownIp.split(".")
        if (parts.size != 4) {
            Log.w(TAG, "[disc/scan] unexpected IP format: $ownIp")
            return
        }
        val base = "${parts[0]}.${parts[1]}.${parts[2]}"

        synchronized(scanFutures) {
            for (i in 1..254) {
                val ip = "$base.$i"
                if (ip == ownIp || ip.endsWith(".0") || ip.endsWith(".255")) continue
                val future = scanExecutor.submit {
                    if (stopped) return@submit
                    try {
                        Socket().use { sock ->
                            sock.connect(InetSocketAddress(ip, 9100), 300)
                            Log.d(TAG, "[disc/scan] port 9100 open at $ip")
                            report(
                                Printer(
                                    id = "disc:$ip:9100",
                                    name = "Printer ($ip)",
                                    ip = ip,
                                    port = 9100,
                                    colorCapable = true,
                                    source = PrinterSource.DISCOVERED,
                                    model = "Network Printer"
                                ),
                                onFound
                            )
                        }
                    } catch (_: Exception) {
                        // Host unreachable or port closed — expected for most addresses
                    }
                }
                scanFutures.add(future)
            }
        }
    }

    /** Returns own IPv4 string and prefix length (always 24 if fallback). */
    private fun getLocalIpAndPrefix(): Pair<String?, Int> {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val props = if (network != null) cm.getLinkProperties(network) else null
            if (props != null) {
                // Walk LinkAddresses looking for a site-local (private) IPv4 address
                for (la in props.linkAddresses) {
                    val addr = la.address
                    if (addr is java.net.Inet4Address && addr.isSiteLocalAddress) {
                        return Pair(addr.hostAddress, la.prefixLength)
                    }
                }
            }
            // Fallback: WifiManager connectionInfo (deprecated API 31 but works on 29/30)
            @Suppress("DEPRECATION")
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ipInt = wm.connectionInfo?.ipAddress ?: 0
            if (ipInt == 0) return Pair(null, 24)
            // WifiManager returns little-endian int on little-endian devices
            val ipBytes = if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
                byteArrayOf(
                    (ipInt and 0xFF).toByte(),
                    ((ipInt shr 8) and 0xFF).toByte(),
                    ((ipInt shr 16) and 0xFF).toByte(),
                    ((ipInt shr 24) and 0xFF).toByte()
                )
            } else {
                byteArrayOf(
                    ((ipInt shr 24) and 0xFF).toByte(),
                    ((ipInt shr 16) and 0xFF).toByte(),
                    ((ipInt shr 8) and 0xFF).toByte(),
                    (ipInt and 0xFF).toByte()
                )
            }
            Pair(InetAddress.getByAddress(ipBytes).hostAddress, 24)
        } catch (e: Exception) {
            Log.e(TAG, "[disc/scan] getLocalIp failed: ${e.message}")
            Pair(null, 24)
        }
    }

    private fun stopTcpScan() {
        synchronized(scanFutures) {
            for (f in scanFutures) f.cancel(true)
            scanFutures.clear()
        }
        scanExecutor.shutdownNow()
        Log.d(TAG, "[disc/scan] executor shut down")
    }

    // -------------------------------------------------------------------------
    // Prong C — Canon BJNP UDP broadcast (best-effort, non-crashing)
    // -------------------------------------------------------------------------

    /**
     * BJNP discovery packet — 16-byte header modelled after cups-bjnp backend
     * (https://github.com/OpenPrinting/cups-bjnp).
     *
     * Layout (all multi-byte fields big-endian):
     *   Offset  Len  Value  Meaning
     *   0       4    "BJNP" ASCII magic bytes (0x42 0x4A 0x4E 0x50)
     *   4       1    0x01   device_type: 0x01 = printer
     *   5       1    0x01   command:     0x01 = discover/identify
     *   6       2    0x0000 sequence number (0 for discovery)
     *   8       2    0x0000 session_id (0 for discovery)
     *   10      2    0x0000 payload_length high word (no payload)
     *   12      4    0x0000 payload_length (32-bit, 0 = no payload)
     *
     * IMPORTANT: The exact byte layout is uncertain — it is based on the
     * cups-bjnp source code and may need adjustment against a real MF8030Cn.
     * If prong C yields no results or wrong results, capture a Wireshark trace
     * of cups-bjnp discovery and compare byte-by-byte against this array.
     */
    private val BJNP_DISCOVER_PACKET: ByteArray = byteArrayOf(
        0x42, 0x4A, 0x4E, 0x50,  // [0..3]  magic: "BJNP"
        0x01,                     // [4]     device_type: printer
        0x01,                     // [5]     command: discover
        0x00, 0x00,               // [6..7]  sequence number
        0x00, 0x00,               // [8..9]  session_id
        0x00, 0x00,               // [10..11] reserved / payload length high word
        0x00, 0x00, 0x00, 0x00   // [12..15] payload length (0 = no payload)
    )

    private val bjnpPorts = intOf(8610, 8612)

    @Volatile
    private var bjnpThread: Thread? = null

    private fun startBjnpDiscovery(onFound: (Printer) -> Unit) {
        val t = Thread {
            // Wrap the entire prong in try/catch so it NEVER crashes discovery.
            // Prongs A and B continue regardless of any failure here.
            try {
                runBjnpDiscovery(onFound)
            } catch (e: Exception) {
                Log.w(TAG, "[disc/bjnp] best-effort prong failed (A+B still running): ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        t.name = "canonprint-bjnp"
        t.isDaemon = true
        bjnpThread = t
        t.start()
    }

    private fun runBjnpDiscovery(onFound: (Printer) -> Unit) {
        for (bjnpPort in bjnpPorts) {
            if (stopped) break
            Log.d(TAG, "[disc/bjnp] sending discovery broadcast port=$bjnpPort")
            try {
                DatagramSocket().use { sock ->
                    sock.broadcast = true
                    sock.soTimeout = 2000  // 2-second receive window

                    val broadcast = InetAddress.getByName("255.255.255.255")
                    val pkt = DatagramPacket(BJNP_DISCOVER_PACKET, BJNP_DISCOVER_PACKET.size, broadcast, bjnpPort)
                    sock.send(pkt)
                    Log.d(TAG, "[disc/bjnp] broadcast sent port=$bjnpPort bytes=${BJNP_DISCOVER_PACKET.size}")

                    // Listen for unicast replies for ~2 seconds
                    val buf = ByteArray(1024)
                    val reply = DatagramPacket(buf, buf.size)
                    val deadline = System.currentTimeMillis() + 2000L
                    while (System.currentTimeMillis() < deadline && !stopped) {
                        try {
                            sock.receive(reply)
                            val senderIp = reply.address?.hostAddress
                            if (senderIp != null) {
                                Log.d(TAG, "[disc/bjnp] reply from $senderIp port=$bjnpPort len=${reply.length}")
                                report(
                                    Printer(
                                        id = "disc:$senderIp:9100",
                                        name = "Canon ($senderIp)",
                                        ip = senderIp,
                                        port = 9100,
                                        colorCapable = true,
                                        source = PrinterSource.DISCOVERED,
                                        model = "Canon (BJNP)"
                                    ),
                                    onFound
                                )
                            }
                        } catch (_: java.net.SocketTimeoutException) {
                            // Timeout on receive — normal end of listen window
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                // Permission denied, no WiFi, malformed packet — log and try next port
                Log.w(TAG, "[disc/bjnp] port=$bjnpPort failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Shared reporting helper
    // -------------------------------------------------------------------------

    /** Reports [printer] via [onFound] on the main thread if it hasn't been reported yet. */
    private fun report(printer: Printer, onFound: (Printer) -> Unit) {
        val key = "${printer.ip}:${printer.port}"
        if (reported.putIfAbsent(key, true) == null) {
            Log.i(TAG, "[disc] new printer key=$key name=${printer.name} source=${printer.source}")
            main.post { if (!stopped) onFound(printer) }
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Starts all three discovery prongs.  Safe to call once per session.
     * [onFound] is always invoked on the main thread; the same printer (by ip:port)
     * is reported at most once per session even if multiple prongs find it.
     */
    fun start(onFound: (Printer) -> Unit) {
        stopped = false
        reported.clear()
        Log.i(TAG, "[disc] start — launching prongs A (mDNS), B (TCP scan), C (BJNP)")
        startNsdDiscovery(onFound)
        startTcpScan(onFound)
        startBjnpDiscovery(onFound)
    }

    /**
     * Stops all discovery prongs, unregisters NSD listeners, releases the
     * multicast lock, shuts down the scan thread pool, and interrupts the BJNP
     * thread.  Safe to call multiple times.
     */
    fun stop() {
        if (stopped) return
        stopped = true
        Log.i(TAG, "[disc] stop — tearing down all prongs")
        stopNsdDiscovery()
        stopTcpScan()
        bjnpThread?.interrupt()
        bjnpThread = null
        resolveQueue.clear()
    }

    // -------------------------------------------------------------------------
    // Kotlin/Java interop helpers
    // -------------------------------------------------------------------------

    /** Convenience: vararg Int → plain IntArray for bjnpPorts. */
    @Suppress("NOTHING_TO_INLINE")
    private inline fun intOf(vararg values: Int): IntArray = values
}
