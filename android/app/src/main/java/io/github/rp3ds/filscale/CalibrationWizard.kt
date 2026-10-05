package io.github.rp3ds.filscale

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.delay
import java.util.Locale

// Same palette as the main screen (original firmware's LVCOL_*).
private val BG = Color(0xFF0B0E14)
private val CARD = Color(0xFF141821)
private val BORDER = Color(0xFF2E3646)
private val TEXT = Color(0xFFFFFFFF)
private val MUTED = Color(0xFF8A93A6)
private val FAINT = Color(0xFF5A6478)
private val ACCENT = Color(0xFF2F7FFF)
private val RED = Color(0xFFE24B4A)
private val GREEN = Color(0xFF3BA55D)

/** What the wizard asks of the scale. Every call is a command; the scale owns the state machine. */
data class CalActions(
    val start: () -> Unit,
    val tare: () -> Unit,
    val ref: (Float) -> Unit,
    val measure: () -> Unit,
    val back: () -> Unit,
    val cancel: () -> Unit,
    val factor: (Float) -> Unit,
)

// Same reference choices and keypad range as the original's wizard (runCalibrationWizard).
private val PRESETS = listOf("BambuLab Grey" to 210, "BambuLab Transp" to 215, "R3D Grey" to 239)
private const val REF_MIN = 150
private const val REF_MAX = 4500

private class Dim(val k: Float) {
    fun d(v: Float): Dp = (v * k).dp
    fun f(v: Float) = (v * k).sp
}

/**
 * Redraw of the original scale's Calibration Wizard: 1 empty the platform and TARE, 2 pick the
 * reference (Custom first, then the preset spools), 3 place it and press Calibrate once the reading
 * is steady; then it saves by itself and shows the success screen. The header goes back one step
 * (from step 1 it cancels), and the three dots show where you are.
 */
@Composable
fun CalibrationWizard(s: ScaleState, act: CalActions, onClose: () -> Unit) {
    var started by remember { mutableStateOf(false) }
    var showPad by remember { mutableStateOf(false) }
    var padValue by remember { mutableStateOf("") }
    val phase = s.calPhase

    LaunchedEffect(Unit) {
        act.start()
        delay(6_000)
        if (!started) onClose()          // the scale never answered: do not hang on an empty screen
    }
    LaunchedEffect(phase) {
        if (phase != 0) started = true
        else if (started) onClose()      // finished or cancelled on the scale
    }
    // Leaving mid-wizard (back gesture, dialog dismissed) hands the load cell back to the scale.
    val phaseNow by rememberUpdatedState(phase)
    DisposableEffect(Unit) { onDispose { if (phaseNow != 0 && phaseNow != 6) act.cancel() } }

    Dialog(
        onDismissRequest = { if (showPad) showPad = false else onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(BG), contentAlignment = Alignment.TopCenter) {
            BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(480f / 320f).background(BG)) {
                val dm = Dim(maxWidth.value / 480f)
                val title = stringResource(R.string.calibrate)
                when {
                    phase == 1 -> {
                        Header(dm, title, 1) { act.back() }
                        Hint(dm, stringResource(R.string.cal_empty_tare), 118f)
                        // The kitchen-scale TARE button, promoted to THE action.
                        Box(
                            Modifier
                                .offset(dm.d(130f), dm.d(244f)).size(dm.d(220f), dm.d(64f))
                                .clip(RoundedCornerShape(dm.d(12f))).background(ACCENT)
                                .clickable { act.tare() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("0.0", color = TEXT, fontSize = dm.f(20f))
                                Text(stringResource(R.string.tare), color = TEXT, fontSize = dm.f(14f))
                            }
                        }
                    }
                    phase == 2 -> Busy(dm, stringResource(R.string.cal_taring))
                    phase == 3 && showPad -> Pad(
                        dm, padValue,
                        onKey = { key ->
                            padValue = when {
                                key == '<' -> padValue.dropLast(1)
                                padValue.isEmpty() && key == '0' -> padValue       // no leading zero
                                padValue.length < 5 -> padValue + key
                                else -> padValue
                            }
                        },
                        onBack = { showPad = false },
                        onOk = { v -> act.ref(v.toFloat()); showPad = false; padValue = "" },
                    )
                    phase == 3 -> {
                        Header(dm, title, 2) { act.back() }
                        // One 52 px row per choice, Custom first: typing the real weight of any object
                        // beats hoping to own a listed spool.
                        val rows = listOf<Pair<String, Int?>>(stringResource(R.string.cal_custom) to null) + PRESETS
                        rows.forEachIndexed { i, (name, grams) ->
                            Box(
                                Modifier
                                    .offset(dm.d(10f), dm.d(60f + i * 61f)).size(dm.d(460f), dm.d(52f))
                                    .clip(RoundedCornerShape(dm.d(10f))).background(CARD)
                                    .border(dm.d(1f), BORDER, RoundedCornerShape(dm.d(10f)))
                                    .clickable { if (grams == null) { padValue = ""; showPad = true } else act.ref(grams.toFloat()) },
                            ) {
                                Text(name, color = TEXT, fontSize = dm.f(16f), modifier = Modifier.align(Alignment.CenterStart).padding(start = dm.d(16f)))
                                Text(
                                    if (grams == null) "›" else "$grams g  ›",
                                    color = MUTED, fontSize = dm.f(16f),
                                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = dm.d(14f)),
                                )
                            }
                        }
                    }
                    phase == 4 -> {
                        Header(dm, title, 3) { act.back() }
                        // The one number on this screen is the instruction itself.
                        Hint(dm, stringResource(R.string.cal_place, s.calRef.toFloat()), 118f)
                        // The button turning blue IS the "you can press now": it arms only on a steady reading.
                        Box(
                            Modifier
                                .offset(dm.d(100f), dm.d(252f)).size(dm.d(280f), dm.d(56f))
                                .clip(RoundedCornerShape(dm.d(12f)))
                                .background(if (s.calOk) ACCENT else CARD)
                                .then(if (s.calOk) Modifier else Modifier.border(dm.d(1f), BORDER, RoundedCornerShape(dm.d(12f))))
                                .clickable(enabled = s.calOk) { act.measure() },
                            contentAlignment = Alignment.Center,
                        ) { Text(title, color = if (s.calOk) TEXT else FAINT, fontSize = dm.f(16f)) }
                    }
                    phase == 5 -> Busy(dm, stringResource(R.string.cal_measuring))
                    phase == 6 -> Result(
                        dm, ok = true, head = stringResource(R.string.cal_saved),
                        body = "Factor: " + String.format(Locale.US, "%.4f", s.calibration) + "\n" + stringResource(R.string.cal_saved2),
                    )
                    phase == 7 -> Result(dm, ok = false, head = stringResource(R.string.badge_error), body = stringResource(R.string.cal_err_read))
                }
            }
        }
    }
}

@Composable
private fun Header(dm: Dim, title: String, step: Int, onBack: () -> Unit) {
    Box(Modifier.size(dm.d(480f), dm.d(48f)).clickable { onBack() }) {
        Text("‹", color = TEXT, fontSize = dm.f(34f), modifier = Modifier.offset(dm.d(16f), dm.d(2f)))
        Text(title, color = TEXT, fontSize = dm.f(20f), modifier = Modifier.offset(dm.d(56f), dm.d(12f)))
        if (step in 1..3) {
            for (i in 0 until 3) {
                Box(
                    Modifier
                        .offset(dm.d(418f + i * 18f), dm.d(19f)).size(dm.d(10f))
                        .clip(CircleShape).background(if (i == step - 1) ACCENT else FAINT),
                )
            }
        }
        Box(Modifier.offset(0.dp, dm.d(47f)).size(dm.d(480f), dm.d(1f)).background(BORDER))
    }
}

@Composable
private fun Hint(dm: Dim, text: String, y: Float) {
    Text(
        text, color = TEXT, fontSize = dm.f(20f), textAlign = TextAlign.Center,
        modifier = Modifier.offset(0.dp, dm.d(y)).width(dm.d(480f)).padding(horizontal = dm.d(16f)),
    )
}

@Composable
private fun Busy(dm: Dim, text: String) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = dm.d(16f)),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = ACCENT, modifier = Modifier.size(dm.d(44f)))
        Text(text, color = TEXT, fontSize = dm.f(20f), textAlign = TextAlign.Center, modifier = Modifier.padding(top = dm.d(14f)))
    }
}

@Composable
private fun Result(dm: Dim, ok: Boolean, head: String, body: String) {
    val tint = if (ok) GREEN else RED
    Column(
        Modifier.fillMaxSize().padding(horizontal = dm.d(16f)),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(dm.d(64f)).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) {
            Text(if (ok) "✓" else "✕", color = TEXT, fontSize = dm.f(34f))
        }
        Text(head, color = tint, fontSize = dm.f(20f), textAlign = TextAlign.Center, modifier = Modifier.padding(top = dm.d(12f)))
        Text(body, color = MUTED, fontSize = dm.f(14f), textAlign = TextAlign.Center, modifier = Modifier.padding(top = dm.d(6f)))
    }
}

/**
 * Integer keypad of the original (tsNumericInput): starts empty, whole grams, no leading zero, 5 digits
 * at most. OK stays gray and inert until the value is within 150..4500; the field says the rule until
 * it is met, then shows a green check.
 */
@Composable
private fun Pad(dm: Dim, value: String, onKey: (Char) -> Unit, onBack: () -> Unit, onOk: (Int) -> Unit) {
    val v = value.toIntOrNull()
    val overMax = v != null && v > REF_MAX
    val valid = v != null && v >= REF_MIN && !overMax

    Header(dm, stringResource(R.string.cal_ref_title), 0, onBack)

    // Value field (left column), hint on its right.
    Box(
        Modifier
            .offset(dm.d(10f), dm.d(52f)).size(dm.d(330f), dm.d(52f))
            .clip(RoundedCornerShape(dm.d(10f))).background(CARD)
            .border(dm.d(1f), if (valid) ACCENT else BORDER, RoundedCornerShape(dm.d(10f))),
    ) {
        Text(
            value.ifEmpty { "0" }, color = if (value.isEmpty()) FAINT else TEXT, fontSize = dm.f(20f),
            modifier = Modifier.align(Alignment.CenterStart).padding(start = dm.d(14f)),
        )
        val hint = when {
            overMax -> stringResource(R.string.cal_max_g, REF_MAX) to RED
            valid -> "✓" to GREEN
            else -> stringResource(R.string.cal_min_g, REF_MIN) to FAINT
        }
        Text(hint.first, color = hint.second, fontSize = dm.f(14f), modifier = Modifier.align(Alignment.CenterEnd).padding(end = dm.d(12f)))
    }

    // 3 x 4 digit grid under the field (blank, 0, blank on the last row).
    val keys = listOf("7", "8", "9", "4", "5", "6", "1", "2", "3", "", "0", "")
    keys.forEachIndexed { i, label ->
        if (label.isEmpty()) return@forEachIndexed
        val r = i / 3; val c = i % 3
        Box(
            Modifier
                .offset(dm.d(10f + c * 112f), dm.d(110f + r * 54f)).size(dm.d(105f), dm.d(47f))
                .clip(RoundedCornerShape(dm.d(10f))).background(CARD)
                .border(dm.d(1f), BORDER, RoundedCornerShape(dm.d(10f)))
                .clickable { onKey(label[0]) },
            contentAlignment = Alignment.Center,
        ) { Text(label, color = TEXT, fontSize = dm.f(20f)) }
    }

    // Action column: backspace aligned with the field, OK filling the rest (blue only when valid).
    Box(
        Modifier
            .offset(dm.d(350f), dm.d(52f)).size(dm.d(120f), dm.d(52f))
            .clip(RoundedCornerShape(dm.d(10f))).background(CARD)
            .border(dm.d(1f), BORDER, RoundedCornerShape(dm.d(10f)))
            .clickable { onKey('<') },
        contentAlignment = Alignment.Center,
    ) { Text("⌫", color = TEXT, fontSize = dm.f(20f)) }
    Box(
        Modifier
            .offset(dm.d(350f), dm.d(111f)).size(dm.d(120f), dm.d(201f))
            .clip(RoundedCornerShape(dm.d(12f))).background(if (valid) ACCENT else CARD)
            .then(if (valid) Modifier else Modifier.border(dm.d(1f), BORDER, RoundedCornerShape(dm.d(12f))))
            .clickable(enabled = valid) { onOk(v!!) },
        contentAlignment = Alignment.Center,
    ) { Text(stringResource(R.string.ok), color = if (valid) TEXT else FAINT, fontSize = dm.f(20f)) }
}
