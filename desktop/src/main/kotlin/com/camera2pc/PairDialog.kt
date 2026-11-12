package com.camera2pc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.camera2pc.ui.components.CamIcons
import com.camera2pc.ui.components.ShapeIcon
import kotlinx.coroutines.launch

/** Сопряжение с телефоном по коду беспроводной отладки (adb pair). */
@Composable
fun PairDialog(engine: StreamEngine, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<String>?>(null) }
    val done = result?.isSuccess == true

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        shape = RoundedCornerShape(32.dp),
        containerColor = colors.surfaceContainerHigh,
        icon = { ShapeIcon(CamIcons.Smartphone, colors.primaryContainer, colors.onPrimaryContainer, size = 48.dp) },
        title = { Text("Подключить телефон по коду") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "На телефоне: Параметры разработчика → Беспроводная отладка → «Подключить устройство с помощью кода». " +
                        "Введите сюда IP-адрес с портом и шестизначный код из этого окна.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it.filter { c -> c.isDigit() || c == '.' || c == ':' } },
                    label = { Text("IP-адрес и порт") },
                    placeholder = { Text("192.168.1.50:37581") },
                    singleLine = true,
                    enabled = !busy && !done,
                    shape = RoundedCornerShape(16.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    label = { Text("Код сопряжения") },
                    placeholder = { Text("123456") },
                    singleLine = true,
                    enabled = !busy && !done,
                    shape = RoundedCornerShape(16.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadingIndicator(Modifier.size(32.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Сопрягаю…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                result?.let { r ->
                    Text(
                        r.getOrNull() ?: (r.exceptionOrNull()?.message ?: "Ошибка"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (r.isSuccess) colors.primary else colors.error,
                    )
                }
            }
        },
        confirmButton = {
            if (done) {
                Button(
                    onClick = {
                        onClose()
                        engine.settings.mode = Mode.Usb
                        engine.settings.save()
                        engine.stop()
                        engine.start()
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text("Подключить") }
            } else {
                Button(
                    onClick = {
                        busy = true
                        result = null
                        scope.launch {
                            result = engine.pair(address, code)
                            busy = false
                        }
                    },
                    enabled = !busy && ':' in address && code.length == 6,
                    shapes = ButtonDefaults.shapes(),
                ) { Text("Сопрячь") }
            }
        },
        dismissButton = {
            TextButton(onClick = onClose, enabled = !busy, shape = CircleShape) { Text("Закрыть") }
        },
    )
}
