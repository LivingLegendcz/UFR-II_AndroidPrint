package cz.ufrii.print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.OutputStream

private const val TAG = "CanonPrint"

/**
 * Writes a CUPS Raster v3 stream for Canon MF8030 (A4 @ 600 dpi, RGB chunky).
 *
 * Stream layout (per CUPS Raster spec):
 *   - 4-byte SYNC word  (once per stream, before all pages)
 *   - For each page:
 *       1796-byte cups_page_header2_t  (copied verbatim from the golden-header asset)
 *       cupsHeight * BYTES_PER_LINE raw RGB bytes  (R G B per pixel, no compression)
 *
 * The page-header template is loaded once from assets and reused for every page;
 * this guarantees byte-identical headers to the golden reference accepted by rastertoufr2.
 *
 * Pixel assembly is done in horizontal bands (BAND_ROWS rows at a time) to avoid OOM
 * when rendering a 4724×6780 page.  The caller supplies a [renderBand] lambda that
 * fills a pre-allocated Bitmap with content for each band; this keeps Bitmap/Android
 * details out of the raster-assembly core.
 */
class CupsRasterWriter(context: Context) {

    // -------------------------------------------------------------------------
    // Public constants
    // -------------------------------------------------------------------------

    companion object {
        /** CUPS Raster v3 sync word: "3SaR" little-endian = 0x33 0x53 0x61 0x52 */
        val SYNC: ByteArray = byteArrayOf(0x33, 0x53, 0x61, 0x52)

        /** A4 @ 600 dpi raster dimensions – must match the header template. */
        const val CUPS_WIDTH: Int = 4724
        const val CUPS_HEIGHT: Int = 6780

        /** BYTES_PER_LINE = CUPS_WIDTH * 3 (RGB, 1 byte per channel, no padding). */
        const val BYTES_PER_LINE: Int = 14172   // = 4724 * 3

        /** Size of cups_page_header2_t as used by rastertoufr2. */
        private const val HEADER_SIZE: Int = 1796

        /** Number of raster rows processed per band — balance between RAM and overhead. */
        private const val BAND_ROWS: Int = 256
    }

    // -------------------------------------------------------------------------
    // Header template (loaded once, immutable, reused per page)
    // -------------------------------------------------------------------------

    private val headerTemplate: ByteArray = run {
        val bytes = context.assets.open("cups_page_header_a4.bin").use { it.readBytes() }
        check(bytes.size == HEADER_SIZE) {
            "cups_page_header_a4.bin has ${bytes.size} bytes, expected $HEADER_SIZE"
        }
        Log.d(TAG, "[raster] header template loaded size=$HEADER_SIZE")
        bytes
    }

    // -------------------------------------------------------------------------
    // Core stream primitives
    // -------------------------------------------------------------------------

    /**
     * Writes the 4-byte CUPS Raster v3 SYNC word to [out].
     * Call this ONCE at the very beginning of a new raster stream (before all pages).
     */
    fun writeStreamHeader(out: OutputStream) {
        out.write(SYNC)
        Log.d(TAG, "[raster] sync written")
    }

    /**
     * Writes one complete raster page to [out]:
     *   1. The 1796-byte page-header template (verbatim copy from asset).
     *   2. [CUPS_HEIGHT] rows of raw RGB pixels, produced band-by-band.
     *
     * @param out        Destination stream (no buffering needed; caller may wrap in BufferedOutputStream).
     * @param renderBand Callback invoked for each band.
     *                   Contract: fill [dst] (a [Bitmap] of size CUPS_WIDTH × bandRows)
     *                   with the image content for rows [bandTopRow]..[bandTopRow+bandRows).
     *                   The bitmap is ARGB_8888; alpha is discarded when converting to RGB.
     * @param progress   Optional callback receiving cumulative rows written so far.
     */
    fun writePage(
        out: OutputStream,
        pageIndex: Int = 0,
        renderBand: (bandTopRow: Int, bandRows: Int, dst: Bitmap) -> Unit,
        progress: ((rowsDone: Int) -> Unit)? = null
    ) {
        // --- 1. Page header ---
        out.write(headerTemplate)
        Log.d(TAG, "[raster] page header written size=$HEADER_SIZE")

        // --- 2. Allocate reusable buffers (one set for the whole page) ---
        // Bitmap: CUPS_WIDTH wide, BAND_ROWS tall (partial last band also fits).
        val bandBitmap = Bitmap.createBitmap(CUPS_WIDTH, BAND_ROWS, Bitmap.Config.ARGB_8888)
        val argbBuf = IntArray(CUPS_WIDTH * BAND_ROWS)
        val rgbBuf  = ByteArray(CUPS_WIDTH * BAND_ROWS * 3)

        var totalRowsWritten = 0
        var totalBytesWritten = 0L

        try {
            // --- 3. Band loop ---
            var bandTop = 0
            while (bandTop < CUPS_HEIGHT) {
                val bandRows = minOf(BAND_ROWS, CUPS_HEIGHT - bandTop)
                // bandRows may be < BAND_ROWS on the last (partial) band.

                // 3a. Ask caller to fill the bitmap for this band.
                val bandIndex = bandTop / BAND_ROWS
                try {
                    renderBand(bandTop, bandRows, bandBitmap)
                } catch (t: Throwable) {
                    throw RuntimeException(
                        "render selhal: page=$pageIndex band=$bandIndex top=$bandTop: ${t.message}", t
                    )
                }

                // 3b. Extract ARGB pixels from the bitmap.
                //     Only the first bandRows*CUPS_WIDTH pixels are valid.
                bandBitmap.getPixels(
                    argbBuf,
                    0,          // offset in argbBuf
                    CUPS_WIDTH, // stride
                    0, 0,       // x, y within bitmap
                    CUPS_WIDTH,
                    bandRows
                )

                // 3c. Convert ARGB_8888 → RGB (drop alpha) into rgbBuf.
                val pixelCount = bandRows * CUPS_WIDTH
                var argbIdx = 0
                var rgbIdx  = 0
                while (argbIdx < pixelCount) {
                    val px = argbBuf[argbIdx++]
                    rgbBuf[rgbIdx++] = ((px shr 16) and 0xFF).toByte()  // R
                    rgbBuf[rgbIdx++] = ((px shr  8) and 0xFF).toByte()  // G
                    rgbBuf[rgbIdx++] = ( px         and 0xFF).toByte()  // B
                }

                // 3d. Write exactly bandRows * BYTES_PER_LINE bytes.
                val bandBytes = bandRows * BYTES_PER_LINE
                out.write(rgbBuf, 0, bandBytes)

                totalRowsWritten  += bandRows
                totalBytesWritten += bandBytes

                progress?.invoke(totalRowsWritten)
                Log.v(TAG, "[raster] band bandTop=$bandTop bandRows=$bandRows bandBytes=$bandBytes")

                bandTop += bandRows
            }
        } finally {
            bandBitmap.recycle()
        }

        // --- 4. Integrity check ---
        val expectedBytes = CUPS_HEIGHT.toLong() * BYTES_PER_LINE
        check(totalBytesWritten == expectedBytes) {
            "writePage: wrote $totalBytesWritten bytes, expected $expectedBytes"
        }
        Log.d(TAG, "[raster] page done rows=$totalRowsWritten bytes=$totalBytesWritten")
    }

    // -------------------------------------------------------------------------
    // High-level helpers
    // -------------------------------------------------------------------------

    /**
     * Renders every page of a PDF document to a CUPS Raster stream.
     *
     * Stream layout:  SYNC  [header + pixels] × numPages
     *
     * Each PDF page is scaled (preserving aspect ratio, white background) to fill
     * CUPS_WIDTH × CUPS_HEIGHT.  Only the rows for the current band are rendered
     * per [PdfRenderer.Page.render] call using a translate matrix, avoiding
     * allocating a full-page bitmap.
     *
     * @param out      Destination stream.
     * @param pdfFd    ParcelFileDescriptor opened on the PDF file (read mode).
     * @param progress Optional row-progress callback (resets to 0 at each new page).
     */
    fun writePdf(
        out: OutputStream,
        pdfFd: ParcelFileDescriptor,
        progress: ((rowsDone: Int) -> Unit)? = null
    ) {
        writeStreamHeader(out)

        PdfRenderer(pdfFd).use { renderer ->
            val pageCount = renderer.pageCount
            Log.d(TAG, "[raster] pdf pageCount=$pageCount")

            for (pageIndex in 0 until pageCount) {
                renderer.openPage(pageIndex).use { page ->
                    Log.d(TAG, "[raster] rendering pdf page $pageIndex/${pageCount-1} " +
                               "pdfW=${page.width} pdfH=${page.height}")

                    // Compute uniform scale so the whole PDF page fits within CUPS canvas.
                    val scaleX = CUPS_WIDTH.toFloat()  / page.width
                    val scaleY = CUPS_HEIGHT.toFloat() / page.height
                    val scale  = minOf(scaleX, scaleY)

                    // Center the scaled content on the canvas.
                    val scaledW = page.width  * scale
                    val scaledH = page.height * scale
                    val offsetX = (CUPS_WIDTH  - scaledW) / 2f
                    val offsetY = (CUPS_HEIGHT - scaledH) / 2f

                    writePage(out, pageIndex = pageIndex, renderBand = { bandTop, _, bitmap ->
                        val bandIndex = bandTop / BAND_ROWS
                        // Matrix: scale the PDF, translate so only the current band is
                        // drawn at (0,0) in the bitmap.
                        val matrix = Matrix().apply {
                            setScale(scale, scale)
                            postTranslate(offsetX, offsetY - bandTop)
                        }
                        // Fill white background for this band.
                        bitmap.eraseColor(Color.WHITE)
                        // Render PDF content clipped to this band — wrap for diagnostic context.
                        try {
                            page.render(
                                bitmap,
                                null,   // destClip: null = whole bitmap
                                matrix,
                                PdfRenderer.Page.RENDER_MODE_FOR_PRINT
                            )
                        } catch (t: Throwable) {
                            throw RuntimeException(
                                "page.render selhal: page=$pageIndex band=$bandIndex top=$bandTop: ${t.message}", t
                            )
                        }
                    }, progress = progress)
                }
            }
        }
        Log.d(TAG, "[raster] writePdf complete")
    }

    /**
     * Renders a single [Bitmap] (any size/aspect) to a one-page CUPS Raster stream.
     *
     * The source image is scaled to fit within CUPS_WIDTH × CUPS_HEIGHT (preserving
     * aspect ratio) and centered on a white background.
     *
     * Stream layout:  SYNC  header + pixels
     *
     * @param out      Destination stream.
     * @param bitmap   Source image (not recycled by this method).
     * @param progress Optional row-progress callback.
     */
    fun writeImage(
        out: OutputStream,
        bitmap: Bitmap,
        progress: ((rowsDone: Int) -> Unit)? = null
    ) {
        writeStreamHeader(out)

        Log.d(TAG, "[raster] writeImage srcW=${bitmap.width} srcH=${bitmap.height}")

        // Uniform scale to fit within CUPS canvas.
        val scaleX = CUPS_WIDTH.toFloat()  / bitmap.width
        val scaleY = CUPS_HEIGHT.toFloat() / bitmap.height
        val scale  = minOf(scaleX, scaleY)

        val scaledW = bitmap.width  * scale
        val scaledH = bitmap.height * scale
        val offsetX = (CUPS_WIDTH  - scaledW) / 2f
        val offsetY = (CUPS_HEIGHT - scaledH) / 2f

        writePage(out, renderBand = { bandTop, _, bandBitmap ->
            val canvas = Canvas(bandBitmap)
            canvas.drawColor(Color.WHITE)

            val matrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate(offsetX, offsetY - bandTop)
            }
            canvas.drawBitmap(bitmap, matrix, null)
        }, progress = progress)

        Log.d(TAG, "[raster] writeImage complete")
    }
}
