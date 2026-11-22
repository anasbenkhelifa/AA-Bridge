package dev.caraalink.ui

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.caraalink.settings.AppSettings
import dev.caraalink.ui.theme.Dash
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.caraalink.R
import dev.caraalink.settings.SettingsActivity
import dev.caraalink.update.UpdateChecker
import dev.caraalink.shell.ShellBridge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import dev.caraalink.wireless.CarBluetooth
import dev.caraalink.wireless.RememberedCar

/** Page with a back arrow and a title; Settings, Help and About share it. */
@Composable
fun SubScreen(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize().dashGlow(Dash.GlowIdle), color = Color.Transparent, contentColor = Dash.Text) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, tint = Dash.Teal) }
                Spacer(Modifier.width(2.dp))
                Text(title.uppercase(), color = Dash.Teal, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), content = content)
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onHelp: () -> Unit, onAbout: () -> Unit, onCarButtons: () -> Unit) {
    val context = LocalContext.current
    SubScreen(stringResource(R.string.settings), onBack) {
        WirelessCarRow()
        DashGroup {
            SharperPictureRow()
            SettingsRow(painterResource(R.drawable.ic_touch_app), stringResource(R.string.settings_car_buttons), onCarButtons)
            WebSignInRow(R.string.settings_signin_youtube, dev.caraalink.video.CarWebPresentation.YOUTUBE)
            WebSignInRow(R.string.settings_signin_claude, dev.caraalink.video.CarWebPresentation.CLAUDE)
            CarAppsRow()
        }
        DashGroup {
            SettingsRow(rememberVectorPainter(Icons.Rounded.Refresh), stringResource(R.string.settings_updates)) {
                UpdateChecker.checkNow(context, manual = true)
            }
            SettingsRow(painterResource(R.drawable.ic_help), stringResource(R.string.help), onHelp)
            SettingsRow(rememberVectorPainter(Icons.Rounded.Info), stringResource(R.string.settings_about), onAbout)
            SettingsRow(painterResource(R.drawable.ic_tune), stringResource(R.string.settings_advanced)) {
                context.startActivity(Intent(context, SettingsActivity::class.java))
            }
        }
        Byline(Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
    }
}

/** The remembered car, with Forget; hidden when there is none. */
@Composable
private fun WirelessCarRow() {
    val context = LocalContext.current
    var car by remember { mutableStateOf(AppSettings.wirelessCar(context)) }
    val name = car?.name ?: return
    DashGroup {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(stringResource(R.string.settings_wireless_car), style = MaterialTheme.typography.bodyLarge) },
            supportingContent = { Text(name) },
            leadingContent = { Icon(painterResource(R.drawable.ic_car), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = {
                TextButton(onClick = {
                    AppSettings.setWirelessCar(context, null)
                    car = null
                }) { Text(stringResource(R.string.settings_forget), fontWeight = FontWeight.Bold) }
            },
        )
        BluetoothConnectRow()
    }
}

/** Connect by itself when the phone joins the car's Bluetooth: on once a paired device is chosen as the car. */
@Composable
private fun BluetoothConnectRow() {
    val context = LocalContext.current
    var device by remember { mutableStateOf(AppSettings.carBluetooth(context)) }
    var choices by remember { mutableStateOf<List<RememberedCar>?>(null) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) choices = CarBluetooth.paired(context)
    }
    val set: (Boolean) -> Unit = { on ->
        when {
            !on -> {
                AppSettings.setCarBluetooth(context, null)
                device = null
            }
            CarBluetooth.hasPermission(context) -> choices = CarBluetooth.paired(context)
            else -> askPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(stringResource(R.string.settings_bt_title), style = MaterialTheme.typography.bodyLarge) },
        supportingContent = {
            val name = device?.name
            Text(if (name == null) stringResource(R.string.settings_bt_off) else stringResource(R.string.settings_bt_on, name))
        },
        leadingContent = { Icon(painterResource(R.drawable.ic_wifi), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Switch(checked = device != null, onCheckedChange = set) },
        modifier = Modifier.clickable { set(device == null) },
    )
    choices?.let { list ->
        AlertDialog(
            onDismissRequest = { choices = null },
            title = { Text(stringResource(R.string.bt_pick_title)) },
            text = {
                // Ten actions, or a long list of paired devices, don't fit a small phone.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (list.isEmpty()) Text(stringResource(R.string.bt_pick_empty))
                    for (choice in list) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(choice.name) },
                            modifier = Modifier.fillMaxWidth().clickable {
                                AppSettings.setCarBluetooth(context, choice)
                                device = choice
                                choices = null
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choices = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** 1080p instead of 720p. A running Android Auto session restarts to pick it up, as it does from Advanced. */
@Composable
private fun SharperPictureRow() {
    val context = LocalContext.current
    var sharp by remember { mutableStateOf(!AppSettings.videoDownscale720p(context)) }
    val set: (Boolean) -> Unit = { value ->
        sharp = value
        AppSettings.setVideoDownscale720p(context, !value)
        dev.caraalink.aa.AaReconnectBus.onVideoSettingsChanged?.invoke()
    }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(stringResource(R.string.settings_sharp_title), style = MaterialTheme.typography.bodyLarge) },
        supportingContent = { Text(stringResource(R.string.settings_sharp_body)) },
        leadingContent = { Icon(painterResource(R.drawable.ic_devices), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Switch(checked = sharp, onCheckedChange = set) },
        modifier = Modifier.clickable { set(!sharp) },
    )
}

/** Opens a site in the car's browser, on the phone, to sign in with a real keyboard. */
@Composable
private fun WebSignInRow(label: Int, url: String) {
    val context = LocalContext.current
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(stringResource(label), style = MaterialTheme.typography.bodyLarge) },
        supportingContent = { Text(stringResource(R.string.settings_signin_body)) },
        leadingContent = { Icon(painterResource(R.drawable.ic_devices), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable {
            context.startActivity(Intent(context, WebSignInActivity::class.java).putExtra(WebSignInActivity.EXTRA_URL, url))
        },
    )
}

/** Apps on the car screen need Shizuku: says what is missing, and a tap does the next step. */
@Composable
private fun CarAppsRow() {
    val context = LocalContext.current
    var state by remember { mutableStateOf(ShellBridge.state(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                state = ShellBridge.state(context)
                if (state == ShellBridge.State.READY) ShellBridge.connect(context)
                delay(1_500)
            }
        }
    }
    val (summary, action) = when (state) {
        ShellBridge.State.READY -> R.string.apps_ready to {}
        ShellBridge.State.NEEDS_PERMISSION -> R.string.apps_need_allow to { ShellBridge.requestPermission() }
        ShellBridge.State.NOT_RUNNING -> R.string.apps_need_start to {
            context.packageManager.getLaunchIntentForPackage(ShellBridge.SHIZUKU_PACKAGE)?.let { context.startActivity(it) }
            Unit
        }
        ShellBridge.State.NOT_INSTALLED -> R.string.apps_need_install to {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app/download/"))) }
            Unit
        }
    }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(stringResource(R.string.settings_car_apps), style = MaterialTheme.typography.bodyLarge) },
        supportingContent = { Text(stringResource(summary)) },
        leadingContent = { Icon(painterResource(R.drawable.ic_devices), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable(onClick = action),
    )
}

/** A dashboard tile holding a few rows. */
@Composable
internal fun DashGroup(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(shape).background(Dash.Tile).border(1.dp, Dash.TileBorder, shape),
        content = content,
    )
}

@Composable
private fun SettingsRow(icon: Painter, label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}
