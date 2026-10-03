package io.github.rp3ds.filscale

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// Palette and geometry copied from the original firmware's main screen
// (lvglBuildMainScreen / LVCOL_* in TigerTagSplashESP32.ino), 480x320 landscape.
private val BG = Color(0xFF0B0E14)
private val CARD = Color(0xFF141821)
private val BORDER = Color(0xFF2E3646)
private val TEXT = Color(0xFFFFFFFF)
private val MUTED = Color(0xFF8A93A6)
private val FAINT = Color(0xFF565D6D)
private val ACCENT = Color(0xFF2F7FFF)
private val RED = Color(0xFFE24B4A)
private val GREEN = Color(0xFF3BA55D)

private data class Badge(val text: String, val bg: Color, val spin: Boolean = false)

/** The original's status badge (top-left), mapped from the states this firmware has. */
private fun badgeFor(s: ScaleState): Badge = when {
    !s.connected -> Badge("Sem ligação", RED)
    !s.scaleOk -> Badge("Erro", RED)
    s.status == "scanning" -> Badge("A pesar", ACCENT, spin = true)
    s.status == "stable" && s.fbState == 2 -> Badge("Sincronizado!", GREEN)
    s.fbState == 2 -> Badge("Pronto", GREEN)
    s.fbState == 1 -> Badge("A ligar…", ACCENT, spin = true)
    else -> Badge("Sem conta", RED)
}

/**
 * A faithful redraw of the scale's 480x320 LCD. Every coordinate below is the original's
 * pixel value; `k` scales the whole canvas to the phone's width.
 */
@Composable
fun ScaleDisplay(
    s: ScaleState,
    onTare: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tareFlash by remember { mutableStateOf(false) }
    LaunchedEffect(tareFlash) {
        if (tareFlash) {
            delay(1000)
            tareFlash = false
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .aspectRatio(480f / 320f)
            .background(BG),
    ) {
        val k = maxWidth.value / 480f
        fun d(v: Float): Dp = (v * k).dp
        fun f(v: Float) = (v * k).sp

        val badge = badgeFor(s)
        val tagActive = s.uid.isNotEmpty()

        // ---- Top-left: status badge ----
        Row(
            Modifier
                .offset(d(8f), d(8f))
                .clip(RoundedCornerShape(d(8f)))
                .background(badge.bg)
                .padding(horizontal = d(7f), vertical = d(4f)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(d(6f)),
        ) {
            if (badge.spin) {
                CircularProgressIndicator(
                    modifier = Modifier.size(d(18f)),
                    strokeWidth = d(3f),
                    color = TEXT,
                    trackColor = TEXT.copy(alpha = 0.3f),
                )
            }
            Text(badge.text, color = TEXT, fontSize = f(16f))
        }

        // ---- Top-centre: name ----
        Box(Modifier.fillMaxWidth().offset(y = d(6f)), contentAlignment = Alignment.TopCenter) {
            Text(if (s.fbState == 2 && s.fbName.isNotBlank()) s.fbName else "FilScale", color = TEXT, fontSize = f(20f))
        }

        // ---- Top-right: link icons ----
        Row(
            Modifier.align(Alignment.TopEnd).padding(top = d(10f), end = d(8f)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(d(8f)),
        ) {
            Text("BT", color = if (s.bleLinked) ACCENT else FAINT, fontSize = f(12f))
            WifiIcon(d(18f), lit = if (s.wifiLinked) litBars(s.rssi) else 0)
        }

        // ---- Main card ----
        Box(
            Modifier
                .offset(d(20f), d(50f))
                .size(d(440f), d(150f))
                .clip(RoundedCornerShape(d(14f)))
                .background(CARD)
                .border(d(1f), BORDER, RoundedCornerShape(d(14f))),
        ) {
            // Pairing badge: with a single reader the original shows the "one chip" outline.
            if (tagActive) {
                Box(
                    Modifier.offset(d(12f), d(10f)).size(d(26f)).border(d(2f), FAINT, CircleShape),
                ) {
                    Canvas(Modifier.size(d(26f))) {
                        val u = size.width / 26f
                        val st = Stroke(2f * u)
                        drawCircle(FAINT, 4.5f * u, Offset(8f * u, 13f * u), style = st)
                        drawCircle(FAINT, 4.5f * u, Offset(17f * u, 13f * u), style = st)
                        drawRect(FAINT, Offset(10f * u, 12f * u), Size(6f * u, 2f * u))
                    }
                }
            }

            Text(
                if (s.connected) "${s.weight} g" else "-- g",
                modifier = Modifier.offset(0.dp, d(20f)).width(d(224f)),
                color = TEXT, fontSize = f(40f), textAlign = TextAlign.Center, maxLines = 1,
            )

            if (tagActive) {
                Column(
                    Modifier.offset(0.dp, d(72f)).width(d(224f)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d(8f))) {
                        Box(Modifier.size(d(20f)).clip(CircleShape).background(Color(0xFF556070)))
                        Text(s.uid, color = TEXT, fontSize = f(16f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(d(170f)))
                    }
                }
            }

            Box(Modifier.offset(d(230f), d(14f)).size(d(1f), d(120f)).background(BORDER))

            // Container / filament are not read by this firmware yet (they come from the
            // TigerTag cloud in the original), so they show the original's "--" placeholder.
            Text("RECIPIENTE", color = MUTED, fontSize = f(14f), modifier = Modifier.offset(d(246f), d(14f)))
            Text("-- g", color = TEXT, fontSize = f(14f), modifier = Modifier.offset(d(376f), d(14f)))
            Text("FILAMENTO", color = MUTED, fontSize = f(14f), modifier = Modifier.offset(d(246f), d(40f)))
            Text("-- g", color = TEXT, fontSize = f(14f), modifier = Modifier.offset(d(376f), d(40f)))

            Box(Modifier.offset(d(246f), d(68f)).size(d(184f), d(1f)).background(BORDER))

            HomeIcon(Modifier.offset(d(246f), d(80f)).size(d(16f)))
            Text("--", color = TEXT, fontSize = f(14f), modifier = Modifier.offset(d(270f), d(80f)))
            PinIcon(Modifier.offset(d(246f), d(108f)).size(d(16f)))
            Text("--", color = TEXT, fontSize = f(14f), modifier = Modifier.offset(d(270f), d(108f)))
        }

        // ---- Bottom: Tare (2/3) + Settings (1/3) ----
        Column(
            Modifier
                .offset(d(20f), d(214f))
                .size(d(285f), d(92f))
                .clip(RoundedCornerShape(d(14f)))
                .background(if (tareFlash) GREEN else CARD)
                .border(d(1f), BORDER, RoundedCornerShape(d(14f)))
                .clickable(enabled = s.connected) { onTare(); tareFlash = true },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("0.0", color = TEXT, fontSize = f(28f))
            Text("TARA", color = TEXT, fontSize = f(16f))
        }

        Column(
            Modifier
                .offset(d(20f + 285f + 12f), d(214f))
                .size(d(143f), d(92f))
                .clip(RoundedCornerShape(d(14f)))
                .background(CARD)
                .border(d(1f), BORDER, RoundedCornerShape(d(14f)))
                .clickable { onSettings() },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            GearIcon(Modifier.size(d(28f)))
            Text("Definições", color = TEXT, fontSize = f(16f))
        }
    }
}

private fun litBars(rssi: Int) = when {
    rssi == 0 -> 1
    rssi > -60 -> 3
    rssi > -75 -> 2
    else -> 1
}

@Composable
private fun WifiIcon(size: Dp, lit: Int) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val origin = Offset(w / 2f, w * 0.9f)
        val stroke = Stroke(w * 0.1f)
        for (i in 1..3) {
            val r = w * 0.3f * i
            drawArc(
                color = if (i <= lit) TEXT else FAINT,
                startAngle = 225f, sweepAngle = 90f, useCenter = false,
                topLeft = Offset(origin.x - r, origin.y - r), size = Size(2 * r, 2 * r),
                style = stroke,
            )
        }
        drawCircle(if (lit > 0) TEXT else FAINT, w * 0.07f, origin)
    }
}

@Composable
private fun HomeIcon(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val p = Path().apply {
            moveTo(w * 0.5f, w * 0.08f); lineTo(w * 0.96f, w * 0.52f); lineTo(w * 0.80f, w * 0.52f)
            lineTo(w * 0.80f, w * 0.92f); lineTo(w * 0.20f, w * 0.92f); lineTo(w * 0.20f, w * 0.52f)
            lineTo(w * 0.04f, w * 0.52f); close()
        }
        drawPath(p, MUTED)
    }
}

@Composable
private fun PinIcon(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val tri = Path().apply {
            moveTo(w * 0.2f, w * 0.5f); lineTo(w * 0.8f, w * 0.5f); lineTo(w * 0.5f, w * 0.96f); close()
        }
        drawPath(tri, MUTED)
        drawCircle(MUTED, w * 0.34f, Offset(w * 0.5f, w * 0.38f))
        drawCircle(CARD, w * 0.13f, Offset(w * 0.5f, w * 0.38f))
    }
}

@Composable
private fun GearIcon(modifier: Modifier) {
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2f
        for (i in 0 until 8) {
            rotate(i * 45f, c) {
                drawRect(TEXT, Offset(c.x - r * 0.14f, c.y - r), Size(r * 0.28f, r * 0.34f))
            }
        }
        drawCircle(TEXT, r * 0.66f, c, style = Stroke(r * 0.3f))
    }
}
