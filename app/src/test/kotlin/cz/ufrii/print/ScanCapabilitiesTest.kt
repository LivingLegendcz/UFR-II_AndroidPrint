package cz.ufrii.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScanCapabilitiesTest {

    /** Verbatim `scanimage -A` of the MF8030Cn (patched pixma, 2026-10-23). */
    private val live = """
        All options specific to device `pixma:MF8000_192.168.0.62':
            --resolution auto||75|150|300|600dpi [75]
                Sets the resolution of the scanned image.
            --mode auto|Color|Gray|Lineart [Color]
                Selects the scan mode (e.g., lineart, monochrome, or color).
            --source Flatbed|Automatic Document Feeder [Flatbed]
                Selects the scan source (such as a document-feeder).
            -l auto|0..216.747mm [0]
                Top-left x position of scan area.
            -t auto|0..297.011mm [0]
                Top-left y position of scan area.
            -x auto|0..216.747mm [216.747]
                Width of scan-area.
            -y auto|0..297.011mm [297.011]
                Height of scan-area.
    """.trimIndent()

    @Test fun `parses the live MF8030Cn options`() {
        val c = ScanCapabilities.parse(live)!!
        assertEquals(listOf(ScanSource.GLASS, ScanSource.FEEDER), c.sources)
        assertEquals(listOf(ScanMode.COLOR, ScanMode.GRAY, ScanMode.LINEART), c.modes)
        assertEquals(listOf(75, 150, 300, 600), c.resolutions)
    }

    @Test fun `glass size from the scan-area ranges`() {
        val c = ScanCapabilities.parse(live)!!
        assertEquals(216.747f, c.glassW!!, 0.001f)
        assertEquals(297.011f, c.glassH!!, 0.001f)
        assertNull(ScanCapabilities.parse("    --resolution 75|150dpi [75]\n    --mode Color [Color]\n")!!.glassW)
    }

    @Test fun `range resolution and no feeder`() {
        val c = ScanCapabilities.parse("    --resolution 50..300dpi [100]\n    --mode Color|Gray [Color]\n")!!
        assertEquals(listOf(ScanSource.GLASS), c.sources)
        assertEquals(listOf(75, 100, 150, 200, 300), c.resolutions)
    }

    @Test fun `no answer gives null`() {
        assertNull(ScanCapabilities.parse("scanimage: open of device pixma:MF8000_1.2.3.4 failed: Invalid argument"))
    }
}
