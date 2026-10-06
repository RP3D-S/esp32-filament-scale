package io.github.rp3ds.tigerscalelite

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.Inet4Address

/** A fixed address for the scale's Wi-Fi: [on] false means DHCP (the default). */
data class FixedIp(val on: Boolean, val ip: String, val gw: String, val mask: String, val dns: String)

/** What the Wi-Fi dialog can ask of the scale besides the credentials. */
data class NetActions(val applyFixed: (FixedIp) -> Unit)

/** The addresses of the phone's own Wi-Fi network, to suggest values that fit the same router. */
data class PhoneNet(val ip: String, val gateway: String, val mask: String, val dns: String)

fun isIpv4(s: String): Boolean {
    val p = s.trim().split('.')
    return p.size == 4 && p.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) && it.toInt() in 0..255 }
}

/** Editable copy of the fixed-IP settings while the Wi-Fi dialog is open. */
class FixedIpForm(on: Boolean, ip: String, gw: String, mask: String, dns: String) {
    var on by mutableStateOf(on)
    var ip by mutableStateOf(ip)
    var gw by mutableStateOf(gw)
    var mask by mutableStateOf(mask.ifBlank { "255.255.255.0" })
    var dns by mutableStateOf(dns)

    fun toFixedIp() = FixedIp(on, ip.trim(), gw.trim(), mask.trim(), dns.trim())

    /** Off is always valid; on needs an address, a gateway and a mask (the DNS may stay empty: the scale uses the gateway). */
    fun valid() = !on || (isIpv4(ip) && isIpv4(gw) && isIpv4(mask) && (dns.isBlank() || isIpv4(dns)))

    fun differsFrom(s: ScaleState) =
        on != s.sipOn || (on && (ip.trim() != s.sipIp || gw.trim() != s.sipGw || mask.trim() != s.sipMask || dns.trim() != s.sipDns))
}

private fun dotted(v: Int) = "${(v ushr 24) and 255}.${(v ushr 16) and 255}.${(v ushr 8) and 255}.${v and 255}"

/**
 * The phone's current Wi-Fi addresses, with a suggested address for the scale at the top end of the same subnet
 * (outside the range most routers hand out, but check it against the router's DHCP range). Null without Wi-Fi.
 */
fun phoneNetwork(ctx: Context): PhoneNet? {
    val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
    val lp = cm.getLinkProperties(cm.activeNetwork ?: return null) ?: return null
    val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
    val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress ?: return null
    val dns = lp.dnsServers.firstOrNull { it is Inet4Address }?.hostAddress ?: gw
    val prefix = la.prefixLength
    val mask = if (prefix <= 0) 0 else -1 shl (32 - prefix)
    val b = la.address.address
    val phone = ((b[0].toInt() and 255) shl 24) or ((b[1].toInt() and 255) shl 16) or ((b[2].toInt() and 255) shl 8) or (b[3].toInt() and 255)
    val hostMask = mask.inv()
    if (hostMask < 32) return null
    return PhoneNet(ip = dotted((phone and mask) + (hostMask - 15)), gateway = gw, mask = dotted(mask), dns = dns)
}

private val MUTED = Color(0xFF8A93A6)
private val ORANGE = Color(0xFFE8821E)
private val RED = Color(0xFFE24B4A)

/**
 * "Fixed IP address", for a router that never answers the scale's DHCP request (it associates and then waits
 * forever for an address). Part of the Wi-Fi dialog: the same Connect button sends it with the credentials.
 */
@Composable
fun FixedIpSection(form: FixedIpForm, s: ScaleState, onApplyOnly: () -> Unit) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.ip_use), modifier = Modifier.weight(1f))
            Switch(checked = form.on, onCheckedChange = { form.on = it })
        }
        if (form.on) {
            val num = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            OutlinedTextField(value = form.ip, onValueChange = { form.ip = it }, singleLine = true, keyboardOptions = num,
                label = { Text(stringResource(R.string.ip_address)) }, isError = form.ip.isNotBlank() && !isIpv4(form.ip))
            OutlinedTextField(value = form.gw, onValueChange = { form.gw = it }, singleLine = true, keyboardOptions = num,
                label = { Text(stringResource(R.string.ip_gateway)) }, isError = form.gw.isNotBlank() && !isIpv4(form.gw))
            OutlinedTextField(value = form.mask, onValueChange = { form.mask = it }, singleLine = true, keyboardOptions = num,
                label = { Text(stringResource(R.string.ip_mask)) }, isError = form.mask.isNotBlank() && !isIpv4(form.mask))
            OutlinedTextField(value = form.dns, onValueChange = { form.dns = it }, singleLine = true, keyboardOptions = num,
                label = { Text(stringResource(R.string.ip_dns)) }, isError = form.dns.isNotBlank() && !isIpv4(form.dns))
            TextButton(onClick = {
                phoneNetwork(ctx)?.let { n -> form.ip = n.ip; form.gw = n.gateway; form.mask = n.mask; form.dns = n.dns }
            }) { Text(stringResource(R.string.ip_fill)) }
            Text(stringResource(R.string.ip_hint), color = MUTED, fontSize = 12.sp)
        }
        if (s.ipErr == "addr") Text(stringResource(R.string.ip_invalid), color = RED, fontSize = 13.sp)
        if (s.ipErr == "boot") Text(stringResource(R.string.wifi_needs_boot), color = ORANGE, fontSize = 13.sp)
        if (form.differsFrom(s)) {
            TextButton(onClick = onApplyOnly, enabled = form.valid() && s.bleLinked) { Text(stringResource(R.string.ip_apply)) }
        } else if (s.sipOn && s.sipIp.isNotBlank()) {
            Text(stringResource(R.string.ip_current, s.sipIp), color = MUTED, fontSize = 12.sp)
        }
    }
}
