package io.github.rp3ds.filscale

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm: ScaleViewModel by viewModels()

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) vm.startBle()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val needed = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            vm.startBle()
        } else {
            permissionRequest.launch(needed)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF0B0E14)) {
                    val s by vm.state.collectAsStateWithLifecycle()
                    ScaleScreen(
                        s = s,
                        onTare = vm::tare,
                        onCalibrate = vm::calibrate,
                        onHost = vm::setHost,
                        onSearch = vm::search,
                        onScanWifi = vm::scanWifi,
                        onWifi = vm::configureWifi,
                    )
                }
            }
        }
    }
}

@Composable
fun ScaleScreen(
    s: ScaleState,
    onTare: () -> Unit,
    onCalibrate: (Float) -> Unit,
    onHost: (String) -> Unit,
    onSearch: () -> Unit,
    onScanWifi: () -> Unit,
    onWifi: (String, String) -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ScaleDisplay(s, onTare = onTare, onSettings = { showSettings = true })
        s.message?.let {
            Text(it, color = Color(0xFFF2B705), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
        }
    }

    if (showSettings) {
        SettingsDialog(s, { showSettings = false }, onCalibrate, onHost, onSearch, onScanWifi, onWifi)
    }
}

@Composable
private fun SettingsDialog(
    s: ScaleState,
    onDismiss: () -> Unit,
    onCalibrate: (Float) -> Unit,
    onHost: (String) -> Unit,
    onSearch: () -> Unit,
    onScanWifi: () -> Unit,
    onWifi: (String, String) -> Unit,
) {
    var showCal by remember { mutableStateOf(false) }
    var showHost by remember { mutableStateOf(false) }
    var showWifi by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Definições") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Bluetooth: ", fontWeight = FontWeight.Medium)
                    Text(if (s.bleLinked) "ligado" else "à procura da balança…")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Wi-Fi: ", fontWeight = FontWeight.Medium)
                    Text(
                        when {
                            s.wifiLinked -> "ligado (${s.host})"
                            s.wifiState == 2 -> "balança em ${s.ssid}, a ligar…"
                            s.wifiState == 1 -> "a ligar a rede…"
                            s.wifiState == 3 -> "falhou, verifica a palavra-passe"
                            else -> "sem rede configurada"
                        },
                    )
                }
                Text(
                    "Leitor NFC: ${if (s.readerOk) "OK" else "não detetado"} · " +
                        "Célula de carga: ${if (s.scaleOk) "OK" else "sem resposta"}",
                    fontSize = 12.sp, color = Color(0xFF8A93A6),
                )
                if (s.firmware.isNotEmpty()) {
                    Text(
                        "Firmware ${s.firmware} · fator ${"%.2f".format(s.calibration)}",
                        fontSize = 12.sp, color = Color(0xFF8A93A6),
                    )
                }
                OutlinedButton(
                    onClick = { onScanWifi(); showWifi = true },
                    enabled = s.bleLinked,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Configurar Wi-Fi da balança") }
                OutlinedButton(onClick = { showCal = true }, enabled = s.connected, modifier = Modifier.fillMaxWidth()) {
                    Text("Calibrar")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onSearch, enabled = !s.searching) { Text("Procurar") }
                    OutlinedButton(onClick = { showHost = true }) { Text("IP manual") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )

    if (showWifi) WifiDialog(s, { showWifi = false }, onScanWifi, onWifi)
    if (showHost) HostDialog(s.host, { showHost = false }) { onHost(it); showHost = false }
    if (showCal) CalibrateDialog({ showCal = false }) { onCalibrate(it); showCal = false }
}

/** Wi-Fi provisioning over BLE: pick a network the scale can see, type the password. */
@Composable
private fun WifiDialog(
    s: ScaleState,
    onDismiss: () -> Unit,
    onScan: () -> Unit,
    onOk: (String, String) -> Unit,
) {
    var ssid by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wi-Fi da balança") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Redes que a balança vê (2,4 GHz):", fontSize = 13.sp)
                if (s.networks.isEmpty()) Text("A procurar…", fontSize = 13.sp, color = Color(0xFF8A93A6))
                s.networks.forEach { n ->
                    Text(
                        n,
                        Modifier
                            .fillMaxWidth()
                            .background(if (n == ssid) Color(0xFF2F7FFF) else Color(0xFF141821))
                            .clickable { ssid = n }
                            .padding(10.dp),
                    )
                }
                TextButton(onClick = onScan) { Text("Procurar de novo") }
                OutlinedTextField(
                    value = ssid, onValueChange = { ssid = it }, singleLine = true,
                    label = { Text("Nome da rede") },
                )
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it }, singleLine = true,
                    label = { Text("Palavra-passe") },
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (s.wifiNeedsBoot) {
                    Text(
                        "A balança já tem Wi-Fi. Carrega no botão BOOT do ESP32 e tenta outra vez (30 s).",
                        color = Color(0xFFE8821E), fontSize = 13.sp,
                    )
                }
                when (s.wifiState) {
                    1 -> Text("A ligar…", color = Color(0xFF2F7FFF))
                    2 -> Text("Ligado a ${s.ssid} (${s.ip})", color = Color(0xFF3BA55D))
                    3 -> Text("Não foi possível ligar. Verifica a palavra-passe.", color = Color(0xFFE24B4A))
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (ssid.isNotBlank()) onOk(ssid, pass) }) { Text("Ligar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}

@Composable
private fun HostDialog(current: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Endereço da balança") },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                label = { Text("IP ou nome (ex.: 192.168.1.50)") },
            )
        },
        confirmButton = { TextButton(onClick = { onOk(text) }) { Text("Ligar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun CalibrateDialog(onDismiss: () -> Unit, onOk: (Float) -> Unit) {
    var text by remember { mutableStateOf("500") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calibrar") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "1. Retira tudo da balança e faz a tara.\n2. Coloca um peso conhecido.\n3. Indica o peso e confirma.",
                    fontSize = 13.sp,
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    label = { Text("Peso conhecido (g)") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { text.replace(',', '.').toFloatOrNull()?.takeIf { it > 0 }?.let(onOk) }) {
                Text("Calibrar")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
