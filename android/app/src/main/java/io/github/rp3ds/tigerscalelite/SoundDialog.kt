package io.github.rp3ds.tigerscalelite

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What the sound screen can ask of the scale. */
data class SoundActions(
    val set: (pin: Int?, level: Int?) -> Unit,
    val test: () -> Unit,
)

/** The GPIOs the firmware accepts for the buzzer (firmware/src/buzzer.cpp: free outputs on a WROOM-32). */
private val BUZZER_PINS = listOf(26, 25, 13, 14, 18, 19, 21, 22, 23, 4)

private val MUTED = Color(0xFF8A93A6)

/** Text for a volume level as the Settings row shows it. */
@Composable
fun volumeName(level: Int): String = stringResource(
    when (level) {
        0 -> R.string.snd_off
        1 -> R.string.snd_low
        2 -> R.string.snd_medium
        3 -> R.string.snd_high
        else -> R.string.snd_unknown
    },
)

/**
 * The scale's buzzer: volume, the GPIO it is wired to, and a test. The original scale had a speaker with a
 * Volume row in Settings; this is its counterpart. The scale remembers both settings.
 */
@Composable
fun SoundDialog(s: ScaleState, snd: SoundActions, onClose: () -> Unit) {
    val enabled = s.connected
    val pinKnown = s.bzPin != -9
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.snd_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.snd_volume), color = MUTED, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    for (level in 0..3) {
                        FilterChip(
                            selected = s.bzLvl == level, enabled = enabled,
                            onClick = { snd.set(null, level) },
                            label = { Text(volumeName(level)) },
                        )
                    }
                }
                Text(stringResource(R.string.snd_pin), color = MUTED, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    FilterChip(
                        selected = pinKnown && s.bzPin == -1, enabled = enabled,
                        onClick = { snd.set(-1, null) },
                        label = { Text(stringResource(R.string.snd_none)) },
                    )
                    for (pin in BUZZER_PINS) {
                        FilterChip(
                            selected = s.bzPin == pin, enabled = enabled,
                            onClick = { snd.set(pin, null) },
                            label = { Text("$pin") },
                        )
                    }
                }
                OutlinedButton(
                    onClick = snd.test,
                    enabled = enabled && s.bzLvl > 0 && s.bzPin >= 0,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.snd_test)) }
                Text(stringResource(R.string.snd_hint), color = MUTED, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.btn_close)) } },
    )
}
