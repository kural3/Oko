package com.camera2pc

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.camera2pc.ui.components.CamIcons
import com.camera2pc.ui.components.ConnectedChoice
import com.camera2pc.ui.components.ShapeIcon
import com.camera2pc.ui.components.StatValue
import com.camera2pc.ui.components.StatusPill
import com.camera2pc.ui.components.Tile
import com.camera2pc.ui.components.TileShape
import com.camera2pc.ui.theme.Camera2PCTheme
import kotlinx.coroutines.launch

private enum class Page { Home, Camera, Settings }

fun main() = application {
    val settings = remember { Settings() }
    val engine = remember { StreamEngine(settings) }
    val state = rememberWindowState(size = DpSize(1200.dp, 800.dp), position = WindowPosition(Alignment.Center))
    val close = {
        engine.shutdown()
        settings.save()
        exitApplication()
    }
    LaunchedEffect(Unit) {
        if (System.getenv("OKO_DEMO") == "1") engine.startDemo()
        else if (settings.autostart && (settings.mode == Mode.Usb || settings.host.isNotBlank())) engine.start()
    }
    LaunchedEffect(Unit) {
        Log.i("Oko ${Updates.current} запущен")
        if (settings.checkUpdates && System.getenv("OKO_DEMO") != "1") Updates.check()
    }
    Window(
        onCloseRequest = close,
        state = state,
        title = "Oko",
        icon = androidx.compose.ui.res.painterResource("oko.png"),
    ) {
        LaunchedEffect(Unit) {
            window.minimumSize = java.awt.Dimension(900, 600)
            WindowChrome.install(window, state, close)
        }
        // свёрнуто — превью не рисуем, телефон опрашиваем реже
        LaunchedEffect(state.isMinimized) {
            engine.windowVisible = !state.isMinimized
            engine.control.slow = state.isMinimized
        }
        val scheme by WallpaperTheme.scheme()
        Camera2PCTheme(scheme) {
            App(engine)
        }
    }
}

@Composable
private fun App(engine: StreamEngine) {
    var page by remember {
        mutableStateOf(Page.entries.firstOrNull { it.name.equals(System.getenv("OKO_PAGE"), ignoreCase = true) } ?: Page.Home)
    }
    val controls = WindowChrome.controls.value
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .titleBarHitTest()
    ) {
        Row(Modifier.fillMaxSize()) {
            Rail(page, onSelect = { page = it })
            AnimatedContent(
                targetState = page,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                label = "page",
            ) { p ->
                when (p) {
                    Page.Home -> HomePage(engine)
                    Page.Camera -> CameraPage(engine)
                    Page.Settings -> SettingsPage(engine)
                }
            }
        }
        if (controls != null) WindowButtons(controls, Modifier.align(Alignment.TopEnd))
    }
}

/** Узкая панель слева, как в Pier.doon: логотип и разделы. */
@Composable
private fun Rail(page: Page, onSelect: (Page) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(76.dp)
            .fillMaxHeight()
            .padding(vertical = 12.dp),
    ) {
        Image(
            androidx.compose.ui.graphics.vector.rememberVectorPainter(com.camera2pc.ui.components.OkoLogo),
            contentDescription = "Oko",
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(20.dp))
        RailItem(CamIcons.Home, "Главная", page == Page.Home) { onSelect(Page.Home) }
        RailItem(CamIcons.Tune, "Настройки камеры", page == Page.Camera) { onSelect(Page.Camera) }
        Spacer(Modifier.weight(1f))
        Box {
            RailItem(CamIcons.Settings, "Настройки", page == Page.Settings) { onSelect(Page.Settings) }
            // вышла новая версия — точка на «Настройках»
            if (Updates.available != null) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp).size(10.dp)
                        .clip(CircleShape).background(colors.error)
                )
            }
        }
    }
}

@Composable
private fun RailItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (selected) colors.secondaryContainer else Color.Transparent, label = "rail")
    val fg by animateColorAsState(if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant, label = "railFg")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(vertical = 4.dp)
            .size(48.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick),
    ) {
        Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun HomePage(engine: StreamEngine) {
    val colors = MaterialTheme.colorScheme
    val s = engine.settings
    Column(Modifier.fillMaxSize().padding(start = 8.dp, end = 20.dp, bottom = 16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(WindowChrome.HEIGHT + 20.dp).padding(top = 14.dp),
        ) {
            Text("Камера телефона", style = MaterialTheme.typography.headlineMedium)
        }

        // плитки, как на главной Pier.doon
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().height(132.dp)) {
            val live = engine.phase == Phase.Live
            Tile(
                title = when (engine.phase) {
                    Phase.Live -> "Телефон подключён"
                    Phase.Connecting -> "Подключение…"
                    Phase.Error -> "Нет связи"
                    Phase.Stopped -> "Не подключено"
                    Phase.Paused -> "Пауза"
                },
                subtitle = when {
                    engine.message.isNotEmpty() -> engine.message
                    s.mode == Mode.Usb -> "Через ADB"
                    else -> "По Wi‑Fi"
                },
                icon = CamIcons.Smartphone,
                container = if (engine.phase == Phase.Error) colors.errorContainer else colors.primaryContainer,
                onContainer = if (engine.phase == Phase.Error) colors.onErrorContainer else colors.onPrimaryContainer,
                decor = MaterialShapes.Cookie12Sided.toShape(),
                modifier = Modifier.weight(1.3f).fillMaxHeight(),
            )
            Tile(
                title = "Камера Oko Camera",
                subtitle = if (engine.cameraInUse) "Сейчас используется программой" else "Выберите её в Zoom, Discord, Teams…",
                icon = CamIcons.Videocam,
                container = colors.tertiaryContainer,
                onContainer = colors.onTertiaryContainer,
                decor = MaterialShapes.Sunny.toShape(),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            Tile(
                title = "Поток",
                icon = CamIcons.Speed,
                container = colors.surfaceContainerHigh,
                onContainer = colors.onSurface,
                decor = MaterialShapes.Clover4Leaf.toShape(),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    StatValue("к/с", if (live) "${engine.fps}" else "—")
                    StatValue("Мбит/с", if (live) "%.1f".format(engine.mbps) else "—")
                    if (live && engine.frameWidth > 0) StatValue("кадр", "${engine.frameWidth}×${engine.frameHeight}")
                    if (engine.control.online && engine.control["battery"] != null) {
                        StatValue("заряд", "${engine.control.int("battery")}%")
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        PreviewCard(engine, Modifier.weight(1f).fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        ControlBar(engine)
    }
}

@Composable
private fun PreviewCard(engine: StreamEngine, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.clip(TileShape).background(colors.surfaceContainer),
    ) {
        val image = engine.preview
        if (image != null && engine.phase == Phase.Live) {
            Image(image, contentDescription = "Превью", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (engine.phase == Phase.Connecting) {
                    LoadingIndicator(modifier = Modifier.size(72.dp))
                } else {
                    ShapeIcon(
                        CamIcons.Videocam,
                        container = colors.surfaceContainerHighest,
                        content = colors.onSurfaceVariant,
                        size = 88.dp,
                        shape = MaterialShapes.Cookie9Sided.toShape(),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    when (engine.phase) {
                        Phase.Connecting -> "Подключаюсь к телефону…"
                        Phase.Paused -> "Пауза: камерой никто не пользуется — телефон отдыхает"
                        Phase.Error -> engine.message
                        else -> "Откройте Oko на телефоне и нажмите «Подключить»"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        val live = engine.phase == Phase.Live
        StatusPill(
            text = when {
                live && engine.cameraInUse -> "В эфире · камера используется"
                live -> "Подключено"
                engine.phase == Phase.Connecting -> "Подключение"
                engine.phase == Phase.Paused -> "Пауза"
                else -> "Не подключено"
            },
            live = live,
            warn = engine.phase == Phase.Error,
            modifier = Modifier.align(Alignment.TopStart).padding(14.dp),
        )
    }
}

/** Нижняя капсула, как плеер в Pier.doon: способ связи и адрес, большая кнопка, поворот и зеркало. */
@Composable
private fun ControlBar(engine: StreamEngine) {
    val colors = MaterialTheme.colorScheme
    val s = engine.settings
    val running = engine.running
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(colors.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Box(Modifier.width(210.dp)) {
            ConnectedChoice(
                options = listOf("Wi‑Fi", "ADB"),
                selected = s.mode.ordinal,
                onSelect = {
                    s.mode = Mode.entries[it]
                    s.save()
                    if (running) { engine.stop(); engine.start() }
                },
                containerColor = colors.surfaceContainerHigh,
            )
        }
        Spacer(Modifier.width(10.dp))
        if (s.mode == Mode.Wifi) {
            OutlinedTextField(
                value = s.host,
                onValueChange = { s.host = it.filter { c -> c.isDigit() || c == '.' } },
                enabled = !running,
                singleLine = true,
                placeholder = { Text("IP телефона, напр. 192.168.1.50") },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                shape = CircleShape,
                keyboardActions = KeyboardActions(onDone = { engine.start() }),
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = colors.outlineVariant),
                modifier = Modifier.widthIn(min = 200.dp, max = 300.dp),
            )
        } else {
            var pairing by remember { mutableStateOf(false) }
            Column {
                Text(
                    if (running && engine.link.isNotEmpty()) engine.link.replaceFirstChar { it.uppercase() } else "Беспроводная отладка или кабель",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    "Подключить новый телефон по коду",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.primary,
                    modifier = Modifier.clip(CircleShape).clickable { pairing = true }.padding(vertical = 2.dp),
                )
            }
            if (pairing) PairDialog(engine) { pairing = false }
        }

        Spacer(Modifier.weight(1f))
        Button(
            onClick = { if (running) engine.stop() else engine.start() },
            shapes = ButtonDefaults.shapes(),
            colors = if (running) ButtonDefaults.buttonColors(
                containerColor = colors.secondaryContainer, contentColor = colors.onSecondaryContainer,
            ) else ButtonDefaults.buttonColors(),
            contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
            modifier = Modifier.height(ButtonDefaults.MediumContainerHeight),
        ) {
            Icon(if (running) CamIcons.Stop else CamIcons.Play, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (running) "Отключить" else "Подключить", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.weight(1f))

        val c = engine.control
        FilledIconToggleButton(
            checked = c.cameras().firstOrNull { it.first == c["camera"] }?.second?.startsWith("Фронт") == true,
            onCheckedChange = { c.nextCamera() },
            enabled = c.online,
            shapes = IconButtonDefaults.toggleableShapes(),
            modifier = Modifier.size(48.dp),
        ) { Icon(CamIcons.SwitchCamera, contentDescription = "Сменить камеру") }
        Spacer(Modifier.width(6.dp))
        FilledIconToggleButton(
            checked = c.flag("torch"),
            onCheckedChange = { c.set("torch" to if (it) 1 else 0) },
            enabled = c.online && c.flag("flash"),
            shapes = IconButtonDefaults.toggleableShapes(),
            modifier = Modifier.size(48.dp),
        ) { Icon(CamIcons.Flash, contentDescription = "Фонарик") }
        Spacer(Modifier.width(6.dp))
        FilledIconToggleButton(
            checked = s.rotate != 0,
            onCheckedChange = { s.rotate = (s.rotate + 90) % 360; s.save() },
            shapes = IconButtonDefaults.toggleableShapes(),
            modifier = Modifier.size(48.dp),
        ) { Icon(CamIcons.Rotate, contentDescription = "Повернуть") }
        if (s.rotate != 0) {
            Text("${s.rotate}°", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 6.dp))
        }
        Spacer(Modifier.width(6.dp))
        FilledIconToggleButton(
            checked = s.mirror,
            onCheckedChange = { s.mirror = it; s.save() },
            shapes = IconButtonDefaults.toggleableShapes(),
            modifier = Modifier.size(48.dp),
        ) { Icon(CamIcons.Flip, contentDescription = "Зеркально") }
    }
}

@Composable
private fun SettingsPage(engine: StreamEngine) {
    val colors = MaterialTheme.colorScheme
    val s = engine.settings
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 8.dp, end = 20.dp, bottom = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(WindowChrome.HEIGHT + 20.dp).padding(top = 14.dp)) {
            Text("Настройки", style = MaterialTheme.typography.headlineMedium)
        }
        SettingRow(CamIcons.Play, "Подключаться при запуске", "Сразу соединяться с телефоном, когда открывается программа") {
            Switch(checked = s.autostart, onCheckedChange = { s.autostart = it; s.save() })
        }
        SettingRow(
            CamIcons.Battery,
            "Экономия энергии",
            "Когда окно свёрнуто и камерой никто не пользуется — видео не передаётся, телефон и ПК отдыхают. " +
                "Как только Discord, Zoom и т. п. откроют камеру, поток включится сам.",
        ) {
            Switch(checked = s.powerSave, onCheckedChange = { s.powerSave = it; s.save() })
        }
        var pairing by remember { mutableStateOf(false) }
        SettingRow(CamIcons.Smartphone, "Подключить телефон по ADB", "Сопряжение по коду из «Беспроводной отладки» — нужно один раз") {
            FilledTonalButton(onClick = { pairing = true }, shapes = ButtonDefaults.shapes()) { Text("Сопряжение") }
        }
        if (pairing) PairDialog(engine) { pairing = false }
        SettingRow(CamIcons.Link, "Порт", "Тот же, что на экране телефона (обычно 8080)") {
            OutlinedTextField(
                value = s.port.toString(),
                onValueChange = { v -> v.filter(Char::isDigit).take(5).toIntOrNull()?.let { s.port = it; s.save() } },
                singleLine = true,
                shape = CircleShape,
                modifier = Modifier.width(110.dp),
            )
        }
        val update = Updates.available
        SettingRow(
            CamIcons.Refresh,
            if (update != null) "Доступна версия ${update.version}" else "Обновления",
            "Сейчас установлена ${Updates.current}." +
                (if (Updates.status.isNotEmpty() && update == null) " ${Updates.status}." else "") +
                " Раз в запуск Oko смотрит на GitHub, не вышла ли новая.",
        ) {
            val scope = rememberCoroutineScope()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (update != null) {
                    Button(onClick = { Updates.openPage() }, shapes = ButtonDefaults.shapes()) { Text("Скачать") }
                } else {
                    FilledTonalButton(
                        onClick = { scope.launch { Updates.check() } },
                        enabled = !Updates.checking,
                        shapes = ButtonDefaults.shapes(),
                    ) { Text(if (Updates.checking) "Проверяю…" else "Проверить") }
                }
                Switch(checked = s.checkUpdates, onCheckedChange = { s.checkUpdates = it; s.save() })
            }
        }
        SettingRow(CamIcons.Info, "Журнал", "Что делал adb и почему не было связи. Пригодится, если что-то не работает") {
            FilledTonalButton(onClick = { Log.reveal() }, shapes = ButtonDefaults.shapes()) { Text("Открыть") }
        }
        Spacer(Modifier.height(6.dp))
        Tile(
            title = "Как пользоваться",
            subtitle = "1. Откройте Oko на телефоне.  2. Введите его IP или выберите ADB и нажмите «Подключить».  " +
                "3. В нужной программе выберите камеру «Oko Camera».  ISO, выдержка, фокус и выбор камеры — в разделе «Настройки камеры».",
            icon = CamIcons.Info,
            container = colors.secondaryContainer,
            onContainer = colors.onSecondaryContainer,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, trailing: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        ShapeIcon(icon, colors.surfaceContainerHighest, colors.onSurfaceVariant, size = 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        trailing()
    }
}
