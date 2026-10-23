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
import kotlin.math.roundToInt

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
class CupsRasterWriter(context: Context, private val geometry: PageGeometry = PageGeometry.DEFAULT) {

    /** Raster size of this printer model (A4 at 600 dpi; the printable area comes from its PPD). */
    private val width = geometry.width
    private val height = geometry.height
    private val bytesPerLine = geometry.bytesPerLine

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
        if (!geometry.isDefault) patchGeometry(bytes)
        bytes
    }

    /**
     * Writes this model's page size into the golden header (cups_page_header2_t, little-endian):
     * ImagingBoundingBox, Margins, cupsWidth/Height/BytesPerLine and cupsImagingBBox. Everything
     * else (RGB 8-bit chunky, 600 dpi, A4 PageSize) stays as the filter accepted it.
     */
    private fun patchGeometry(bytes: ByteArray) {
        val a = geometry.area
        val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        intArrayOf(a[0].roundToInt(), a[1].roundToInt(), a[2].roundToInt(), a[3].roundToInt())
            .forEachIndexed { i, v -> b.putInt(284 + 4 * i, v) }      // ImagingBoundingBox
        b.putInt(312, a[0].roundToInt()); b.putInt(316, a[1].roundToInt())   // Margins
        b.putInt(372, width); b.putInt(376, height); b.putInt(392, bytesPerLine)
        a.forEachIndexed { i, v -> b.putFloat(436 + 4 * i, v) }        // cupsImagingBBox
        Log.i(TAG, "[raster] header geometry ${width}x$height area=${a.joinToString()}")
    }

    // -------------------------------------------------------------------------
    // Band buffers (~13 MB together) — shared by all pages of one stream
    // -------------------------------------------------------------------------

    private var bandBitmap: Bitmap? = null
    private var argbBuf: IntArray? = null
    private var rgbBuf: ByteArray? = null

    /** Bitmap width × BAND_ROWS (a partial last band also fits) + pixel/RGB arrays. */
    private fun buffers(): Triple<Bitmap, IntArray, ByteArray> {
        val bmp = bandBitmap ?: Bitmap.createBitmap(width, BAND_ROWS, Bitmap.Config.ARGB_8888).also { bandBitmap = it }
        val argb = argbBuf ?: IntArray(width * BAND_ROWS).also { argbBuf = it }
        val rgb = rgbBuf ?: ByteArray(width * BAND_ROWS * 3).also { rgbBuf = it }
        return Triple(bmp, argb, rgb)
    }

    /** Frees the band buffers; the high-level writers call it when the stream is done. */
    private fun release() {
        bandBitmap?.recycle()
        bandBitmap = null
        argbBuf = null
        rgbBuf = null
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
     *   2. [height] rows of raw RGB pixels, produced band-by-band.
     *
     * @param out        Destination stream (no buffering needed; caller may wrap in BufferedOutputStream).
     * @param renderBand Callback invoked for each band.
     *                   Contract: fill [dst] (a [Bitmap] of size width × bandRows)
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

        // --- 2. Band buffers: allocated once per writer, reused by every page and copy ---
        val (bandBitmap, argbBuf, rgbBuf) = buffers()

        var totalRowsWritten = 0
        var totalBytesWritten = 0L

        // --- 3. Band loop ---
        var bandTop = 0
        while (bandTop < height) {
            val bandRows = minOf(BAND_ROWS, height - bandTop)
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
            //     Only the first bandRows*width pixels are valid.
            bandBitmap.getPixels(
                argbBuf,
                0,          // offset in argbBuf
                width, // stride
                0, 0,       // x, y within bitmap
                width,
                bandRows
            )

            // 3c. Convert ARGB_8888 → RGB (drop alpha) into rgbBuf.
            val pixelCount = bandRows * width
            var argbIdx = 0
            var rgbIdx  = 0
            while (argbIdx < pixelCount) {
                val px = argbBuf[argbIdx++]
                rgbBuf[rgbIdx++] = ((px shr 16) and 0xFF).toByte()  // R
                rgbBuf[rgbIdx++] = ((px shr  8) and 0xFF).toByte()  // G
                rgbBuf[rgbIdx++] = ( px         and 0xFF).toByte()  // B
            }

            // 3d. Write exactly bandRows * bytesPerLine bytes.
            val bandBytes = bandRows * bytesPerLine
            out.write(rgbBuf, 0, bandBytes)

            totalRowsWritten  += bandRows
            totalBytesWritten += bandBytes

            progress?.invoke(totalRowsWritten)
            Log.v(TAG, "[raster] band bandTop=$bandTop bandRows=$bandRows bandBytes=$bandBytes")

            bandTop += bandRows
        }

        // --- 4. Integrity check ---
        val expectedBytes = height.toLong() * bytesPerLine
        check(totalBytesWritten == expectedBytes) {
            "writePage: wrote $totalBytesWritten bytes, expected $expectedBytes"
        }
        Log.d(TAG, "[raster] page done rows=$totalRowsWritten bytes=$totalBytesWritten")
    }

    // -------------------------------------------------------------------------
    // High-level helpers
    // -------------------------------------------------------------------------

    /**
     * Renders pages of a PDF document to a CUPS Raster stream.
     *
     * Stream layout:  SYNC  [header + pixels] × pages × copies
     *
     * Each PDF page is scaled (preserving aspect ratio, white background) to fill
     * width × height.  Only the rows for the current band are rendered
     * per [PdfRenderer.Page.render] call using a translate matrix, avoiding
     * allocating a full-page bitmap.
     *
     * @param out       Destination stream.
     * @param pdfFd     ParcelFileDescriptor opened on the PDF file (read mode).
     * @param copies    F2: rastertoufr2 ignores the copies argv (verified on device —
     *                  copies=2 printed 1 sheet), so the selection is emitted
     *                  [copies]× in sequence → collated output (1,2,3,1,2,3).
     * @param pages     0-based page indexes in print order (null = all pages);
     *                  -1 emits a blank page (manual duplex keeps sheets paired).
     * @param rotate180 Render upside down (manual-duplex back sides, per calibration).
     * @param onPage    Called before each emitted page with (index, total).
     * @param progress  Optional row-progress callback (resets to 0 at each new page).
     */
    fun writePdf(
        out: OutputStream,
        pdfFd: ParcelFileDescriptor,
        copies: Int = 1,
        pages: List<Int>? = null,
        rotate180: Boolean = false,
        onPage: ((done: Int, total: Int) -> Unit)? = null,
        progress: ((rowsDone: Int) -> Unit)? = null,
        padOdd: Boolean = false
    ) {
        writeStreamHeader(out)

        try { PdfRenderer(pdfFd).use { renderer ->
            val pageCount = renderer.pageCount
            // Duplex unit + copies: an odd page count gets a blank back so each copy starts on a new sheet.
            val selection = (pages ?: (0 until pageCount).toList()).filter { it < pageCount }
                .let { if (padOdd && copies > 1 && it.size % 2 == 1) it + -1 else it }
            val sequence = List(copies.coerceAtLeast(1)) { selection }.flatten()
            Log.d(TAG, "[raster] pdf pageCount=$pageCount selection=${selection.size} copies=$copies rotate180=$rotate180")

            sequence.forEachIndexed { n, pageIndex ->
                onPage?.invoke(n, sequence.size)
                if (pageIndex < 0) {
                    writePage(out, pageIndex = -1, renderBand = { _, _, bitmap -> bitmap.eraseColor(Color.WHITE) },
                        progress = progress)
                    return@forEachIndexed
                }
                renderer.openPage(pageIndex).use { page ->
                    // F4: landscape pages are rotated 90° to fill the portrait canvas
                    // instead of being letterboxed down to ~70 %.
                    val landscape = page.width > page.height
                    Log.d(TAG, "[raster] rendering pdf page $pageIndex/${pageCount-1} " +
                               "pdfW=${page.width} pdfH=${page.height} landscape=$landscape")

                    // Effective (post-rotation) content dimensions in page units.
                    val effW = if (landscape) page.height else page.width
                    val effH = if (landscape) page.width  else page.height

                    // Uniform scale so the (possibly rotated) page fits within CUPS canvas.
                    val scale = minOf(width.toFloat() / effW, height.toFloat() / effH)

                    // Center the scaled content on the canvas.
                    val offsetX = (width  - effW * scale) / 2f
                    val offsetY = (height - effH * scale) / 2f

                    writePage(out, pageIndex = pageIndex, renderBand = { bandTop, _, bitmap ->
                        val bandIndex = bandTop / BAND_ROWS
                        // Matrix: (rotate 90° for landscape) → scale → centre → (180° for
                        // duplex backs) → translate so only the current band is drawn at (0,0).
                        val matrix = Matrix().apply {
                            if (landscape) {
                                // 90° CW maps (x,y)→(-y,x); shift +page.height back into +x.
                                setRotate(90f)
                                postTranslate(page.height.toFloat(), 0f)
                                postScale(scale, scale)
                            } else {
                                setScale(scale, scale)
                            }
                            postTranslate(offsetX, offsetY)
                            if (rotate180) postRotate(180f, width / 2f, height / 2f)
                            postTranslate(0f, -bandTop.toFloat())
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
        } } finally { release() }
        Log.d(TAG, "[raster] writePdf complete")
    }

    /**
     * Writes pages drawn directly on a full-page [Canvas] (calibration sheets). Each
     * [drawPages] entry draws one page in raster pixel coordinates (4724 × 6780).
     */
    fun writeDrawn(out: OutputStream, drawPages: List<(Canvas) -> Unit>) {
        writeStreamHeader(out)
        try { drawPages.forEachIndexed { i, draw ->
            writePage(out, pageIndex = i, renderBand = { bandTop, _, bitmap ->
                bitmap.eraseColor(Color.WHITE)
                val canvas = Canvas(bitmap)
                canvas.translate(0f, -bandTop.toFloat())
                draw(canvas)
            })
        } } finally { release() }
    }

    /**
     * Renders a single [Bitmap] (any size/aspect) to a one-page CUPS Raster stream.
     *
     * The source image is scaled to fit within width × height (preserving
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

        // F4: rotate wide (landscape/panorama) images 90° to fill the portrait canvas.
        val landscape = bitmap.width > bitmap.height
        Log.d(TAG, "[raster] writeImage srcW=${bitmap.width} srcH=${bitmap.height} landscape=$landscape")

        // Effective (post-rotation) dimensions.
        val effW = if (landscape) bitmap.height else bitmap.width
        val effH = if (landscape) bitmap.width  else bitmap.height

        // Uniform scale to fit within CUPS canvas.
        val scale = minOf(width.toFloat() / effW, height.toFloat() / effH)

        val offsetX = (width  - effW * scale) / 2f
        val offsetY = (height - effH * scale) / 2f

        try { writePage(out, renderBand = { bandTop, _, bandBitmap ->
            val canvas = Canvas(bandBitmap)
            canvas.drawColor(Color.WHITE)

            val matrix = Matrix().apply {
                if (landscape) {
                    setRotate(90f)
                    postTranslate(bitmap.height.toFloat(), 0f)
                    postScale(scale, scale)
                } else {
                    setScale(scale, scale)
                }
                postTranslate(offsetX, offsetY - bandTop)
            }
            canvas.drawBitmap(bitmap, matrix, null)
        }, progress = progress) } finally { release() }

        Log.d(TAG, "[raster] writeImage complete")
    }
}
