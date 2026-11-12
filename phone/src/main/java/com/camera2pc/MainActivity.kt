package com.camera2pc

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.material3.Switch
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.input.pointer.PointerEventPass
import android.os.SystemClock
import com.camera2pc.ui.theme.Camera2PCColors
import com.camera2pc.ui.theme.pureBlack
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.camera2pc.ui.components.CamIcons
import com.camera2pc.ui.components.ConnectedChoice
import com.camera2pc.ui.components.ShapeIcon
import com.camera2pc.ui.components.StatValue
import com.camera2pc.ui.components.StatusPill
import com.camera2pc.ui.components.Tile
import com.camera2pc.ui.components.TileShape
import com.camera2pc.ui.theme.Camera2PCTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var streamer: CameraStreamer
    private var orientation: OrientationEventListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        streamer = CameraStreamer(applicationContext)
        // Поток поворачивается вслед за телефоном: держите его хоть вертикально.
        orientation = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                streamer.setDeviceRotation(
                    when (o) {
                        in 45 until 135 -> Surface.ROTATION_270
                        in 135 until 225 -> Surface.ROTATION_180
                        in 225 until 315 -> Surface.ROTATION_90
                        else -> Surface.ROTATION_0
                    }
                )
            }
        }
        setContent {
            // Цвета — с обоев (Material You), фон остаётся чёрным
            val scheme = remember {
                if (android.os.Build.VERSION.SDK_INT >= 31) dynamicDarkColorScheme(this).pureBlack() else Camera2PCColors
            }
            Camera2PCTheme(scheme) {
                val dim = streamer.dim
                LaunchedEffect(dim) {
                    window.attributes = window.attributes.apply {
                        screenBrightness = if (dim) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
                }
                val prefs = remember { getSharedPreferences("ui", MODE_PRIVATE) }
                var guide by remember { mutableStateOf(!prefs.getBoolean("guideShown", false)) }
                var lastTouch by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
                // Экран — главный потребитель энергии: если идёт трансляция и телефон никто
                // не трогает полторы минуты, притушиваем его сами (камера продолжает работать).
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(5000)
                        val idle = SystemClock.elapsedRealtime() - lastTouch > 90_000
                        if (idle && !streamer.dim && !guide && streamer.clients > 0) streamer.changeDim(true)
                    }
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent(PointerEventPass.Initial)
                                    lastTouch = SystemClock.elapsedRealtime()
                                }
                            }
                        },
                ) {
                    CameraScreen(streamer, onGuide = { guide = true })
                    AnimatedVisibility(visible = guide, enter = fadeIn(), exit = fadeOut()) {
                        AdbGuide(onClose = {
                            guide = false
                            prefs.edit().putBoolean("guideShown", true).apply()
                        })
                    }
                    if (dim) DimOverlay(streamer)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        orientation?.takeIf { it.canDetectOrientation() }?.enable()
    }

    override fun onPause() {
        super.onPause()
        orientation?.disable()
    }

    override fun onDestroy() {
        super.onDestroy()
        streamer.release()
    }
}

@Composable
private fun CameraScreen(streamer: CameraStreamer, onGuide: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            streamer.tick()
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(12.dp),
    ) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (granted) CameraPreview(streamer) else PermissionHint { launcher.launch(Manifest.permission.CAMERA) }
        }
        SidePanel(streamer, onGuide, Modifier.width(300.dp).fillMaxHeight())
    }
}

@Composable
private fun CameraPreview(streamer: CameraStreamer) {
    val owner = LocalLifecycleOwner.current
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    var focus by remember { mutableStateOf<Offset?>(null) }
    val focusAnim = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(
        Modifier
            .fillMaxSize()
            .clip(TileShape)
            .background(colors.surfaceContainer)
    ) {
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FIT_CENTER
                    streamer.attach(owner, this)
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        streamer.focusAt(pos.x, pos.y)
                        focus = pos
                        scope.launch {
                            focusAnim.snapTo(1f)
                            focusAnim.animateTo(0f, androidx.compose.animation.core.tween(900))
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ -> if (zoom != 1f) streamer.zoomBy(zoom) }
                },
        )

        // кольцо фокуса
        focus?.let { pos ->
            val r = with(density) { 36.dp.toPx() }
            Box(
                Modifier
                    .offset { IntOffset((pos.x - r).roundToInt(), (pos.y - r).roundToInt()) }
                    .size(72.dp)
                    .graphicsLayer {
                        alpha = focusAnim.value
                        val s = 1.2f - 0.2f * focusAnim.value
                        scaleX = s; scaleY = s
                    }
                    .border(2.dp, colors.primary, MaterialShapes.Cookie9Sided.toShape())
            )
        }

        val live = streamer.clients > 0
        StatusPill(
            text = if (live) "В эфире · ${streamer.fps} к/с" else "Ожидание компьютера",
            live = live,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
        )

        if (streamer.zoom > 1.01f) {
            Text(
                "%.1f×".format(streamer.zoom),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurface,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceContainerHigh.copy(alpha = 0.85f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        // плавающая капсула с кнопками, как плеер Pier.doon
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp)
                .clip(CircleShape)
                .background(colors.surfaceContainerHigh.copy(alpha = 0.88f))
                .padding(6.dp),
        ) {
            FilledIconToggleButton(
                checked = streamer.useFront,
                onCheckedChange = { streamer.switchCamera() },
                shapes = IconButtonDefaults.toggleableShapes(),
                modifier = Modifier.size(52.dp),
            ) { Icon(CamIcons.SwitchCamera, contentDescription = "Сменить камеру") }
            FilledIconToggleButton(
                checked = streamer.torch,
                onCheckedChange = { streamer.toggleTorch() },
                enabled = streamer.hasFlash,
                shapes = IconButtonDefaults.toggleableShapes(),
                modifier = Modifier.size(52.dp),
            ) { Icon(CamIcons.Flash, contentDescription = "Фонарик") }
            FilledIconToggleButton(
                checked = false,
                onCheckedChange = { streamer.toggleDim() },
                shapes = IconButtonDefaults.toggleableShapes(),
                modifier = Modifier.size(52.dp),
            ) { Icon(CamIcons.Dim, contentDescription = "Притушить экран") }
        }
    }
}

@Composable
private fun SidePanel(streamer: CameraStreamer, onGuide: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    var ips by remember { mutableStateOf(localIps()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            ips = localIps()
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) {
            androidx.compose.foundation.Image(
                androidx.compose.ui.graphics.vector.rememberVectorPainter(com.camera2pc.ui.components.OkoLogo),
                contentDescription = null,
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text("Oko", style = MaterialTheme.typography.headlineSmallEmphasized)
        }

        Tile(
            title = "Как подключить",
            subtitle = "Через ADB — надёжнее всего. Нажмите, чтобы открыть инструкцию",
            icon = CamIcons.Link,
            container = colors.primaryContainer,
            onContainer = colors.onPrimaryContainer,
            decor = MaterialShapes.Cookie12Sided.toShape(),
            onClick = onGuide,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (ips.isEmpty()) "Wi‑Fi не подключён" else "Wi‑Fi: " + ips.joinToString(", "),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                color = colors.onPrimaryContainer,
            )
        }

        val live = streamer.clients > 0
        Tile(
            title = if (live) "Компьютер подключён" else "Ждём компьютер",
            subtitle = if (live) "Видео идёт в камеру Oko Camera" else "Запустите Oko на компьютере",
            icon = CamIcons.Desktop,
            container = colors.tertiaryContainer,
            onContainer = colors.onTertiaryContainer,
            decor = MaterialShapes.Sunny.toShape(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Spacer(Modifier.height(10.dp))
            AnimatedVisibility(visible = live, enter = fadeIn(), exit = fadeOut()) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    StatValue("к/с", "${streamer.fps}", color = colors.onTertiaryContainer)
                    StatValue("Мбит/с", "%.1f".format(streamer.mbps), color = colors.onTertiaryContainer)
                    streamer.frameSize?.let { StatValue("кадр", "${it.width}×${it.height}", color = colors.onTertiaryContainer) }
                }
            }
            AnimatedVisibility(visible = !live, enter = fadeIn(), exit = fadeOut()) {
                LoadingIndicator(color = colors.onTertiaryContainer, modifier = Modifier.size(40.dp))
            }
        }

        Section("Камера")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            streamer.cameras.forEach { cam ->
                val sel = cam.id == streamer.cameraId
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (sel) colors.secondaryContainer else colors.surfaceContainer)
                        .clickable { streamer.selectCamera(cam.id) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Icon(
                        if (cam.front) CamIcons.SwitchCamera else CamIcons.Videocam,
                        contentDescription = null,
                        tint = if (sel) colors.onSecondaryContainer else colors.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        cam.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (sel) colors.onSecondaryContainer else colors.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (sel) Icon(CamIcons.Check, contentDescription = null, tint = colors.onSecondaryContainer, modifier = Modifier.size(18.dp))
                }
            }
        }
        Section("Разрешение")
        ConnectedChoice(
            options = StreamResolution.entries.map { it.label },
            selected = streamer.resolution.ordinal,
            onSelect = { streamer.selectResolution(StreamResolution.entries[it]) },
        )
        Section("Качество")
        ConnectedChoice(
            options = StreamQuality.entries.map { it.label },
            selected = streamer.quality.ordinal,
            onSelect = { streamer.selectQuality(StreamQuality.entries[it]) },
        )
        val fpsOptions = listOf(15, 24, 30, 60).filter { it in streamer.caps.fpsList || it == streamer.fpsTarget }
        if (fpsOptions.size > 1) {
            Section("Кадров в секунду")
            ConnectedChoice(
                options = fpsOptions.map { "$it" },
                selected = fpsOptions.indexOf(streamer.fpsTarget).coerceAtLeast(0),
                onSelect = { streamer.selectFps(fpsOptions[it]) },
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surfaceContainer)
                .padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Поворот за телефоном", style = MaterialTheme.typography.bodyMedium)
                Text("Держите вертикально — будет вертикальное видео", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Switch(checked = streamer.autoRotate, onCheckedChange = { streamer.toggleAutoRotate() })
        }
        Text(
            "Тап по картинке — фокус, щипок — зум. ISO, выдержка, баланс белого и ручной фокус — в Oko на компьютере.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
        streamer.error?.let {
            Text("Камера: $it", style = MaterialTheme.typography.bodySmall, color = colors.error)
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp),
    )
}

@Composable
private fun PermissionHint(onRequest: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().clip(TileShape).background(colors.surfaceContainer),
    ) {
        ShapeIcon(CamIcons.Videocam, colors.primaryContainer, colors.onPrimaryContainer, size = 72.dp)
        Spacer(Modifier.height(16.dp))
        Text("Нужен доступ к камере", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRequest, shapes = ButtonDefaults.shapes()) { Text("Разрешить") }
    }
}

private fun localIps(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { ni ->
            // только локальная сеть: без мобильного интернета и VPN-туннелей
            ni.isUp && !ni.isLoopback && !ni.isVirtual &&
                listOf("rmnet", "ccmni", "dummy", "tun", "ppp", "ipsec", "clat").none { ni.name.startsWith(it) }
        }
        .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it.hostAddress ?: "" } }
        .filter { it.isNotEmpty() && !it.startsWith("198.18.") && !it.startsWith("198.19.") }
}.getOrDefault(emptyList())

/** Экран притушен, камера продолжает снимать. Тап — вернуть. */
@Composable
private fun DimOverlay(streamer: CameraStreamer) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { streamer.toggleDim() },
    ) {
        Text(
            when {
                streamer.sleeping -> "Oko · камера спит — проснётся, когда компьютер начнёт смотреть"
                streamer.clients > 0 -> "Oko · в эфире · ${streamer.fps} к/с"
                else -> "Oko"
            } + "\nНажмите, чтобы включить экран",
            color = Color(0xFF3A3A3A),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
