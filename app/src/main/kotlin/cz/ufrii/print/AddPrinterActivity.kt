package cz.ufrii.print

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "AddPrinter"

class AddPrinterActivity : AppCompatActivity() {

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    /** Non-null when the form is loaded for editing an existing printer. */
    private var editingId: String? = null

    /** Discovery engine; created lazily, stopped in onPause/onDestroy. */
    private var discoveryEngine: DiscoveryEngine? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // -------------------------------------------------------------------------
    // UI references
    // -------------------------------------------------------------------------

    private lateinit var etName: EditText
    private lateinit var etIp: EditText
    private lateinit var etPort: EditText
    private lateinit var rbColor: RadioButton
    private lateinit var rbMono: RadioButton
    private lateinit var rgColor: RadioGroup
    private lateinit var tvConnStatus: TextView
    private lateinit var tvDiscoveryNote: TextView
    private lateinit var llDiscoveryResults: LinearLayout
    private lateinit var llPrinterList: LinearLayout

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refreshPrinterList()
        Log.i(TAG, "[add-printer] onCreate")
    }

    override fun onPause() {
        super.onPause()
        stopDiscovery()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDiscovery()
    }

    // -------------------------------------------------------------------------
    // Discovery helpers
    // -------------------------------------------------------------------------

    private fun stopDiscovery() {
        discoveryEngine?.stop()
        discoveryEngine = null
        Log.i(TAG, "[add-printer] discovery stopped")
    }

    // -------------------------------------------------------------------------
    // Form logic
    // -------------------------------------------------------------------------

    /** Validate IP/hostname: non-empty, no whitespace. */
    private fun isValidAddress(ip: String): Boolean =
        ip.isNotBlank() && !ip.any { it.isWhitespace() }

    /** Parse port; returns default 9100 on blank or invalid input. */
    private fun parsePort(raw: String): Int =
        raw.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: PrinterStore.DEFAULT_PORT

    /** Load an existing Printer into the form for editing. */
    private fun loadIntoForm(p: Printer) {
        editingId = p.id
        etName.setText(p.name)
        etIp.setText(p.ip)
        etPort.setText(p.port.toString())
        if (p.colorCapable) rgColor.check(rbColor.id) else rgColor.check(rbMono.id)
        tvConnStatus.text = ""
        Log.i(TAG, "[add-printer] loadIntoForm id=${p.id} ip=${p.ip}")
    }

    /** Clear the form back to defaults. */
    private fun clearForm() {
        editingId = null
        etName.setText("")
        etIp.setText("")
        etPort.setText("9100")
        rgColor.check(rbColor.id)
        tvConnStatus.text = ""
    }

    private fun doSave() {
        val ip = etIp.text.toString().trim()
        if (!isValidAddress(ip)) {
            Toast.makeText(this, "IP adresa nesmí být prázdná a nesmí obsahovat mezery.", Toast.LENGTH_LONG).show()
            return
        }
        val name = etName.text.toString().trim().ifBlank { ip }
        val port = parsePort(etPort.text.toString())
        val colorCapable = rbColor.isChecked

        val currentEditingId = editingId
        if (currentEditingId != null) {
            // Update existing printer
            val updated = Printer(
                id           = currentEditingId,
                name         = name,
                ip           = ip,
                port         = port,
                colorCapable = colorCapable,
                source       = PrinterSource.MANUAL,
                model        = "Canon MF8030Cn"
            )
            PrinterStore.update(this, updated)
            Log.i(TAG, "[add-printer] updated id=$currentEditingId ip=$ip")
            Toast.makeText(this, "Tiskárna aktualizována.", Toast.LENGTH_SHORT).show()
        } else {
            // Add new printer
            val newPrinter = Printer(
                id           = PrinterStore.newManualId(),
                name         = name,
                ip           = ip,
                port         = port,
                colorCapable = colorCapable,
                source       = PrinterSource.MANUAL,
                model        = "Canon MF8030Cn"
            )
            PrinterStore.add(this, newPrinter)
            Log.i(TAG, "[add-printer] added ip=$ip")
            Toast.makeText(this, "Tiskárna uložena.", Toast.LENGTH_SHORT).show()
        }

        clearForm()
        refreshPrinterList()
    }

    private fun doTestConnection() {
        val ip = etIp.text.toString().trim()
        if (!isValidAddress(ip)) {
            Toast.makeText(this, "Zadejte platnou IP adresu.", Toast.LENGTH_SHORT).show()
            return
        }
        val port = parsePort(etPort.text.toString())
        tvConnStatus.text = "Testuji spojení…"
        Log.i(TAG, "[add-printer] testConnection ip=$ip port=$port")

        Thread {
            val reachable = try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(ip, port), 3000)
                    true
                }
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                tvConnStatus.text = if (reachable) {
                    "Dostupné ✓"
                } else {
                    "Nedostupné ✗ (lze přesto uložit)"
                }
                Log.i(TAG, "[add-printer] testConnection result reachable=$reachable")
            }
        }.start()
    }

    private fun doScanNetwork(density: Float) {
        fun dpToPx(dp: Int) = (dp * density + 0.5f).toInt()

        // Clear previous discovery results
        llDiscoveryResults.removeAllViews()
        tvDiscoveryNote.text = "Hledám tiskárny v síti…"
        stopDiscovery()

        val engine = DiscoveryEngine(this)
        discoveryEngine = engine

        engine.start { found ->
            runOnUiThread {
                Log.i(TAG, "[add-printer] discovered ip=${found.ip}")

                // Row: "name — ip:port"  +  "Předvyplnit" button
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dpToPx(4), 0, dpToPx(4))
                }

                val tvFound = TextView(this).apply {
                    text = "${found.name} — ${found.ip}:${found.port}"
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }

                val btnFill = Button(this).apply {
                    text = "Předvyplnit"
                    textSize = 12f
                    minimumHeight = dpToPx(36)
                    setOnClickListener {
                        loadIntoForm(found)
                        Toast.makeText(this@AddPrinterActivity,
                            "Formulář předvyplněn — zkontrolujte a stiskněte Uložit.",
                            Toast.LENGTH_SHORT).show()
                    }
                }

                row.addView(tvFound)
                row.addView(btnFill)
                llDiscoveryResults.addView(row)
            }
        }

        // Stop discovery after ~5 s
        mainHandler.postDelayed({
            stopDiscovery()
            runOnUiThread {
                val count = llDiscoveryResults.childCount
                tvDiscoveryNote.text = if (count == 0) {
                    "Žádné tiskárny nenalezeny. Tiskárna nedostupná v síti může být stále uložena ručně."
                } else {
                    "Skenování dokončeno ($count nalezeno). Nedostupné tiskárny lze přesto uložit ručně."
                }
            }
        }, 5_000L)
    }

    // -------------------------------------------------------------------------
    // Printer list
    // -------------------------------------------------------------------------

    private fun refreshPrinterList() {
        llPrinterList.removeAllViews()
        val density = resources.displayMetrics.density
        fun dpToPx(dp: Int) = (dp * density + 0.5f).toInt()

        val printers = PrinterStore.loadManual(this)
        Log.i(TAG, "[add-printer] refreshPrinterList count=${printers.size}")

        if (printers.isEmpty()) {
            val tvEmpty = TextView(this).apply {
                text = "Žádné uložené tiskárny."
                textSize = 13f
                setPadding(0, dpToPx(4), 0, dpToPx(4))
            }
            llPrinterList.addView(tvEmpty)
            return
        }

        for (p in printers) {
            val colorLabel = if (p.colorCapable) "barva" else "mono"

            // Printer row container
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFFF5F5F5.toInt())
                setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
                val lp = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, 0, 0, dpToPx(6))
                layoutParams = lp
            }

            val tvInfo = TextView(this).apply {
                text = "${p.name} — ${p.ip}:${p.port} [$colorLabel]"
                textSize = 13f
            }

            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, dpToPx(4), 0, 0)
                layoutParams = lp
            }

            val btnEdit = Button(this).apply {
                text = "Upravit"
                textSize = 12f
                minimumHeight = dpToPx(36)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
                    it.setMargins(0, 0, dpToPx(4), 0)
                }
                setOnClickListener {
                    loadIntoForm(p)
                    Toast.makeText(this@AddPrinterActivity,
                        "Tiskárna načtena do formuláře.", Toast.LENGTH_SHORT).show()
                }
            }

            val btnDelete = Button(this).apply {
                text = "Smazat"
                textSize = 12f
                minimumHeight = dpToPx(36)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    PrinterStore.remove(this@AddPrinterActivity, p.id)
                    Log.i(TAG, "[add-printer] removed id=${p.id}")
                    // If we were editing this one, clear the form
                    if (editingId == p.id) clearForm()
                    refreshPrinterList()
                }
            }

            btnRow.addView(btnEdit)
            btnRow.addView(btnDelete)
            row.addView(tvInfo)
            row.addView(btnRow)
            llPrinterList.addView(row)
        }
    }

    // -------------------------------------------------------------------------
    // Build UI programmatically — mirrors MainActivity.buildUi() conventions
    // -------------------------------------------------------------------------

    private fun buildUi() {
        val density = resources.displayMetrics.density

        fun dpToPx(dp: Int) = (dp * density + 0.5f).toInt()

        // Helper: full-width LayoutParams with vertical margins
        fun lp(topDp: Int = 8, bottomDp: Int = 8) =
            LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.setMargins(0, dpToPx(topDp), 0, dpToPx(bottomDp))
            }

        // ---- Title ----
        val tvTitle = TextView(this).apply {
            text = "Tiskárny UFR-II"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
        }

        // ---- Section: Add/Edit printer ----
        val tvFormHeader = TextView(this).apply {
            text = "Přidat / upravit tiskárnu"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
        }

        etName = EditText(this).apply {
            hint = "Název tiskárny"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }

        etIp = EditText(this).apply {
            hint = "IP adresa (např. 192.168.0.62)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine()
            setText(PrinterStore.DEFAULT_IP)
        }

        etPort = EditText(this).apply {
            hint = "Port"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            setText(PrinterStore.DEFAULT_PORT.toString())
        }

        // Color radio group
        rbColor = RadioButton(this).apply {
            text = "Barva"
            id = android.view.View.generateViewId()
            isChecked = true
        }
        rbMono = RadioButton(this).apply {
            text = "Černobíle"
            id = android.view.View.generateViewId()
        }
        rgColor = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(rbColor)
            addView(rbMono)
        }

        // Connection test status + note
        tvConnStatus = TextView(this).apply {
            text = ""
            textSize = 13f
        }
        val tvTestNote = TextView(this).apply {
            text = "Poznámka: tiskárna momentálně nedostupná v síti může být přesto uložena a použita, jakmile bude dostupná."
            textSize = 11f
            setTextColor(0xFF666666.toInt())
        }

        // Buttons: Uložit + Test spojení
        val btnSave = Button(this).apply {
            text = "Uložit"
            textSize = 14f
            isClickable = true
            isFocusable = true
            minimumHeight = dpToPx(48)
            setOnClickListener { doSave() }
        }

        val btnTest = Button(this).apply {
            text = "Test spojení"
            textSize = 14f
            isClickable = true
            isFocusable = true
            minimumHeight = dpToPx(48)
            setOnClickListener { doTestConnection() }
        }

        // Button row: Uložit | Test spojení
        val btnRowSaveTest = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp2 = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)
            layoutParams = lp2
        }
        btnSave.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
            it.setMargins(0, 0, dpToPx(4), 0)
        }
        btnTest.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        btnRowSaveTest.addView(btnSave)
        btnRowSaveTest.addView(btnTest)

        // ---- Section: Network discovery ----
        val tvDiscoveryHeader = TextView(this).apply {
            text = "Hledání tiskáren v síti"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
        }

        val btnScan = Button(this).apply {
            text = "Hledat aktivně (sken sítě)"
            textSize = 14f
            isClickable = true
            isFocusable = true
            minimumHeight = dpToPx(48)
            setOnClickListener { doScanNetwork(density) }
        }

        tvDiscoveryNote = TextView(this).apply {
            text = "Nalezené tiskárny se zobrazí níže. Klepnutím na Předvyplnit je načtete do formuláře."
            textSize = 11f
            setTextColor(0xFF666666.toInt())
        }

        llDiscoveryResults = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // ---- Section: Saved printers list ----
        val tvListHeader = TextView(this).apply {
            text = "Uložené tiskárny"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
        }

        llPrinterList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // ---- Inner vertical LinearLayout (content inside ScrollView) ----
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(16) + dpToPx(32), dpToPx(16), dpToPx(16))

            // Title
            addView(tvTitle, lp(topDp = 0, bottomDp = 12))

            // Form header
            addView(tvFormHeader, lp(topDp = 0, bottomDp = 6))

            // Form fields
            addView(etName,   lp(topDp = 4, bottomDp = 4))
            addView(etIp,     lp(topDp = 4, bottomDp = 4))
            addView(etPort,   lp(topDp = 4, bottomDp = 4))
            addView(rgColor,  lp(topDp = 4, bottomDp = 4))

            // Action buttons row
            addView(btnRowSaveTest, lp(topDp = 8, bottomDp = 4))

            // Connection status + note
            addView(tvConnStatus, lp(topDp = 2, bottomDp = 2))
            addView(tvTestNote,   lp(topDp = 2, bottomDp = 12))

            // Discovery section
            addView(tvDiscoveryHeader,  lp(topDp = 8, bottomDp = 6))
            addView(btnScan,            lp(topDp = 0, bottomDp = 4))
            addView(tvDiscoveryNote,    lp(topDp = 2, bottomDp = 4))
            addView(llDiscoveryResults, lp(topDp = 0, bottomDp = 12))

            // Saved printers section
            addView(tvListHeader,  lp(topDp = 8, bottomDp = 6))
            addView(llPrinterList, lp(topDp = 0, bottomDp = 0))
        }

        // ---- Root ScrollView ----
        val root = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -1)
            fitsSystemWindows = true
            addView(content, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        setContentView(root)
    }
}
