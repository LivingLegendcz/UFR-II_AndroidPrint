package cz.ufrii.print.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.WifiFind
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cz.ufrii.print.R
import cz.ufrii.print.status.PrinterCapabilities
import cz.ufrii.print.ui.AppViewModel
import cz.ufrii.print.ui.ProbeState
import cz.ufrii.print.ui.components.HeaderBar
import cz.ufrii.print.ui.components.OutlineButton
import cz.ufrii.print.ui.components.Overline
import cz.ufrii.print.ui.components.PrimaryButton
import cz.ufrii.print.ui.components.Spinner
import cz.ufrii.print.ui.theme.Ufr

private val IPV4 = Regex("^((25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1?\\d?\\d)$")
private val HOSTNAME = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]{0,252}[A-Za-z0-9])?$")

fun isValidHost(s: String) = IPV4.matches(s) || (HOSTNAME.matches(s) && s.any { it.isLetter() })

/**
 * Přidat / upravit tiskárnu (§5 obrazovka 14): Název, IP, Port → "Otestovat spojení"
 * (TCP 9100 + SNMP capability probe) → Uložit (allowed even without a test).
 */
@Composable
fun AddPrinterScreen(
    vm: AppViewModel,
    editId: String?,
    prefillIp: String?,
    prefillName: String?,
    onDone: () -> Unit
) {
    val c = Ufr.colors
    val existing = vm.printer(editId)
    var name by remember { mutableStateOf(existing?.name ?: prefillName ?: "") }
    var ip by remember { mutableStateOf(existing?.ip ?: prefillIp ?: "") }
    var port by remember { mutableStateOf((existing?.port ?: 9100).toString()) }
    val probe by vm.probe.collectAsState()

    DisposableEffect(Unit) { onDispose { vm.resetProbe() } }

    val ipValid = isValidHost(ip.trim())
    val portInt = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val caps: PrinterCapabilities? = (probe as? ProbeState.Reachable)?.caps ?: existing?.capabilities

    Column(
        Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().imePadding()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp)
    ) {
        HeaderBar(
            stringResource(if (existing != null) R.string.add_title_edit else R.string.add_title),
            onBack = onDone, backIcon = Icons.Outlined.Close
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Field(stringResource(R.string.field_name), name, { name = it }, placeholder = "Canon MF8030Cn")
            Field(stringResource(R.string.field_ip), ip, { ip = it; vm.resetProbe() }, placeholder = "192.168.0.62",
                mono = true, keyboard = KeyboardType.Uri,
                error = if (ip.isNotBlank() && !ipValid) stringResource(R.string.field_ip_invalid) else null)
            Field(stringResource(R.string.field_port), port, { port = it.filter(Char::isDigit).take(5); vm.resetProbe() },
                placeholder = "9100", mono = true, keyboard = KeyboardType.Number)

            ProbeResult(probe, caps)

            OutlineButton(
                stringResource(R.string.add_test), { vm.testConnection(ip.trim(), portInt ?: 9100) },
                icon = Icons.Outlined.WifiFind, strong = true,
                enabled = ipValid && portInt != null && probe != ProbeState.Testing
            )
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            stringResource(R.string.action_save),
            onClick = {
                val p = AppViewModel.newPrinter(
                    name = name.ifBlank { caps?.model ?: ip.trim() },
                    ip = ip.trim(), port = portInt ?: 9100, caps = caps, id = existing?.id
                )
                vm.savePrinter(p)
                onDone()
            },
            enabled = ipValid && portInt != null
        )
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    mono: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    error: String? = null
) {
    val c = Ufr.colors
    Column {
        Overline(label)
        Spacer(Modifier.height(6.dp))
        val shape = RoundedCornerShape(14.dp)
        val style = (if (mono) Ufr.type.data.copy(fontSize = Ufr.type.label.fontSize) else Ufr.type.label).copy(color = c.ink)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(c.ink),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.fillMaxWidth().clip(shape).background(c.chip)
                .border(1.dp, if (error != null) c.err else c.line, shape)
                .padding(horizontal = 16.dp, vertical = 15.dp),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = c.dim))
                inner()
            }
        )
        if (error != null) {
            Spacer(Modifier.height(4.dp))
            Text(error, style = Ufr.type.labelSmall.copy(fontWeight = FontWeight.Normal), color = c.err)
        }
    }
}

@Composable
private fun ProbeResult(probe: ProbeState, caps: PrinterCapabilities?) {
    val c = Ufr.colors
    when (probe) {
        ProbeState.Idle -> if (caps != null) CapabilitySummary(caps)
        ProbeState.Testing -> Row(verticalAlignment = Alignment.CenterVertically) {
            Spinner(size = 16.dp)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.add_testing), style = Ufr.type.bodySmall, color = c.mut)
        }
        ProbeState.Unreachable -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.WifiOff, null, tint = c.err, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.add_unreachable), style = Ufr.type.bodySmall, color = c.err)
        }
        is ProbeState.Reachable -> Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle, null, tint = c.ok, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.add_reachable), style = Ufr.type.bodySmall, color = c.ok)
            }
            Spacer(Modifier.height(10.dp))
            CapabilitySummary(probe.caps)
        }
    }
}
