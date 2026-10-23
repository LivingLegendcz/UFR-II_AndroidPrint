package cz.ufrii.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrintOptionsTest {

    private val up = DuplexCalibration(faceUp = true, rotate180 = false)
    private val down = DuplexCalibration(faceUp = false, rotate180 = false)

    @Test fun `even page count pairs fronts and backs`() {
        val p = DuplexPlanner.plan(listOf(0, 1, 2, 3), 1, PaperSource.CASSETTE, up)
        assertEquals(listOf(0, 2), p.fronts)
        assertEquals(listOf(1, 3), p.backs)
    }

    @Test fun `odd page count gets a blank back`() {
        val p = DuplexPlanner.plan(listOf(0, 1, 2), 1, PaperSource.CASSETTE, up)
        assertEquals(listOf(0, 2), p.fronts)
        assertEquals(listOf(1, -1), p.backs)
    }

    @Test fun `face down cassette reverses the backs`() {
        val p = DuplexPlanner.plan(listOf(0, 1, 2), 1, PaperSource.CASSETTE, down)
        assertEquals(listOf(-1, 1), p.backs)
    }

    @Test fun `manual feeder keeps order even face down`() {
        val p = DuplexPlanner.plan(listOf(0, 1, 2, 3), 1, PaperSource.MANUAL, down)
        assertEquals(listOf(1, 3), p.backs)
    }

    @Test fun `each copy starts on a new sheet`() {
        val p = DuplexPlanner.plan(listOf(0, 1, 2), 2, PaperSource.CASSETTE, up)
        assertEquals(listOf(0, 2, 0, 2), p.fronts)
        assertEquals(listOf(1, -1, 1, -1), p.backs)
    }

    @Test fun `page ranges`() {
        assertEquals(listOf(0, 1, 2, 4), parsePageRange("1-3, 5", 10))
        assertEquals(listOf(7, 8, 9), parsePageRange("8-", 10))
        assertEquals(listOf(0, 1), parsePageRange("-2", 10))
        assertNull(parsePageRange("0", 10))
        assertNull(parsePageRange("3-11", 10))
        assertNull(parsePageRange("5-3", 10))
        assertNull(parsePageRange("abc", 10))
        assertNull(parsePageRange("", 10))
    }
}
