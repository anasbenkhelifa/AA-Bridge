package dev.caraalink.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.caraalink.R
import dev.caraalink.settings.CarKeyAction
import dev.caraalink.settings.CarKeys
import dev.caraalink.ui.theme.Dash

/** Buttons the car has sent, live, so the screen can show a press as it happens. */
@Composable
private fun rememberPressed(): CarKeys.Pressed {
    var pressed by remember { mutableStateOf(CarKeys.pressed) }
    DisposableEffect(Unit) {
        val main = Handler(Looper.getMainLooper())
        val listener: (CarKeys.Pressed) -> Unit = { p -> main.post { pressed = p } }
        CarKeys.addListener(listener)
        onDispose { CarKeys.removeListener(listener) }
    }
    return pressed
}

/** The buttons to list: those with a job already, then anything else pressed since the app started. */
fun buttonsToShow(mapping: Map<Int, CarKeyAction>, seen: Set<Int>): List<Int> = (mapping.keys + seen).sorted()

@Composable
private fun buttonName(key: Int): String = when (key) {
    CarKeys.KEY_HOME -> stringResource(R.string.buttons_home)
    CarKeys.KEY_BACK -> stringResource(R.string.buttons_back)
    else -> stringResource(R.string.buttons_number, key)
}

@Composable
private fun actionName(action: CarKeyAction): String = stringResource(
    when (action) {
        CarKeyAction.NONE -> R.string.action_none
        CarKeyAction.CLOSE_AA -> R.string.action_close
        CarKeyAction.BACK -> R.string.action_back
        CarKeyAction.HOME -> R.string.action_home
        CarKeyAction.VOICE -> R.string.action_voice
        CarKeyAction.PLAY_PAUSE -> R.string.action_play_pause
        CarKeyAction.NEXT -> R.string.action_next
        CarKeyAction.PREVIOUS -> R.string.action_previous
        CarKeyAction.ANSWER_CALL -> R.string.action_call
        CarKeyAction.END_CALL -> R.string.action_end_call
    },
)

/** Settings > Car buttons: press a button in the car, then choose what it does in Android Auto. */
@Composable
fun CarButtonsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val pressed = rememberPressed()
    var mapping by remember { mutableStateOf(CarKeys.mapping(context)) }
    var editing by remember { mutableStateOf<Int?>(null) }

    SubScreen(stringResource(R.string.settings_car_buttons), onBack) {
        Text(
            stringResource(R.string.buttons_hint),
            Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            color = Dash.Muted, fontSize = 14.sp,
        )
        DashGroup {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                overlineContent = { Text(stringResource(R.string.buttons_last)) },
                headlineContent = {
                    val last = pressed.last
                    Text(
                        if (last == null) stringResource(R.string.buttons_none_yet) else buttonName(last),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (last == null) Dash.Muted else Dash.Teal,
                    )
                },
            )
        }
        DashGroup {
            for (key in buttonsToShow(mapping, pressed.seen)) {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(buttonName(key), style = MaterialTheme.typography.bodyLarge) },
                    supportingContent = { Text(actionName(mapping[key] ?: CarKeyAction.NONE)) },
                    modifier = Modifier.clickable { editing = key },
                )
            }
        }
        Text(
            stringResource(R.string.buttons_note),
            Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            color = Dash.Muted, fontSize = 12.sp,
        )
    }

    editing?.let { key ->
        val current = mapping[key] ?: CarKeyAction.NONE
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(buttonName(key)) },
            text = {
                // Ten actions, or a long list of paired devices, don't fit a small phone.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    for (action in CarKeyAction.values()) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            headlineContent = { Text(actionName(action)) },
                            leadingContent = { RadioButton(selected = action == current, onClick = null) },
                            modifier = Modifier.fillMaxWidth().clickable {
                                CarKeys.setAction(context, key, action)
                                mapping = CarKeys.mapping(context)
                                editing = null
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
