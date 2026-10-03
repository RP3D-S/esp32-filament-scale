package io.github.rp3ds.filscale

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm: ScaleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    val s by vm.state.collectAsStateWithLifecycle()
                    ScaleScreen(
                        s = s,
                        onTare = vm::tare,
                        onCalibrate = vm::calibrate,
                        onServo = vm::setServo,
                        onHost = vm::setHost,
                        onSearch = vm::search,
                    )
                }
            }
        }
    }
}

private val Green = Color(0xFF4CAF50)
private val Amber = Color(0xFFFFB300)
private val Red = Color(0xFFE53935)
private val Grey = Color(0xFF616161)

@Composable
fun ScaleScreen(
    s: ScaleState,
    onTare: () -> Unit,
    onCalibrate: (Float) -> Unit,
    onServo: (Boolean) -> Unit,
    onHost: (String) -> Unit,
    onSearch: () -> Unit,
) {
    var showHost by remember { mutableStateOf(false) }
    var showCal by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        DeviceScreen(s)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onTare, enabled = s.connected, modifier = Modifier.weight(1f)) { Text("Tara") }
            OutlinedButton(onClick = { showCal = true }, enabled = s.connected, modifier = Modifier.weight(1f)) {
                Text("Calibrar")
            }
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Motor da bobine", fontWeight = FontWeight.Medium)
                Text("Roda a bobine até ler a tag", fontSize = 12.sp, color = Grey)
            }
            Switch(checked = s.servoEnabled, onCheckedChange = onServo, enabled = s.connected)
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Ligação", fontWeight = FontWeight.Medium)
            Text(
                when {
                    s.connected -> "Ligado a ${s.host}"
                    s.searching -> "A procurar a balança na rede…"
                    s.host.isBlank() -> "Sem balança configurada"
                    else -> "A tentar ligar a ${s.host}…"
                },
                fontSize = 13.sp,
            )
            if (s.firmware.isNotEmpty()) {
                Text("Firmware ${s.firmware} · Wi-Fi ${s.rssi} dBm · fator ${"%.2f".format(s.calibration)}",
                    fontSize = 12.sp, color = Grey)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSearch, enabled = !s.searching) { Text("Procurar") }
                OutlinedButton(onClick = { showHost = true }) { Text("IP manual") }
            }
        }

        s.message?.let { Text(it, color = Amber, fontSize = 13.sp) }
    }

    if (showHost) HostDialog(s.host, { showHost = false }) { onHost(it); showHost = false }
    if (showCal) CalibrateDialog({ showCal = false }) { onCalibrate(it); showCal = false }
}

/** The 480x320 "display" of the physical scale, redrawn on the phone. */
@Composable
private fun DeviceScreen(s: ScaleState) {
    val statusColor = when {
        !s.connected -> Grey
        !s.scaleOk -> Red
        s.status == "stable" -> Green
        s.status == "scanning" -> Amber
        else -> Grey
    }
    val statusText = when {
        !s.connected -> "Sem ligação"
        !s.scaleOk -> "Célula de carga sem resposta"
        s.status == "stable" -> "Estável"
        s.status == "scanning" -> "A ler a tag…"
        else -> "Coloca uma bobine"
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(480f / 320f)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black)
            .border(BorderStroke(3.dp, statusColor), RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Row(Modifier.align(Alignment.TopStart), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Led("D", s.readerRight)
            Led("E", s.readerLeft)
        }
        Text(
            if (s.connected) "${s.rssi} dBm" else "",
            modifier = Modifier.align(Alignment.TopEnd),
            color = Grey, fontSize = 12.sp,
        )
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (s.connected) s.weight.toString() else "--",
                    fontSize = 84.sp, fontWeight = FontWeight.Bold, color = Color.White,
                )
                Text(" g", fontSize = 28.sp, color = Grey, modifier = Modifier.padding(bottom = 14.dp))
            }
            Text(statusText, color = statusColor, fontSize = 16.sp)
        }
        Text(
            s.uid.ifEmpty { "sem tag" },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            textAlign = TextAlign.Center,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            color = if (s.uid.isEmpty()) Grey else Color.White,
        )
    }
}

@Composable
private fun Led(label: String, on: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (on) Green else Red))
        Text(label, color = Grey, fontSize = 12.sp)
    }
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
                Text("1. Retira tudo da balança e faz a tara.\n2. Coloca um peso conhecido.\n3. Indica o peso e confirma.",
                    fontSize = 13.sp)
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
