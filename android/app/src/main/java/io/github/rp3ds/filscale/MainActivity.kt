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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.util.Locale

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
                    val cal = remember {
                        CalActions(vm::calStart, vm::calTare, vm::calRef, vm::calMeasure, vm::calBack, vm::calCancel, vm::calFactor)
                    }
                    ScaleScreen(
                        s = s,
                        onTare = vm::tare,
                        cal = cal,
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
                        onRestart = vm::restartScale,
                        onFactoryReset = vm::factoryReset,
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
    cal: CalActions,
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
    onRestart: () -> Unit,
    onFactoryReset: () -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showWizard by remember { mutableStateOf(false) }
    var showCalPrompt by remember { mutableStateOf(false) }
    // First run (no scale chosen yet): go straight to the picker.
    LaunchedEffect(s.noScale) { if (s.noScale) showPicker = true }

    // First-calibration reminder, as on the original: a never-calibrated scale weighs garbage, so ask
    // 2 s after the scale answers, then every 5 minutes until a calibration lands. Never while a
    // weighing is in progress or the wizard is open.
    val sNow by rememberUpdatedState(s)
    LaunchedEffect(s.connected, s.calDone) {
        if (!s.connected || s.calDone) { showCalPrompt = false; return@LaunchedEffect }
        delay(2_000)
        while (true) {
            val cur = sNow
            if (cur.calDone || !cur.connected) break
            if (cur.status == "idle" && cur.calPhase == 0 && !showWizard) showCalPrompt = true
            delay(300_000)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ScaleDisplay(s, onTare = onTare, onSettings = { showSettings = true })
            s.message?.let {
                Text(it, color = Color(0xFFF2B705), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
        if (showCalPrompt) {
            CalPrompt(onGo = { showCalPrompt = false; showWizard = true }, onLater = { showCalPrompt = false })
        }
    }

    if (showSettings) {
        SettingsDialog(
            s, { showSettings = false }, cal, onWizard = { showSettings = false; showWizard = true },
            onHost, onSearch, onScanWifi, onWifi, onFbLogin, onFbLogout,
            onRfidTest, onRfPower, onPickScale = { showSettings = false; showPicker = true }, onForget = onForget,
            onRestart = onRestart, onFactoryReset = onFactoryReset,
        )
    }
    if (showWizard) CalibrationWizard(s, cal) { showWizard = false }
    if (showPicker) ScalePickerDialog(s, onDiscover, onStopDiscover, onChoose) { showPicker = false }
}

/**
 * The original's first-calibration notification: a side panel sliding in from the right over the
 * dimmed home screen. Tapping outside the panel counts as "later".
 */
@Composable
private fun CalPrompt(onGo: () -> Unit, onLater: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val swallow = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onLater() },
    ) {
        AnimatedVisibility(visible = shown, enter = slideInHorizontally { it }, modifier = Modifier.align(Alignment.CenterEnd)) {
            Column(
                Modifier
                    .fillMaxHeight().fillMaxWidth(0.72f).background(Color(0xFF141821))
                    .clickable(interactionSource = swallow, indication = null) { }
                    .padding(20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("◎", color = Color(0xFFE8821E), fontSize = 44.sp)
                Text(
                    stringResource(R.string.cal_prompt_q), fontSize = 20.sp, fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp),
                )
                Text(
                    stringResource(R.string.cal_prompt_sub), fontSize = 13.sp, color = Color(0xFF8A93A6),
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
                )
                Button(onClick = onGo, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.calibrate)) }
                TextButton(onClick = onLater) { Text(stringResource(R.string.later)) }
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    s: ScaleState,
    onDismiss: () -> Unit,
    cal: CalActions,
    onWizard: () -> Unit,
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
    onRestart: () -> Unit,
    onFactoryReset: () -> Unit,
) {
    var showScale by remember { mutableStateOf(false) }
    var showManual by remember { mutableStateOf(false) }
    var showHost by remember { mutableStateOf(false) }
    var showWifi by remember { mutableStateOf(false) }
    var showFb by remember { mutableStateOf(false) }
    var showRfid by remember { mutableStateOf(false) }
    var showLang by remember { mutableStateOf(false) }

    SettingsScreen(
        s,
        SettingsActions(
            onBack = onDismiss,
            onScale = { showScale = true },
            onWifi = { onScanWifi(); showWifi = true },
            onAccount = { showFb = true },
            onWizard = onWizard,
            onManual = { showManual = true },
            onLanguage = { showLang = true },
            onRfid = { showRfid = true },
        ),
        onRestart = onRestart,
        onFactoryReset = onFactoryReset,
    )

    if (showScale) ScaleDialog(s, { showScale = false }, onSearch, { showHost = true }, onPickScale, onForget)
    if (showLang) LanguageDialog { showLang = false }
    if (showWifi) WifiDialog(s, { showWifi = false }, onScanWifi, onWifi)
    if (showRfid) RfidTestScreen(s, { showRfid = false }, onRfPower, onRfidTest)
    if (showFb) FirebaseDialog(s, { showFb = false }, onFbLogin, onFbLogout)
    if (showHost) HostDialog(s.host, { showHost = false }) { onHost(it); showHost = false }
    if (showManual) ManualFactorDialog(s.calibration, { showManual = false }) { cal.factor(it); showManual = false }
}

/** The "Scale" row: which scale this app talks to, how (Bluetooth / Wi-Fi), and the connection tools only the app needs. */
@Composable
private fun ScaleDialog(
    s: ScaleState,
    onDismiss: () -> Unit,
    onSearch: () -> Unit,
    onManualIp: () -> Unit,
    onPickScale: () -> Unit,
    onForget: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.row_scale)) },
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
                Text(
                    stringResource(
                        R.string.hw_status,
                        stringResource(if (s.readerOk) R.string.ok else R.string.not_detected),
                        stringResource(if (s.scaleOk) R.string.ok else R.string.no_response),
                    ),
                    fontSize = 12.sp, color = Color(0xFF8A93A6),
                )
                OutlinedButton(onClick = { onDismiss(); onPickScale() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.btn_add_scale))
                }
                if (!s.noScale) {
                    TextButton(onClick = { onForget(); onDismiss() }) { Text(stringResource(R.string.btn_forget_scale)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onSearch, enabled = !s.searching) { Text(stringResource(R.string.btn_search)) }
                    OutlinedButton(onClick = onManualIp) { Text(stringResource(R.string.btn_manual_ip)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) } },
    )
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
    var showPass by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    // Connected to the network that was just chosen: let the "connected" line show for a moment, then
    // close. Matching the SSID keeps a stale "connected" to the previous network from closing it early.
    LaunchedEffect(attempted, s.wifiState, s.ssid) {
        if (attempted && s.wifiState == 2 && s.ssid == ssid) {
            delay(1_200)
            onDismiss()
        }
    }
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
                    visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showPass = !showPass }) {
                            Text(stringResource(if (showPass) R.string.pw_hide else R.string.pw_show), fontSize = 12.sp)
                        }
                    },
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
        confirmButton = {
            TextButton(onClick = { if (ssid.isNotBlank()) { attempted = true; onOk(ssid, pass) } }) { Text(stringResource(R.string.btn_connect)) }
        },
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

/** Types the load-cell factor directly (the original's "manual calibration"). */
@Composable
private fun ManualFactorDialog(current: Double, onDismiss: () -> Unit, onOk: (Float) -> Unit) {
    var text by remember { mutableStateOf("") }
    val factor = text.replace(',', '.').toFloatOrNull()?.takeIf { it > 0f }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cal_manual)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.cal_factor_current, "%.4f".format(Locale.US, current)),
                    fontSize = 13.sp, color = Color(0xFF8A93A6),
                )
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    label = { Text(stringResource(R.string.cal_factor_new)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { factor?.let(onOk) }, enabled = factor != null) { Text(stringResource(R.string.apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** In-app language, independent of the phone's (Android 13+ per-app locales; same nine as the scale). */
@Composable
private fun LanguageDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val options = listOf("" to stringResource(R.string.language_system)) + APP_LANGUAGES
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
