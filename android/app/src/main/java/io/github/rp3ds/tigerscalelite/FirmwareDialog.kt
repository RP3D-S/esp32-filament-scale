package io.github.rp3ds.tigerscalelite

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What the firmware screen can ask for. */
data class FwActions(
    val ui: FwUi,
    val check: () -> Unit,
    val update: (FirmwareRelease) -> Unit,
    val pickFile: () -> Unit,
    val reset: () -> Unit,
)

private val MUTED = Color(0xFF8A93A6)
private val GREEN = Color(0xFF3BA55D)
private val RED = Color(0xFFE24B4A)
private val ORANGE = Color(0xFFE8821E)

/**
 * Firmware: the version on the scale, a check against the published releases, an update (download, checksum,
 * send over Wi-Fi) and an install from a file on the phone. The scale only switches to the new image after it
 * matches the checksum, so a failure leaves the old firmware running.
 */
@Composable
fun FirmwareDialog(s: ScaleState, fw: FwActions, onClose: () -> Unit) {
    val ui = fw.ui
    val busy = ui is FwUi.Working || ui is FwUi.Checking
    fun close() { fw.reset(); onClose() }

    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text(stringResource(R.string.fw_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.fw_installed, s.firmware.ifBlank { "-" }), color = MUTED, fontSize = 13.sp)
                when (ui) {
                    FwUi.Idle -> {
                        OutlinedButton(onClick = fw.check, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.fw_check)) }
                        OutlinedButton(onClick = fw.pickFile, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.fw_from_file)) }
                    }
                    FwUi.Checking -> Status(stringResource(R.string.fw_checking), null)
                    is FwUi.UpToDate -> Text(stringResource(R.string.fw_latest), color = GREEN)
                    is FwUi.Available -> Text(stringResource(R.string.fw_available, ui.release.version), color = ORANGE)
                    is FwUi.Working -> {
                        Status(
                            stringResource(
                                when (ui.stage) {
                                    0 -> R.string.fw_downloading
                                    1 -> R.string.fw_arming
                                    2 -> R.string.fw_sending
                                    else -> R.string.fw_restarting
                                },
                            ),
                            ui.pct.takeIf { it >= 0 },
                        )
                        Text(stringResource(R.string.fw_keep_open), color = MUTED, fontSize = 12.sp)
                    }
                    is FwUi.Done -> Text(stringResource(R.string.fw_done, ui.version), color = GREEN)
                    is FwUi.Failed -> Text(stringResource(R.string.fw_failed, ui.reason), color = RED)
                }
            }
        },
        confirmButton = {
            when (ui) {
                is FwUi.Available -> Button(onClick = { fw.update(ui.release) }) { Text(stringResource(R.string.fw_update)) }
                is FwUi.UpToDate, is FwUi.Failed, is FwUi.Done -> TextButton(onClick = { fw.reset() }) { Text(stringResource(R.string.ok)) }
                else -> {}
            }
        },
        dismissButton = { TextButton(onClick = { close() }, enabled = !busy) { Text(stringResource(R.string.btn_close)) } },
    )
}

@Composable
private fun Status(text: String, pct: Int?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text)
        if (pct != null) {
            LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
            Text("$pct %", color = MUTED, fontSize = 12.sp)
        } else {
            CircularProgressIndicator(modifier = Modifier.padding(top = 4.dp))
        }
    }
}
