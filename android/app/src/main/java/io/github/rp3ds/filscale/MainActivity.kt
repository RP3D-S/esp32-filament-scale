package io.github.rp3ds.filscale

import android.Manifest
import android.content.pm.PackageManager
import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: ScaleViewModel by viewModels()

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) vm.startBle()
        }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
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
                        onFbLogin = vm::loginFirebase,
                        onFbLogout = vm::logoutFirebase,
                        onRfidTest = vm::rfidTest,
                        onRfPower = vm::rfPower,
                        onDiscover = vm::discoverScales,
                        onStopDiscover = vm::stopDiscoverScales,
                        onChoose = vm::chooseScale,
                        onForget = vm::forgetScale,
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
    onFbLogin: (String, String) -> Unit,
    onFbLogout: () -> Unit,
    onRfidTest: (Boolean) -> Unit,
    onRfPower: (Int) -> Unit,
    onDiscover: () -> Unit,
    onStopDiscover: () -> Unit,
    onChoose: (FoundScale) -> Unit,
    onForget: () -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    // First run (no scale chosen yet): go straight to the picker.
    LaunchedEffect(s.noScale) { if (s.noScale) showPicker = true }

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
        SettingsDialog(
            s, { showSettings = false }, onCalibrate, onHost, onSearch, onScanWifi, onWifi, onFbLogin, onFbLogout,
            onRfidTest, onRfPower, onPickScale = { showSettings = false; showPicker = true }, onForget = onForget,
        )
    }
    if (showPicker) ScalePickerDialog(s, onDiscover, onStopDiscover, onChoose) { showPicker = false }
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
    onFbLogin: (String, String) -> Unit,
    onFbLogout: () -> Unit,
    onRfidTest: (Boolean) -> Unit,
    onRfPower: (Int) -> Unit,
    onPickScale: () -> Unit,
    onForget: () -> Unit,
) {
    var showCal by remember { mutableStateOf(false) }
    var showHost by remember { mutableStateOf(false) }
    var showWifi by remember { mutableStateOf(false) }
    var showFb by remember { mutableStateOf(false) }
    var showRfid by remember { mutableStateOf(false) }
    var showLang by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.scale_label), fontWeight = FontWeight.Medium)
                    Text(if (s.noScale) stringResource(R.string.scale_none) else s.scaleName)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.label_bluetooth), fontWeight = FontWeight.Medium)
                    Text(stringResource(if (s.bleLinked) R.string.status_connected else if (s.noScale) R.string.scale_none else R.string.status_searching_scale))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.label_wifi), fontWeight = FontWeight.Medium)
                    Text(
                        when {
                            s.wifiLinked -> stringResource(R.string.status_wifi_linked, s.host)
                            s.wifiState == 2 -> stringResource(R.string.status_wifi_scale_on, s.ssid)
                            s.wifiState == 1 -> stringResource(R.string.status_wifi_connecting)
                            s.wifiState == 3 -> stringResource(R.string.status_wifi_failed)
                            else -> stringResource(R.string.status_wifi_none)
                        },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.label_account), fontWeight = FontWeight.Medium)
                    Text(
                        when (s.fbState) {
                            2 -> s.fbEmail.ifBlank { stringResource(R.string.status_connected) }
                            1 -> stringResource(R.string.connecting)
                            3 -> stringResource(R.string.badge_error)
                            else -> stringResource(R.string.badge_no_account)
                        },
                    )
                }
                Text(
                    stringResource(
                        R.string.hw_status,
                        stringResource(if (s.readerOk) R.string.ok else R.string.not_detected),
                        stringResource(if (s.scaleOk) R.string.ok else R.string.no_response),
                    ),
                    fontSize = 12.sp, color = Color(0xFF8A93A6),
                )
                if (s.firmware.isNotEmpty()) {
                    Text(
                        stringResource(R.string.firmware_line, s.firmware, "%.2f".format(s.calibration)),
                        fontSize = 12.sp, color = Color(0xFF8A93A6),
                    )
                }
                OutlinedButton(onClick = onPickScale, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.btn_add_scale))
                }
                if (!s.noScale) {
                    TextButton(onClick = onForget) { Text(stringResource(R.string.btn_forget_scale)) }
                }
                OutlinedButton(
                    onClick = { onScanWifi(); showWifi = true },
                    enabled = s.bleLinked,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.btn_configure_wifi)) }
                OutlinedButton(onClick = { showFb = true }, enabled = s.bleLinked, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (s.fbState == 2) R.string.acct_title else R.string.btn_connect_account))
                }
                OutlinedButton(onClick = { showRfid = true }, enabled = s.connected, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.rfid_test))
                }
                OutlinedButton(onClick = { showCal = true }, enabled = s.connected, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.calibrate))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onSearch, enabled = !s.searching) { Text(stringResource(R.string.btn_search)) }
                    OutlinedButton(onClick = { showHost = true }) { Text(stringResource(R.string.btn_manual_ip)) }
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    OutlinedButton(onClick = { showLang = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.language))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) } },
    )

    if (showLang) LanguageDialog { showLang = false }
    if (showWifi) WifiDialog(s, { showWifi = false }, onScanWifi, onWifi)
    if (showRfid) RfidTestScreen(s, { showRfid = false }, onRfPower, onRfidTest)
    if (showFb) FirebaseDialog(s, { showFb = false }, onFbLogin, onFbLogout)
    if (showHost) HostDialog(s.host, { showHost = false }) { onHost(it); showHost = false }
    if (showCal) CalibrateDialog({ showCal = false }) { onCalibrate(it); showCal = false }
}

/** Lists scales in Bluetooth range (strongest first); tapping one makes it the scale this app uses. */
@Composable
private fun ScalePickerDialog(
    s: ScaleState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onChoose: (FoundScale) -> Unit,
    onDismiss: () -> Unit,
) {
    // Discovery starts once the Bluetooth permission is there (it may be asked right now).
    LaunchedEffect(s.blePerm) { if (s.blePerm) onStart() }
    DisposableEffect(Unit) { onDispose { onStop() } }
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(10_000); waited = true }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.picker_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.picker_hint), fontSize = 13.sp)
                if (s.found.isEmpty()) {
                    Text(
                        stringResource(if (waited) R.string.picker_none_found else R.string.scanning),
                        fontSize = 13.sp, color = Color(0xFF8A93A6),
                    )
                }
                s.found.forEachIndexed { i, f ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (f.name == s.scaleName && !s.noScale) Color(0xFF2F7FFF) else Color(0xFF141821))
                            .clickable { onChoose(f); onDismiss() }
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(f.name, fontWeight = FontWeight.Medium)
                        Text(
                            "${f.rssi} dBm" + if (i == 0 && s.found.size > 1) " · " + stringResource(R.string.picker_nearest) else "",
                            fontSize = 12.sp, color = Color(0xFF8A93A6),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) } },
    )
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
        title = { Text(stringResource(R.string.wifi_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.wifi_networks), fontSize = 13.sp)
                if (s.networks.isEmpty()) Text(stringResource(R.string.scanning), fontSize = 13.sp, color = Color(0xFF8A93A6))
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
                TextButton(onClick = onScan) { Text(stringResource(R.string.btn_search_again)) }
                OutlinedTextField(
                    value = ssid, onValueChange = { ssid = it }, singleLine = true,
                    label = { Text(stringResource(R.string.wifi_network_name)) },
                )
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it }, singleLine = true,
                    label = { Text(stringResource(R.string.password)) },
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (s.wifiNeedsBoot) {
                    Text(
                        stringResource(R.string.wifi_needs_boot),
                        color = Color(0xFFE8821E), fontSize = 13.sp,
                    )
                }
                when (s.wifiState) {
                    1 -> Text(stringResource(R.string.connecting), color = Color(0xFF2F7FFF))
                    2 -> Text(stringResource(R.string.wifi_connected_to, s.ssid, s.ip), color = Color(0xFF3BA55D))
                    3 -> Text(stringResource(R.string.wifi_cannot), color = Color(0xFFE24B4A))
                }
            }
        },
        confirmButton = { TextButton(onClick = { if (ssid.isNotBlank()) onOk(ssid, pass) }) { Text(stringResource(R.string.btn_connect)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) } },
    )
}

/** Signs the scale in to the TigerTag cloud. The password is sent once over the encrypted link and never stored. */
@Composable
private fun FirebaseDialog(
    s: ScaleState,
    onDismiss: () -> Unit,
    onLogin: (String, String) -> Unit,
    onLogout: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.acct_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.fbState == 2) {
                    Text(stringResource(R.string.acct_connected_as, s.fbName.ifBlank { s.fbEmail }), color = Color(0xFF3BA55D))
                    Text(s.fbEmail, fontSize = 13.sp, color = Color(0xFF8A93A6))
                    Text(stringResource(R.string.acct_sends), fontSize = 13.sp)
                } else {
                    Text(
                        stringResource(R.string.acct_intro),
                        fontSize = 13.sp,
                    )
                    OutlinedTextField(
                        value = email, onValueChange = { email = it }, singleLine = true,
                        label = { Text(stringResource(R.string.email)) },
                    )
                    OutlinedTextField(
                        value = pass, onValueChange = { pass = it }, singleLine = true,
                        label = { Text(stringResource(R.string.password)) },
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Text(
                        stringResource(R.string.acct_pair_hint),
                        fontSize = 12.sp, color = Color(0xFF8A93A6),
                    )
                }
                if (s.fbNeedsBoot) {
                    Text(
                        stringResource(R.string.acct_needs_boot),
                        color = Color(0xFFE8821E), fontSize = 13.sp,
                    )
                }
                when (s.fbState) {
                    1 -> Text(stringResource(R.string.connecting), color = Color(0xFF2F7FFF))
                    3 -> Text(s.fbError.ifBlank { stringResource(R.string.acct_cannot) }, color = Color(0xFFE24B4A))
                }
            }
        },
        confirmButton = {
            if (s.fbState == 2) {
                TextButton(onClick = { onLogout(); onDismiss() }) { Text(stringResource(R.string.logout)) }
            } else {
                TextButton(onClick = { if (email.isNotBlank() && pass.isNotBlank()) onLogin(email.trim(), pass) }) { Text(stringResource(R.string.btn_connect)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) } },
    )
}

@Composable
private fun HostDialog(current: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.host_title)) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                label = { Text(stringResource(R.string.host_hint)) },
            )
        },
        confirmButton = { TextButton(onClick = { onOk(text) }) { Text(stringResource(R.string.btn_connect)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun CalibrateDialog(onDismiss: () -> Unit, onOk: (Float) -> Unit) {
    var text by remember { mutableStateOf("500") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.calibrate)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.cal_steps),
                    fontSize = 13.sp,
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    label = { Text(stringResource(R.string.cal_weight)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { text.replace(',', '.').toFloatOrNull()?.takeIf { it > 0 }?.let(onOk) }) {
                Text(stringResource(R.string.calibrate))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** In-app language, independent of the phone's (Android 13+ per-app locales; same nine as the scale). */
@Composable
private fun LanguageDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val options = listOf(
        "" to stringResource(R.string.language_system),
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
    val current = if (Build.VERSION.SDK_INT >= 33) {
        ctx.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
    } else ""
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (tag, label) ->
                    Text(
                        label,
                        Modifier
                            .fillMaxWidth()
                            .background(if (tag == current) Color(0xFF2F7FFF) else Color.Transparent)
                            .clickable {
                                if (Build.VERSION.SDK_INT >= 33) {
                                    ctx.getSystemService(LocaleManager::class.java).applicationLocales =
                                        LocaleList.forLanguageTags(tag)
                                }
                                onDismiss()
                            }
                            .padding(12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) } },
    )
}
