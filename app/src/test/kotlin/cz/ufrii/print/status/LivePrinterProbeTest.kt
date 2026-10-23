package cz.ufrii.print.status

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Manual check against a real printer — skipped unless LIVE_PRINTER_IP is set:
 *   LIVE_PRINTER_IP=192.168.0.62 ./gradlew testDebugUnitTest --tests '*LivePrinterProbeTest*' -i
 * Unicast SNMP + one MFNP GET_ID only (docs/21 rules).
 */
class LivePrinterProbeTest {
    private val ip: String? = System.getenv("LIVE_PRINTER_IP")

    @Test
    fun probeAndStatus() = runBlocking {
        assumeTrue(ip != null)
        val caps = PrinterProbe.probeCapabilities(ip!!)
        println("CAPS: $caps")
        val status = PrinterProbe.readStatus(ip)
        println("STATUS: $status")
        println("TCP 9100: ${PrinterProbe.tcpReachable(ip)}")
    }
}
