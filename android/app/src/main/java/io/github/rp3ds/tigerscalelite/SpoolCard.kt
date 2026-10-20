package io.github.rp3ds.tigerscalelite

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private val CARD = Color(0xFF141821)
private val BORDER = Color(0xFF2E3646)
private val TEXT = Color(0xFFFFFFFF)
private val MUTED = Color(0xFF8A93A6)
private val TRACK = Color(0xFF262D39)
private val GREEN = Color(0xFF3BA55D)
private val CHIP = Color(0xFF1D2430)

/** What the TigerTag catalogue knows about a product, ready to show on the card. */
data class SpoolInfo(
    val brand: String,
    val series: String,
    val name: String,
    val material: String,
    val aspects: List<String>,
    val colors: List<Color>,
    val diameter: String,
    val grams: Int,
    val refill: Boolean,
    val nozzle: String,
    val bed: String,
    val dry: String,
    val imageUrl: String,
)

/**
 * The public TigerTag catalogue API (no key). The scale only sends the photo URL, `https://cdn.tigertag.io/img?id=N`,
 * and N is the product id the tag carries, so the phone asks the catalogue about that product itself. The endpoint
 * takes a chip UID (a decimal integer) next to the product id, but answers the same for any value, so the app sends
 * 0: the card then needs nothing from the weighing workflow (whose latched UID only exists once a spool sits on the
 * platform), and the UIDs of the user's spools are not handed to a third party.
 */
object ProductCatalog {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val cache = LruCache<Long, SpoolInfo>(16)
    private val photoUrl = Regex("""^https://cdn\.tigertag\.io/img\?id=(\d+)""")

    /** The catalogue product id inside a photo URL the scale sent, or null when the URL is not a catalogue one. */
    fun productIdOf(url: String): Long? = photoUrl.find(url)?.groupValues?.get(1)?.toLongOrNull()

    suspend fun load(productId: Long): SpoolInfo? = withContext(Dispatchers.IO) {
        cache.get(productId)?.let { return@withContext it }
        val url = "https://api.tigertag.io/api:tigertag/product/get?uid=0&product_id=$productId"
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) null else parse(JSONObject(r.body?.string() ?: return@use null))
            }
        }.getOrNull()?.also { cache.put(productId, it) }
    }

    // org.json turns a JSON null into the text "null": read it as empty instead.
    private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)

    private fun color(hex: String): Color? {
        val h = hex.removePrefix("#")
        if (h.length < 6) return null
        return runCatching { Color(("FF" + h.substring(0, 6)).toLong(16)) }.getOrNull()
    }

    private fun range(o: JSONObject?, lo: String, hi: String): String {
        val a = o?.optInt(lo, 0) ?: 0
        val b = o?.optInt(hi, 0) ?: 0
        return when {
            a > 0 && b > 0 && a != b -> "$a–$b °C"
            b > 0 -> "$b °C"
            a > 0 -> "$a °C"
            else -> ""
        }
    }

    private fun parse(j: JSONObject): SpoolInfo {
        val f = j.optJSONObject("filament") ?: JSONObject()
        val colors = ArrayList<Color>()
        f.optJSONObject("color_info")?.optJSONArray("colors")?.let { a ->
            for (i in 0 until a.length()) color(a.optString(i))?.let(colors::add)
        }
        if (colors.isEmpty()) color(f.str("color").ifBlank { j.str("color") })?.let(colors::add)
        val dryer = j.optJSONObject("dryer")
        val dryTemp = dryer?.optInt("temp", 0) ?: 0
        val dryHours = dryer?.optInt("time", 0) ?: 0
        val diameter = f.str("diameter")
        return SpoolInfo(
            brand = j.str("brand"),
            series = j.str("series"),
            name = j.str("name").ifBlank { j.str("title") },
            material = f.str("material"),
            aspects = listOf(f.str("aspect1"), f.str("aspect2")).filter { it.isNotBlank() && !it.equals("none", true) },
            colors = colors,
            diameter = if (diameter.isBlank()) "" else "$diameter mm",
            grams = f.optInt("grams", 0),
            refill = f.optBoolean("refill", false),
            nozzle = range(j.optJSONObject("nozzle"), "temp_min", "temp_max"),
            bed = range(j.optJSONObject("bed"), "temp_min", "temp_max"),
            dry = if (dryTemp > 0) "$dryTemp °C" + if (dryHours > 0) " · $dryHours h" else "" else "",
            imageUrl = j.optJSONObject("images")?.str("main_src") ?: "",
        )
    }
}

/**
 * The spool card under the scale screen (which has room to spare), like the card in the TigerTag app: tag type,
 * how much is left, the product and its details. It needs the catalogue product id from the tag: without it (a
 * standard TigerTag) it shows the inventory photo alone, as before; with it, nothing shows until the catalogue has
 * answered.
 */
@Composable
fun SpoolCard(s: ScaleState) {
    // The reader sometimes loses the tag for a moment and finds it again; the scale then clears the photo URL and
    // sends it again, which made the card blink. Keep the last URL a few seconds, so only a spool that is really
    // gone (or a different one) changes the card.
    val url = rememberHeld(s.spoolImageUrl, HOLD_MS)
    val shown = if (url == s.spoolImageUrl) s else s.copy(spoolImageUrl = url)
    val productId = remember(url) { ProductCatalog.productIdOf(url) }
    if (productId == null) { SpoolPhoto(shown); return }   // a standard TigerTag has no catalogue entry: its inventory photo
    // Nothing is shown until the catalogue has answered: the bare photo first and the card a moment later looked
    // like two screens. A failed request is tried again a few times (an answer that arrives is cached for good).
    val info by produceState<SpoolInfo?>(null, productId) {
        repeat(5) {
            ProductCatalog.load(productId)?.let { value = it; return@produceState }
            delay(3_000)
        }
    }
    val card = info ?: return

    val photo by produceState<Bitmap?>(null, card.imageUrl, url) {
        value = AvatarLoader.load(card.imageUrl.ifBlank { url }, maxDim = 512)
    }
    // Filament left = what the scale weighs minus the empty spool, when the account's inventory knows the spool.
    val net = if (s.container > 0 && s.weight > s.container) s.weight - s.container else null
    val pct = if (net != null && card.grams > 0) (net * 100 / card.grams).coerceIn(0, 100) else null

    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(shape).background(CARD)
            .border(1.dp, BORDER, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("TigerTag+", Color(0xFF2B2459), Color(0xFFCFC9FF))
            if (card.refill) Pill(stringResource(R.string.card_refill), Color(0xFF173B2B), Color(0xFF9BE0BC))
        }
        if (pct != null && net != null) {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Text("$pct %", color = TEXT, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text("$net / ${card.grams} g", color = MUTED, fontSize = 13.sp)
                }
                Spacer(Modifier.height(5.dp))
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(TRACK)) {
                    Box(Modifier.fillMaxWidth(pct / 100f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(GREEN))
                }
            }
        }
        Column {
            val head = listOf(card.brand, card.series).filter { it.isNotBlank() }.joinToString(" · ")
            if (head.isNotBlank()) Text(head, color = MUTED, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(card.name, color = TEXT, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(128.dp).clip(RoundedCornerShape(10.dp)).background(TRACK), contentAlignment = Alignment.Center) {
                photo?.let {
                    Image(bitmap = it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(128.dp))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Detail(stringResource(R.string.card_material), card.material)
                Detail(stringResource(R.string.card_aspect), card.aspects.joinToString(" / "))
                Detail(stringResource(R.string.card_weight), if (card.grams > 0) "${card.grams} g" else "")
                Detail(stringResource(R.string.card_diameter), card.diameter)
                if (card.colors.isNotEmpty()) {
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        card.colors.take(4).forEach { c ->
                            Box(Modifier.size(22.dp).clip(CircleShape).background(c).border(1.5.dp, TEXT, CircleShape))
                        }
                    }
                }
            }
        }
        val temps = listOf(
            stringResource(R.string.card_nozzle) to card.nozzle,
            stringResource(R.string.card_bed) to card.bed,
            stringResource(R.string.card_dry) to card.dry,
        ).filter { it.second.isNotBlank() }
        // Two chips to a row: three do not fit one line on a phone, and a sideways scroll hides the last one.
        temps.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                pair.forEach { (label, value) ->
                    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(CHIP).padding(horizontal = 9.dp, vertical = 3.dp)) {
                        Text("$label $value", color = Color(0xFFB6BDCB), fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** How long the card outlives a lost tag. */
private const val HOLD_MS = 5_000L

/** [value] as it comes, except that a blank value only shows up after [holdMs] of staying blank. */
@Composable
private fun rememberHeld(value: String, holdMs: Long): String {
    var held by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (value.isNotBlank()) held = value
        else { delay(holdMs); held = "" }
    }
    return held
}

@Composable
private fun Pill(text: String, bg: Color, fg: Color) {
    Box(Modifier.clip(RoundedCornerShape(20.dp)).background(bg).padding(horizontal = 10.dp, vertical = 2.dp)) {
        Text(text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Detail(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MUTED, fontSize = 12.5.sp)
        Spacer(Modifier.width(8.dp))
        Text(value, color = TEXT, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
