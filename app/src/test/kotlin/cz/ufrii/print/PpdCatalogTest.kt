package cz.ufrii.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PpdCatalogTest {

    @Test fun `model names normalise like build-ppd-pack`() {
        assertEquals("MF8030", PpdCatalog.norm("Canon MF8030 /P"))
        assertEquals("MF8000", PpdCatalog.norm("MF8000 Series"))
        assertEquals("MF4550D", PpdCatalog.norm("Canon i-SENSYS MF4550d"))
        assertEquals("MF642C", PpdCatalog.norm("Canon MF642C UFR II"))
    }

    @Test fun `golden A4 area keeps the verified header size`() {
        val g = PageGeometry.fromArea(floatArrayOf(14.173f, 14.173f, 581.127f, 827.727f))
        assertTrue(g.isDefault)
        assertEquals(14172, g.bytesPerLine)
    }

    @Test fun `smaller printable area of older models shrinks only the height`() {
        // MF4600 and 8 others: 14.173 17.008 581.127 824.892
        val g = PageGeometry.fromArea(floatArrayOf(14.173f, 17.008f, 581.127f, 824.892f))
        assertEquals(4724, g.width)
        assertEquals(6733, g.height)
    }
}
