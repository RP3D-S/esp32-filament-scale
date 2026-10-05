package io.github.rp3ds.tigerscalelite

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// Same palette as the main screen (original firmware's LVCOL_*).
private val BG = Color(0xFF0B0E14)
private val CARD = Color(0xFF141821)
private val BORDER = Color(0xFF2E3646)
private val TEXT = Color(0xFFFFFFFF)
private val MUTED = Color(0xFF8A93A6)
private val ACCENT = Color(0xFF2F7FFF)
private val RED = Color(0xFFE24B4A)
private val GREEN = Color(0xFF3BA55D)

private const val RF_MAX = 4   // levels 0..4, as on the original

/**
 * Redraw of the original scale's Hardware/RFID test screen (runHardwareTest): header with
 * back, RF power stepper, reader pill, UID box, and a Scan/Stop button. This scale has one
 * reader, so the second UID box shows the PN532 firmware version instead.
 */
@Composable
fun RfidTestScreen(
    s: ScaleState,
    onBack: () -> Unit,
    onPower: (Int) -> Unit,
    onScan: (Boolean) -> Unit,
) {
    // Leaving the screen must not leave the scale polling in test mode.
    DisposableEffect(Unit) { onDispose { onScan(false) } }

    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(BG), contentAlignment = Alignment.TopCenter) {
            BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(480f / 320f).background(BG)) {
                val k = maxWidth.value / 480f
                fun d(v: Float): Dp = (v * k).dp
                fun f(v: Float) = (v * k).sp

                // ---- Header (480x48) with back + title, divider at y=47 ----
                Box(
                    Modifier.size(d(480f), d(48f)).clickable { onBack() },
                ) {
                    Text("‹", color = TEXT, fontSize = f(34f), modifier = Modifier.offset(d(16f), d(2f)))
                    Text("RFID", color = TEXT, fontSize = f(20f), modifier = Modifier.offset(d(56f), d(12f)))
                    Box(Modifier.offset(0.dp, d(47f)).size(d(480f), d(1f)).background(BORDER))
                }

                // ---- Power stepper ----
                Text(stringResource(R.string.rfid_power), color = MUTED, fontSize = f(14f), modifier = Modifier.offset(d(6f), d(52f)))
                StepButton("-", enabled = s.rfPow > 0, k = k, x = 6f, y = 68f) { onPower(s.rfPow - 1) }
                Box(
                    Modifier
                        .offset(d(60f), d(68f)).size(d(64f), d(44f))
                        .clip(RoundedCornerShape(d(10f))).background(CARD)
                        .border(d(1f), BORDER, RoundedCornerShape(d(10f))),
                    contentAlignment = Alignment.Center,
                ) { Text("${s.rfPow}/$RF_MAX", color = TEXT, fontSize = f(20f)) }
                StepButton("+", enabled = s.rfPow < RF_MAX, k = k, x = 130f, y = 68f) { onPower(s.rfPow + 1) }

                // ---- Reader pill (one reader on this scale) ----
                Text(
                    stringResource(R.string.rfid_readers), color = MUTED, fontSize = f(14f), textAlign = TextAlign.End,
                    modifier = Modifier.offset(d(480f - 6f - 130f), d(52f)).width(d(130f)),
                )
                Box(
                    Modifier
                        .offset(d(480f - 6f - 44f), d(68f)).size(d(44f))
                        .clip(CircleShape).background(if (s.readerOk) ACCENT else CARD)
                        .border(d(1f), BORDER, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("1", color = TEXT, fontSize = f(16f)) }

                // ---- UID box + PN532 version box ----
                InfoBox(k, x = 6f, title = stringResource(R.string.rfid_reader), value = s.rfUid.ifEmpty { "-" }, green = s.rfUid.isNotEmpty())
                InfoBox(k, x = 246f, title = "PN532", value = s.rfVer.ifEmpty { "-" }, green = false)

                // ---- Scan / Stop ----
                Box(
                    Modifier
                        .offset(d(90f), d(248f)).size(d(300f), d(66f))
                        .clip(RoundedCornerShape(d(12f)))
                        .background(if (s.rfTest) RED else CARD)
                        .border(d(1f), BORDER, RoundedCornerShape(d(12f)))
                        .clickable { onScan(!s.rfTest) },
                    contentAlignment = Alignment.Center,
                ) { Text(stringResource(if (s.rfTest) R.string.stop else R.string.scan), color = TEXT, fontSize = f(16f)) }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxWithConstraintsScope.StepButton(
    label: String, enabled: Boolean, k: Float, x: Float, y: Float, onClick: () -> Unit,
) {
    Box(
        Modifier
            .offset((x * k).dp, (y * k).dp).size((48f * k).dp, (44f * k).dp)
            .clip(RoundedCornerShape((10f * k).dp)).background(CARD)
            .border((1f * k).dp, BORDER, RoundedCornerShape((10f * k).dp))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (enabled) TEXT else BORDER, fontSize = (20f * k).sp) }
}

@Composable
private fun androidx.compose.foundation.layout.BoxWithConstraintsScope.InfoBox(
    k: Float, x: Float, title: String, value: String, green: Boolean,
) {
    Box(
        Modifier
            .offset((x * k).dp, (148f * k).dp).size((228f * k).dp, (64f * k).dp)
            .clip(RoundedCornerShape((8f * k).dp)).background(CARD)
            .border((1f * k).dp, BORDER, RoundedCornerShape((8f * k).dp)),
    ) {
        Text(title, color = MUTED, fontSize = (14f * k).sp, modifier = Modifier.offset((10f * k).dp, (8f * k).dp))
        Text(
            value, color = if (green) GREEN else MUTED, fontSize = (16f * k).sp, maxLines = 1,
            modifier = Modifier.offset((10f * k).dp, (30f * k).dp),
        )
    }
}
