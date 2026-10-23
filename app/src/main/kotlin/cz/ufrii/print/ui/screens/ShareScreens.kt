package cz.ufrii.print.ui.screens

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import cz.ufrii.print.R
import cz.ufrii.print.SettingsShare
import cz.ufrii.print.ui.AppViewModel
import cz.ufrii.print.ui.components.HeaderBar
import cz.ufrii.print.ui.components.ListRow
import cz.ufrii.print.ui.components.OutlineButton
import cz.ufrii.print.ui.components.PrimaryButton
import cz.ufrii.print.ui.components.UfrCard
import cz.ufrii.print.ui.components.UfrSwitch
import cz.ufrii.print.ui.components.paperSourceName
import cz.ufrii.print.ui.theme.Ufr

/** QR with one printer's setup (addresses, capabilities, duplex calibration, defaults). */
@Composable
fun ShareSettingsScreen(vm: AppViewModel, id: String, onBack: () -> Unit) {
    val c = Ufr.colors
    val ctx = LocalContext.current
    val printer = vm.printer(id) ?: return
    val link = remember(printer) { SettingsShare.export(ctx, printer) }
    val qr = remember(link) { SettingsShare.qr(link, 720).asImageBitmap() }

    Column(
        Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
    ) {
        HeaderBar(stringResource(R.string.share_title), onBack = onBack)
        Text(printer.name, style = Ufr.type.titleM, color = c.ink)
        Spacer(Modifier.height(14.dp))
        // White quiet zone in both themes: scanners need dark-on-light.
        Image(qr, null, filterQuality = FilterQuality.None,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(12.dp))
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.share_how), style = Ufr.type.bodySmall, color = c.mut)
        Spacer(Modifier.height(18.dp))
        OutlineButton(stringResource(R.string.share_as_text), {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
            ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.share_title)))
        }, icon = Icons.Outlined.Share, strong = true)
    }
}

/** Reads a shared setup: QR scan (Google code scanner, no camera permission), pasted link, or an opened link. */
@Composable
fun ImportSettingsScreen(vm: AppViewModel, link: String?, onBack: () -> Unit, onDone: (String) -> Unit) {
    val c = Ufr.colors
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var imp by remember { mutableStateOf<SettingsShare.Import?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var defaults by remember { mutableStateOf(true) }
    var downloading by remember { mutableStateOf(false) }

    fun read(text: String?) {
        val r = text?.let { SettingsShare.parse(it) }
        imp = r
        error = if (r == null) ctx.getString(R.string.import_invalid) else null
    }
    LaunchedEffect(link) { if (link != null) { read(link); vm.consumeImportLink() } }

    Column(
        Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
    ) {
        HeaderBar(stringResource(R.string.import_title), onBack = onBack)
        val r = imp
        if (r == null) {
            Text(stringResource(R.string.import_body), style = Ufr.type.body, color = c.mut)
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(stringResource(if (downloading) R.string.import_module_downloading else R.string.import_scan), {
                    val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                    val scanner = GmsBarcodeScanning.getClient(ctx, opts)
                    fun scan() {
                        scanner.startScan()
                            .addOnSuccessListener { read(it.rawValue) }
                            .addOnFailureListener { error = ctx.getString(R.string.import_scan_failed, it.message ?: "") }
                    }
                    // The scanner UI is a Play services module fetched on demand: download it first
                    // (only once), then open the scanner by itself.
                    error = null
                    ModuleInstall.getClient(ctx).areModulesAvailable(scanner)
                        .addOnSuccessListener { r ->
                            if (r.areModulesAvailable()) scan() else {
                                downloading = true
                                ModuleInstall.getClient(ctx).installModules(
                                    ModuleInstallRequest.newBuilder().addApi(scanner).setListener { u ->
                                        if (u.installState == ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED) {
                                            downloading = false; scan()
                                        } else if (u.installState == ModuleInstallStatusUpdate.InstallState.STATE_FAILED ||
                                            u.installState == ModuleInstallStatusUpdate.InstallState.STATE_CANCELED) {
                                            downloading = false
                                            error = ctx.getString(R.string.import_module_failed)
                                        }
                                    }.build()
                                ).addOnSuccessListener { if (it.areModulesAlreadyInstalled()) { downloading = false; scan() } }
                                    .addOnFailureListener { downloading = false; error = ctx.getString(R.string.import_module_failed) }
                            }
                        }
                        .addOnFailureListener { scan() }
                }, icon = Icons.Outlined.QrCodeScanner, enabled = !downloading)
                OutlineButton(stringResource(R.string.import_paste), { read(clipboard.getText()?.text) },
                    icon = Icons.Outlined.ContentPaste, strong = true)
            }
            error?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, style = Ufr.type.bodySmall, color = c.errTx)
            }
            return@Column
        }

        val p = r.printer
        UfrCard {
            ListRow(p.name, subtitle = listOfNotNull(p.ip, p.ipv6, p.model).joinToString(" · "), subtitleMono = true)
            ListRow(stringResource(R.string.import_calibration),
                subtitle = if (r.duplex.isEmpty()) stringResource(R.string.import_none)
                    else r.duplex.keys.map { paperSourceName(it) }.joinToString())
            ListRow(stringResource(R.string.import_caps),
                subtitle = stringResource(if (p.capabilities != null || r.scanCaps != null) R.string.import_yes else R.string.import_none),
                divider = false)
        }
        Spacer(Modifier.height(12.dp))
        UfrCard {
            ListRow(stringResource(R.string.import_defaults),
                subtitle = listOfNotNull(r.source?.let { paperSourceName(it) }, r.scanArea?.let { areaPresetLabel(it) })
                    .joinToString(" · "),
                divider = false,
                trailing = { UfrSwitch(defaults, { defaults = it }, stringResource(R.string.import_defaults)) })
        }
        Spacer(Modifier.height(24.dp))
        PrimaryButton(stringResource(R.string.import_save), {
            val id = SettingsShare.apply(ctx, r, defaults)
            vm.reloadPrinters()
            onDone(id)
        })
    }
}
