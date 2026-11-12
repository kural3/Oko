package com.camera2pc

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.camera2pc.ui.components.CamIcons
import com.camera2pc.ui.components.ChoiceChip
import com.camera2pc.ui.components.ConnectedChoice
import com.camera2pc.ui.components.ShapeIcon
import com.camera2pc.ui.components.TileShape
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Все настройки камеры: слева — телефон (ISO, выдержка, фокус…), справа — обработка на ПК. */
@Composable
fun CameraPage(engine: StreamEngine) {
    val c = engine.control
    Column(Modifier.fillMaxSize().padding(start = 8.dp, end = 20.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(WindowChrome.HEIGHT + 20.dp).padding(top = 14.dp),
        ) {
            Text("Настройки камеры", style = MaterialTheme.typography.headlineMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.weight(1f)) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1.15f).verticalScroll(rememberScrollState()).padding(bottom = 20.dp),
            ) {
                if (c.online) PhoneSettings(c) else OfflineCard(engine)
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 20.dp),
            ) {
                PcSettings(engine)
            }
        }
    }
}

@Composable
private fun OfflineCard(engine: StreamEngine) {
    val colors = MaterialTheme.colorScheme
    Card("Телефон не подключён", CamIcons.Smartphone) {
        Text(
            "Подключите телефон на главной странице — здесь появятся камеры, ISO, выдержка, баланс белого, фокус и остальное.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        if (!engine.running) {
            FilledTonalButton(onClick = { engine.start() }, shapes = ButtonDefaults.shapes()) {
                Icon(CamIcons.Play, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Подключить")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhoneSettings(c: PhoneControl) {
    val colors = MaterialTheme.colorScheme

    // ---------- камера и поток ----------
    Card("Камера телефона", CamIcons.Videocam) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            c.cameras().forEach { (id, name) ->
                ChoiceChip(selected = c["camera"] == id, label = name, onClick = { c.set("camera" to id) })
            }
        }
        Label("Разрешение")
        val res = listOf("SD" to "540p", "HD" to "720p", "FullHD" to "1080p")
        ConnectedChoice(
            options = res.map { it.second },
            selected = res.indexOfFirst { it.first == c["res"] }.coerceAtLeast(0),
            onSelect = { c.set("res" to res[it].first) },
            containerColor = colors.surfaceContainerHighest,
        )
        val fpsAll = c.ints("fpsList")
        val fpsOptions = listOf(15, 24, 30, 60).filter { it in fpsAll || it == c.int("fps") }
        if (fpsOptions.size > 1) {
            Label("Кадров в секунду")
            ConnectedChoice(
                options = fpsOptions.map { "$it" },
                selected = fpsOptions.indexOf(c.int("fps")).coerceAtLeast(0),
                onSelect = { c.set("fps" to fpsOptions[it]) },
                containerColor = colors.surfaceContainerHighest,
            )
        }
        Label("Качество сжатия")
        val q = listOf("Normal" to "Обычное", "High" to "Высокое", "Max" to "Макс")
        ConnectedChoice(
            options = q.map { it.second },
            selected = q.indexOfFirst { it.first == c["quality"] }.coerceAtLeast(0),
            onSelect = { c.set("quality" to q[it].first) },
            containerColor = colors.surfaceContainerHighest,
        )
    }

    // ---------- экспозиция ----------
    Card("Экспозиция", CamIcons.Sun) {
        val manual = c["ae"] == "manual"
        val canManual = c.flag("manualSensor")
        ConnectedChoice(
            options = listOf("Авто", "Ручная (ISO и выдержка)"),
            selected = if (manual) 1 else 0,
            onSelect = { c.set("ae" to if (it == 1) "manual" else "auto") },
            enabled = canManual,
            containerColor = colors.surfaceContainerHighest,
        )
        Hint("Сейчас: ISO ${c.int("liveIso")} · выдержка ${shutter(c.long("liveExp"))}")
        if (manual && canManual) {
            val isoMin = c.int("isoMin", 50).coerceAtLeast(1)
            val isoMax = c.int("isoMax", 3200).coerceAtLeast(isoMin + 1)
            LogSlider(
                label = "ISO",
                value = c.int("iso").toDouble(),
                min = isoMin.toDouble(),
                max = isoMax.toDouble(),
                text = { "${it.roundToInt()}" },
                onChange = { c.set("iso" to it.roundToInt()) },
            )
            val expMin = c.long("expMin", 100_000).coerceAtLeast(1)
            // длиннее 1/2 с для видео бессмысленно — кадры станут очень редкими
            val expMax = c.long("expMax", 100_000_000).coerceAtMost(500_000_000).coerceAtLeast(expMin + 1)
            LogSlider(
                label = "Выдержка",
                value = c.long("exp").toDouble(),
                min = expMin.toDouble(),
                max = expMax.toDouble(),
                text = { shutter(it.roundToLong()) },
                onChange = { c.set("exp" to it.roundToLong()) },
            )
            Hint("Выдержка длиннее 1/${c.int("fps", 30)} с снижает частоту кадров.")
        } else {
            val step = c.float("evStep", 0.1f)
            val evMin = c.int("evMin")
            val evMax = c.int("evMax")
            if (evMax > evMin) {
                ValueSlider(
                    label = "Компенсация",
                    value = c.int("ev").toFloat(),
                    range = evMin.toFloat()..evMax.toFloat(),
                    text = { "%+.1f EV".format(it * step) },
                    onChange = { c.set("ev" to it.roundToInt()) },
                )
            }
            SwitchLine("Зафиксировать экспозицию", c.flag("aeLock")) { c.set("aeLock" to if (it) 1 else 0) }
        }
        val ab = c.ints("antibandModes")
        if (ab.size > 1) {
            Label("Антимерцание (лампы)")
            val opts = listOf(3 to "Авто", 1 to "50 Гц", 2 to "60 Гц", 0 to "Выкл").filter { it.first in ab }
            ConnectedChoice(
                options = opts.map { it.second },
                selected = opts.indexOfFirst { it.first == c.int("antiband") }.coerceAtLeast(0),
                onSelect = { c.set("antiband" to opts[it].first) },
                containerColor = colors.surfaceContainerHighest,
            )
        }
    }

    // ---------- баланс белого ----------
    Card("Баланс белого", CamIcons.Image) {
        val names = mapOf(
            1 to "Авто", 2 to "Лампа накаливания", 3 to "Флуоресцентная", 4 to "Тёплая флуоресцентная",
            5 to "Дневной свет", 6 to "Облачно", 7 to "Сумерки", 8 to "Тень",
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            c.ints("awbModes").forEach { m ->
                ChoiceChip(selected = c.int("awb") == m, label = names[m] ?: "Режим $m", onClick = { c.set("awb" to m) })
            }
        }
        SwitchLine("Зафиксировать баланс белого", c.flag("awbLock")) { c.set("awbLock" to if (it) 1 else 0) }
    }

    // ---------- фокус и зум ----------
    Card("Фокус и зум", CamIcons.Focus) {
        val focusMax = c.float("focusMax")
        if (focusMax > 0f) {
            val manual = c["af"] == "manual"
            ConnectedChoice(
                options = listOf("Автофокус", "Ручной"),
                selected = if (manual) 1 else 0,
                onSelect = { c.set("af" to if (it == 1) "manual" else "auto") },
                containerColor = colors.surfaceContainerHighest,
            )
            if (manual) {
                // слева — далеко (0 диоптрий = бесконечность), справа — близко
                ValueSlider(
                    label = "Расстояние",
                    value = c.float("focus"),
                    range = 0f..focusMax,
                    text = ::distance,
                    onChange = { c.set("focus" to "%.3f".format(java.util.Locale.ROOT, it)) },
                )
            } else {
                Hint("Сейчас: ${distance(c.float("liveFocus"))}")
            }
        } else {
            Hint("У этой камеры фокус фиксированный.")
        }
        val zMin = c.float("zoomMin", 1f)
        val zMax = c.float("zoomMax", 1f)
        if (zMax > zMin) {
            LogSlider(
                label = "Зум",
                value = c.float("zoom", 1f).toDouble(),
                min = zMin.toDouble(),
                max = zMax.toDouble(),
                text = { "%.1f×".format(it) },
                onChange = { c.set("zoom" to "%.2f".format(java.util.Locale.ROOT, it)) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(zMin, 1f, 2f, 3f, 5f).filter { it in zMin..zMax }.distinct().forEach { z ->
                    ChoiceChip(
                        selected = kotlin.math.abs(c.float("zoom", 1f) - z) < 0.05f,
                        label = "%.1f×".format(z).replace(".0×", "×"),
                        onClick = { c.set("zoom" to z) },
                    )
                }
            }
        }
    }

    // ---------- свет, стабилизация, обработка ----------
    Card("Свет и стабилизация", CamIcons.Flash) {
        if (c.flag("flash")) SwitchLine("Фонарик", c.flag("torch")) { c.set("torch" to if (it) 1 else 0) }
        if (c.flag("stabAvail")) SwitchLine("Стабилизация видео", c.flag("stab")) { c.set("stab" to if (it) 1 else 0) }
        if (c.flag("oisAvail")) SwitchLine("Оптическая стабилизация", c.flag("ois")) { c.set("ois" to if (it) 1 else 0) }
        ModeChoice(c, "Шумоподавление", "nr", "nrModes")
        ModeChoice(c, "Резкость", "edge", "edgeModes")
        val effects = mapOf(
            0 to "Нет", 1 to "Ч/Б", 2 to "Негатив", 3 to "Соляризация", 4 to "Сепия",
            5 to "Постеризация", 6 to "Белая доска", 7 to "Чёрная доска", 8 to "Аква",
        )
        val avail = c.ints("effects")
        if (avail.size > 1) {
            Label("Эффект")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                avail.sorted().forEach { m ->
                    ChoiceChip(selected = c.int("effect") == m, label = effects[m] ?: "$m", onClick = { c.set("effect" to m) })
                }
            }
        }
    }

    Card("Телефон", CamIcons.Smartphone) {
        SwitchLine("Поворот вслед за телефоном", c.flag("autorot"), "Держите вертикально — видео станет вертикальным") {
            c.set("autorot" to if (it) 1 else 0)
        }
        SwitchLine("Притушить экран телефона", c.flag("dim"), "Камера продолжает снимать, телефон меньше греется") {
            c.set("dim" to if (it) 1 else 0)
        }
        if (c["battery"] != null) {
            Hint(
                "Заряд ${c.int("battery")}%" + (if (c.flag("charging")) " (заряжается)" else "") +
                    " · температура ${"%.1f".format(c.float("temp"))} °C"
            )
        }
        FilledTonalButton(onClick = { c.set("reset" to 1) }, shapes = ButtonDefaults.shapes()) {
            Icon(CamIcons.Refresh, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Сбросить настройки камеры")
        }
    }
}

/** Выбор режима Camera2 (шумоподавление/резкость): Авто + то, что поддерживает камера. */
@Composable
private fun ModeChoice(c: PhoneControl, title: String, key: String, modesKey: String) {
    val modes = c.ints(modesKey)
    if (modes.isEmpty()) return
    val names = mapOf(-1 to "Авто", 0 to "Выкл", 1 to "Быстро", 2 to "Качество")
    val opts = listOf(-1, 0, 1, 2).filter { it == -1 || it in modes }
    Label(title)
    ConnectedChoice(
        options = opts.map { names.getValue(it) },
        selected = opts.indexOf(c.int(key, -1)).coerceAtLeast(0),
        onSelect = { c.set(key to opts[it]) },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
}

@Composable
private fun PcSettings(engine: StreamEngine) {
    val s = engine.settings
    val colors = MaterialTheme.colorScheme
    // маленькое превью, чтобы сразу видеть результат
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(TileShape).background(colors.surfaceContainer),
    ) {
        val img = engine.preview
        if (img != null && engine.phase == Phase.Live) {
            Image(img, "Превью", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            ShapeIcon(CamIcons.Eye, colors.surfaceContainerHighest, colors.onSurfaceVariant, size = 64.dp, shape = MaterialShapes.Cookie9Sided.toShape())
        }
    }
    Card("Положение кадра", CamIcons.Rotate) {
        Label("Поворот")
        ConnectedChoice(
            options = listOf("0°", "90°", "180°", "270°"),
            selected = s.rotate / 90,
            onSelect = { s.rotate = it * 90; s.save() },
            containerColor = colors.surfaceContainerHighest,
        )
        SwitchLine("Зеркально (по горизонтали)", s.mirror) { s.mirror = it; s.save() }
        SwitchLine("Перевернуть (по вертикали)", s.flipV) { s.flipV = it; s.save() }
        Label("Кадр в камере")
        ConnectedChoice(
            options = listOf("Вписать целиком", "Заполнить 16:9"),
            selected = if (s.fill) 1 else 0,
            onSelect = { s.fill = it == 1; s.save() },
            containerColor = colors.surfaceContainerHighest,
        )
    }
    Card("Цвет", CamIcons.Tune) {
        ValueSlider("Яркость", s.brightness, -0.5f..0.5f, { "%+d".format((it * 200).roundToInt()) }, { s.brightness = it }, onDone = s::save)
        ValueSlider("Контраст", s.contrast, 0.5f..1.5f, { "${(it * 100).roundToInt()}%" }, { s.contrast = it }, onDone = s::save)
        ValueSlider("Насыщенность", s.saturation, 0f..2f, { "${(it * 100).roundToInt()}%" }, { s.saturation = it }, onDone = s::save)
        FilledTonalButton(
            onClick = { s.brightness = 0f; s.contrast = 1f; s.saturation = 1f; s.save() },
            shapes = ButtonDefaults.shapes(),
        ) {
            Icon(CamIcons.Refresh, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Сбросить цвет")
        }
    }
}

// ---------- мелкие элементы ----------

@Composable
private fun Card(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(colors.surfaceContainer)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ShapeIcon(icon, colors.secondaryContainer, colors.onSecondaryContainer, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        content()
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SwitchLine(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Hint(subtitle)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ValueSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    text: (Float) -> String,
    onChange: (Float) -> Unit,
    steps: Int = 0,
    onDone: () -> Unit = {},
) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(text(value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps.coerceIn(0, 200),
            onValueChangeFinished = onDone,
        )
    }
}

/** Ползунок с логарифмической шкалой — для ISO, выдержки и зума. */
@Composable
private fun LogSlider(label: String, value: Double, min: Double, max: Double, text: (Double) -> String, onChange: (Double) -> Unit) {
    val lMin = ln(min)
    val lMax = ln(max)
    val pos = ((ln(value.coerceIn(min, max)) - lMin) / (lMax - lMin)).toFloat()
    ValueSlider(
        label = label,
        value = pos,
        range = 0f..1f,
        text = { text(exp(lMin + it * (lMax - lMin))) },
        onChange = { onChange(exp(lMin + it * (lMax - lMin))) },
    )
}

/** Выдержка в привычном виде: 1/125, 1/30, 0.5 с. */
fun shutter(ns: Long): String {
    if (ns <= 0) return "—"
    val sec = ns / 1e9
    return if (sec >= 0.3) "%.1f с".format(sec) else "1/${(1 / sec).roundToInt()}"
}

/** Диоптрии → расстояние. */
fun distance(d: Float): String = when {
    d <= 0.01f -> "∞"
    1f / d >= 1f -> "%.1f м".format(1f / d)
    else -> "${(100f / d).roundToInt()} см"
}
