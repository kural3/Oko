package com.camera2pc

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.camera2pc.ui.components.CamIcons
import com.camera2pc.ui.components.ShapeIcon
import kotlinx.coroutines.delay

/** Что включено на телефоне — читается из системных настроек, обновляется раз в 2 с. */
private data class DebugState(val developer: Boolean, val usb: Boolean, val wireless: Boolean)

private fun readDebugState(context: Context): DebugState {
    val r = context.contentResolver
    fun global(name: String) = runCatching { Settings.Global.getInt(r, name, 0) == 1 }.getOrDefault(false)
    return DebugState(
        developer = global(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED),
        usb = global(Settings.Global.ADB_ENABLED),
        wireless = global("adb_wifi_enabled"),
    )
}

private fun open(context: Context, action: String) {
    try {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Пошаговая инструкция: как подключить телефон к Oko на компьютере через ADB. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdbGuide(onClose: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var state by remember { mutableStateOf(readDebugState(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            state = readDebugState(context)
            delay(2000)
        }
    }
    val wirelessSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapeIcon(CamIcons.Link, colors.primaryContainer, colors.onPrimaryContainer, size = 48.dp, shape = MaterialShapes.Cookie9Sided.toShape())
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Подключение через ADB", style = MaterialTheme.typography.headlineSmallEmphasized)
                    Text(
                        "Самый надёжный способ: работает даже с VPN. Настраивается один раз.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                FilledTonalIconButton(onClick = onClose, shapes = androidx.compose.material3.IconButtonDefaults.shapes()) {
                    Icon(CamIcons.Check, contentDescription = "Готово")
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip("Режим разработчика", state.developer)
                if (wirelessSupported) StatusChip("Беспроводная отладка", state.wireless)
                StatusChip("Отладка по USB", state.usb)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                    Step(
                        n = 1,
                        title = "Включите режим разработчика",
                        done = state.developer,
                        text = "Настройки → Сведения о телефоне → Сведения о ПО → 7 раз нажмите «Номер сборки» и введите PIN.",
                        action = "Сведения о телефоне" to { open(context, Settings.ACTION_DEVICE_INFO_SETTINGS) },
                    )
                    Step(
                        n = 2,
                        title = if (wirelessSupported) "Включите «Беспроводную отладку»" else "Включите «Отладку по USB»",
                        done = if (wirelessSupported) state.wireless else state.usb,
                        text = if (wirelessSupported) {
                            "Параметры разработчика → «Беспроводная отладка» → включить. Телефон и компьютер должны быть в одной сети Wi‑Fi."
                        } else {
                            "Параметры разработчика → «Отладка по USB» → включить, затем подключите кабель."
                        },
                        action = "Параметры разработчика" to { open(context, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) },
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                    Step(
                        n = 3,
                        title = "Сопрягите с компьютером",
                        done = false,
                        text = "Один раз. На телефоне: «Беспроводная отладка» → «Подключить устройство с помощью кода». " +
                            "В Oko на компьютере: внизу выберите ADB → «Подключить новый телефон по коду» и введите IP-адрес с портом и код.",
                    )
                    Step(
                        n = 4,
                        title = "Нажмите «Подключить» в Oko на ПК",
                        done = false,
                        text = "Готово — дальше просто открывайте Oko на телефоне и на компьютере.",
                    )
                }
            }

            Tip(
                "Кабель тоже подойдёт",
                "Включите «Отладку по USB», подключите кабель (режим «Передача файлов») и разрешите отладку — Oko сам переключится на кабель.",
            )
            Tip(
                "После перезагрузки телефона",
                "Android выключает беспроводную отладку — включите её снова (шаг 2). Сопрягать заново не нужно.",
            )
        }
    }
}

@Composable
private fun StatusChip(label: String, on: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (on) colors.primaryContainer else colors.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (on) colors.primary else colors.outline),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "$label: ${if (on) "вкл" else "выкл"}",
            style = MaterialTheme.typography.labelLarge,
            color = if (on) colors.onPrimaryContainer else colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun Step(n: Int, title: String, done: Boolean, text: String, action: Pair<String, () -> Unit>? = null) {
    val colors = MaterialTheme.colorScheme
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(colors.surfaceContainer)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(32.dp)
                    .clip(MaterialShapes.Cookie9Sided.toShape())
                    .background(if (done) colors.primary else colors.secondaryContainer),
            ) {
                if (done) {
                    Icon(CamIcons.Check, null, tint = colors.onPrimary, modifier = Modifier.size(18.dp))
                } else {
                    Text("$n", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = colors.onSecondaryContainer)
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        if (action != null) {
            FilledTonalButton(onClick = action.second, shapes = ButtonDefaults.shapes(), modifier = Modifier.widthIn(min = 120.dp)) {
                Text(action.first)
            }
        }
    }
}

@Composable
private fun Tip(title: String, text: String) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(colors.tertiaryContainer.copy(alpha = 0.55f))
            .padding(16.dp),
    ) {
        Icon(CamIcons.Info, null, tint = colors.onTertiaryContainer, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onTertiaryContainer)
            Spacer(Modifier.height(2.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, color = colors.onTertiaryContainer.copy(alpha = 0.85f))
        }
    }
}

