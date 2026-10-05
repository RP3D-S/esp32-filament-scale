package io.github.rp3ds.tigerscalelite

import android.app.LocaleManager
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

// Same palette as the original's LVCOL_*.
private val BG = Color(0xFF0B0E14)
private val CARD = Color(0xFF141821)
private val BORDER = Color(0xFF2E3646)
private val TEXT = Color(0xFFFFFFFF)
private val MUTED = Color(0xFF8A93A6)
private val FAINT = Color(0xFF5A6478)
private val ACCENT = Color(0xFF2F7FFF)
private val RED = Color(0xFFE24B4A)
private val GREEN = Color(0xFF3BA55D)
private val ORANGE = Color(0xFFE8821E)

/** The app's languages, same nine as the scale; tag "" (system default) is added by the picker. */
val APP_LANGUAGES = listOf(
    "en" to "English",
    "pt-PT" to "Português (Portugal)",
    "pt-BR" to "Português (Brasil)",
    "fr" to "Français",
    "es" to "Español",
    "de" to "Deutsch",
    "it" to "Italiano",
    "pl" to "Polski",
    "zh" to "中文",
)

/** What each row of the Settings list opens. */
data class SettingsActions(
    val onBack: () -> Unit,
    val onScale: () -> Unit,
    val onWifi: () -> Unit,
    val onAccount: () -> Unit,
    val onWizard: () -> Unit,
    val onManual: () -> Unit,
    val onLanguage: () -> Unit,
    val onRfid: () -> Unit,
)

private enum class RowIcon { BLUETOOTH, WIFI, USER, TARGET, PENCIL, GLOBE, CHIP, REFRESH, TRASH }

/**
 * The original scale's Settings menu (runSettingsMenu) as a list of rows: icon, name, the current
 * value on the right (so the list answers most questions without opening anything) and a chevron
 * when the row opens something. Volume, screen, power-off and live view are left out on purpose:
 * this scale has no speaker, no panel and no battery. Restart is amber, the factory reset is red and
 * sits last, behind a confirmation.
 */
@Composable
fun SettingsScreen(s: ScaleState, a: SettingsActions, onRestart: () -> Unit, onFactoryReset: () -> Unit) {
    var confirm by remember { mutableStateOf(0) }       // 0 none, 1 restart, 2 factory reset
    val ctx = LocalContext.current
    val langTag = if (Build.VERSION.SDK_INT >= 33) {
        ctx.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
    } else ""
    val langName = APP_LANGUAGES.firstOrNull { it.first == langTag }?.second
        ?: APP_LANGUAGES.firstOrNull { langTag.isNotEmpty() && langTag.startsWith(it.first.substringBefore('-')) && !it.first.contains('-') }?.second
        ?: stringResource(R.string.language_system)

    Dialog(onDismissRequest = a.onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(BG)) {
            // ---- Header: back + title, divider ----
            Column(Modifier.fillMaxWidth().clickable { a.onBack() }) {
                Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("‹", color = TEXT, fontSize = 32.sp)
                    Spacer(Modifier.size(14.dp))
                    Text(stringResource(R.string.settings), color = TEXT, fontSize = 20.sp)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(BORDER))
            }

            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val ble = s.bleLinked

                SettingRow(
                    RowIcon.BLUETOOTH, if (s.bleLinked) ACCENT else MUTED, stringResource(R.string.row_scale),
                    if (s.noScale) stringResource(R.string.scale_none) else s.scaleName, onClick = a.onScale,
                )
                val wifiOn = s.wifiState == 2
                SettingRow(
                    RowIcon.WIFI, if (wifiOn) GREEN else RED, "WiFi",
                    if (wifiOn) s.ssid else stringResource(R.string.no_wifi), enabled = ble, onClick = a.onWifi,
                )
                val accountValue = when (s.fbState) {
                    2 -> s.fbName.ifBlank { "OK" }
                    1 -> stringResource(R.string.connecting)
                    3 -> stringResource(R.string.badge_error)
                    else -> stringResource(R.string.badge_no_account)
                }
                SettingRow(
                    RowIcon.USER, when (s.fbState) { 2 -> GREEN; 1 -> ACCENT; else -> RED },
                    stringResource(R.string.row_account), accountValue, enabled = ble, onClick = a.onAccount,
                )
                SettingRow(
                    RowIcon.TARGET, TEXT, stringResource(R.string.cal_wizard),
                    if (s.calibration > 0) String.format(Locale.US, "%.2f", s.calibration) else "",
                    enabled = s.connected, onClick = a.onWizard,
                )
                SettingRow(RowIcon.PENCIL, TEXT, stringResource(R.string.cal_manual), enabled = s.connected, onClick = a.onManual)
                if (Build.VERSION.SDK_INT >= 33) {
                    SettingRow(RowIcon.GLOBE, TEXT, stringResource(R.string.language), langName, onClick = a.onLanguage)
                }
                SettingRow(RowIcon.CHIP, TEXT, "RFID", enabled = s.connected, onClick = a.onRfid)
                // Informational: there is no over-the-air update on this scale, so no chevron (an inert row
                // must not advertise a destination that does not exist).
                SettingRow(RowIcon.REFRESH, TEXT, stringResource(R.string.row_firmware), s.firmware.ifBlank { "-" }, onClick = null)
                SettingRow(RowIcon.REFRESH, ORANGE, stringResource(R.string.reboot), enabled = ble, onClick = { confirm = 1 })
                // The one destructive row sits last, red.
                SettingRow(RowIcon.TRASH, RED, stringResource(R.string.factory_reset), enabled = ble, onClick = { confirm = 2 })
            }
        }
    }

    if (confirm != 0) {
        ConfirmDialog(
            factory = confirm == 2,
            onCancel = { confirm = 0 },
            onConfirm = { if (confirm == 2) onFactoryReset() else onRestart() },
            onDone = { confirm = 0 },
        )
    }
}

/**
 * The original's lvglConfirm: a question and Cancel / Validate. The factory reset is the one action that
 * erases the owner's setup, so its confirm button only fires after a 3 s press-and-hold.
 */
@Composable
private fun ConfirmDialog(factory: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit, onDone: () -> Unit) {
    var sent by remember { mutableStateOf(false) }
    // The scale restarts after the command, which drops the link: close once it had time to act.
    LaunchedEffect(sent) {
        if (sent) {
            delay(4_000)
            onDone()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!sent) onCancel() },
        containerColor = CARD,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(if (factory) R.string.factory_reset_q else R.string.reboot_q),
                    color = TEXT, fontSize = 17.sp,
                )
                if (sent) Text(stringResource(R.string.rebooting), color = MUTED, fontSize = 14.sp)
            }
        },
        confirmButton = {
            if (factory) {
                HoldButton(stringResource(R.string.factory_hold), RED, FACTORY_HOLD_MS, enabled = !sent) { sent = true; onConfirm() }
            } else {
                Button(
                    onClick = { sent = true; onConfirm() },
                    enabled = !sent,
                    colors = ButtonDefaults.buttonColors(containerColor = ACCENT, contentColor = TEXT),
                ) { Text(stringResource(R.string.confirm)) }
            }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = !sent) { Text(stringResource(R.string.cancel), color = MUTED) } },
    )
}

private const val FACTORY_HOLD_MS = 3_000

/** Fires [onComplete] only after being pressed for [holdMs] without letting go; the bar fills while held and empties on release. */
@Composable
private fun HoldButton(label: String, color: Color, holdMs: Int, enabled: Boolean, onComplete: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .width(180.dp).height(40.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape).background(CARD).border(1.dp, color, shape)
            .pointerInput(enabled) {
                detectTapGestures(onPress = {
                    if (!enabled) return@detectTapGestures
                    val job = scope.launch {
                        progress.animateTo(1f, tween(holdMs, easing = LinearEasing))
                        onComplete()
                    }
                    tryAwaitRelease()
                    if (progress.value < 1f) {            // let go too early: nothing happens
                        job.cancel()
                        scope.launch { progress.snapTo(0f) }
                    }
                })
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.value).background(color))
        Text(label, color = TEXT, fontSize = 14.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
}

@Composable
private fun SettingRow(
    icon: RowIcon, iconColor: Color, name: String, value: String = "", enabled: Boolean = true, onClick: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth().height(52.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape).background(CARD).border(1.dp, BORDER, shape)
            .then(if (onClick != null && enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon26(icon, iconColor)
        Spacer(Modifier.size(12.dp))
        Text(name, color = TEXT, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (value.isNotEmpty()) {
            Text(value, color = MUTED, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
        }
        if (onClick != null) Text("›", color = MUTED, fontSize = 26.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

/** 26 x 26 icon area, drawn with the same primitives as the original (rings, bars, pill outlines). */
@Composable
private fun Icon26(icon: RowIcon, c: Color) {
    Canvas(Modifier.size(26.dp)) {
        val u = size.width / 26f
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        fun ring(x: Float, y: Float, d: Float, w: Float = 2f) =
            drawCircle(c, radius = (d / 2f) * u - w * u / 2f, center = p(x + d / 2f, y + d / 2f), style = Stroke(w * u))
        fun bar(x: Float, y: Float, w: Float, h: Float) = drawRect(c, p(x, y), Size(w * u, h * u))
        fun pill(x: Float, y: Float, w: Float, h: Float, r: Float) =
            drawRoundRect(c, p(x + 1f, y + 1f), Size((w - 2f) * u, (h - 2f) * u), androidx.compose.ui.geometry.CornerRadius(r * u), style = Stroke(2f * u))
        when (icon) {
            RowIcon.USER -> {
                drawCircle(c, 4.5f * u, p(13.5f, 6.5f))
                clipRect(0f, 14f * u, size.width, size.height) { drawCircle(c, 10f * u, p(13f, 25f)) }
            }
            RowIcon.TARGET -> {
                ring(3f, 3f, 20f); ring(9f, 9f, 8f)
                bar(12f, 9f, 2f, 8f); bar(9f, 12f, 8f, 2f)
                bar(10f, 0f, 5f, 3f); bar(10f, 23f, 5f, 3f); bar(0f, 10f, 3f, 5f); bar(22f, 10f, 3f, 5f)
            }
            RowIcon.GLOBE -> { ring(2f, 2f, 22f); bar(2f, 12f, 22f, 2f); pill(8f, 2f, 10f, 22f, 5f) }
            RowIcon.CHIP -> {
                pill(7f, 6f, 12f, 14f, 2f)
                drawCircle(c, 2f * u, p(13f, 13f))
                for (y in listOf(8f, 12f, 16f)) { bar(1f, y, 5f, 2f); bar(20f, y, 5f, 2f) }
            }
            RowIcon.WIFI -> {
                val st = Stroke(2.2f * u, cap = StrokeCap.Round)
                for (r in listOf(6f, 11f, 16f)) {
                    drawArc(c, 225f, 90f, false, p(13f - r, 21f - r), Size(2f * r * u, 2f * r * u), style = st)
                }
                drawCircle(c, 2f * u, p(13f, 21f))
            }
            RowIcon.BLUETOOTH -> {
                val path = Path().apply {
                    moveTo(7f * u, 8f * u); lineTo(19f * u, 18f * u); lineTo(13f * u, 24f * u)
                    lineTo(13f * u, 2f * u); lineTo(19f * u, 8f * u); lineTo(7f * u, 18f * u)
                }
                drawPath(path, c, style = Stroke(2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            RowIcon.PENCIL -> {
                drawLine(c, p(7f, 19f), p(19f, 7f), 4f * u, StrokeCap.Round)
                drawLine(c, p(4f, 22f), p(8f, 21f), 2f * u, StrokeCap.Round)
                drawLine(c, p(4f, 22f), p(5f, 18f), 2f * u, StrokeCap.Round)
            }
            RowIcon.REFRESH -> {
                drawArc(c, -50f, 280f, false, p(4f, 4f), Size(18f * u, 18f * u), style = Stroke(2.4f * u, cap = StrokeCap.Round))
                val head = Path().apply { moveTo(15f * u, 1f * u); lineTo(21f * u, 5f * u); lineTo(14f * u, 8f * u); close() }
                drawPath(head, c)
            }
            RowIcon.TRASH -> {
                bar(5f, 6f, 16f, 2f); bar(10f, 3f, 6f, 3f)
                drawRoundRect(c, p(7f, 9f), Size(12f * u, 14f * u), androidx.compose.ui.geometry.CornerRadius(2f * u), style = Stroke(2f * u))
                bar(11f, 12f, 1.6f, 8f); bar(14.4f, 12f, 1.6f, 8f)
            }
        }
    }
}
