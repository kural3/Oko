package com.camera2pc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorFilter
import org.jetbrains.skia.ColorMatrix
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Properties
import kotlin.coroutines.coroutineContext

enum class Mode { Wifi, Usb }
enum class Phase { Stopped, Connecting, Live, Paused, Error }

/** Настройки, хранятся в %APPDATA%\Camera2PC\settings.properties. */
class Settings {
    private val file = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "Camera2PC/settings.properties")
    var host by mutableStateOf("")
    var port by mutableIntStateOf(8080)
    var mode by mutableStateOf(Mode.Usb)
    var rotate by mutableIntStateOf(0)
    var mirror by mutableStateOf(false)
    var autostart by mutableStateOf(true)
    var flipV by mutableStateOf(false)
    /** Обрезать до 16:9 вместо чёрных полос (удобно для вертикального видео). */
    var fill by mutableStateOf(false)
    var brightness by mutableFloatStateOf(0f)   // -0.5..0.5
    var contrast by mutableFloatStateOf(1f)     // 0.5..1.5
    var saturation by mutableFloatStateOf(1f)   // 0..2
    /** Не гонять видео, пока камерой никто не пользуется и окно свёрнуто. */
    var powerSave by mutableStateOf(true)

    init {
        runCatching {
            val p = Properties().apply { file.inputStream().use(::load) }
            host = p.getProperty("host", "")
            port = p.getProperty("port", "8080").toIntOrNull() ?: 8080
            mode = if (p.getProperty("mode", "usb") == "usb") Mode.Usb else Mode.Wifi
            rotate = (p.getProperty("rotate", "0").toIntOrNull() ?: 0) % 360
            mirror = p.getProperty("mirror") == "true"
            autostart = p.getProperty("autostart", "true") == "true"
            flipV = p.getProperty("flipV") == "true"
            fill = p.getProperty("fill") == "true"
            brightness = p.getProperty("brightness", "0").toFloatOrNull() ?: 0f
            contrast = p.getProperty("contrast", "1").toFloatOrNull() ?: 1f
            saturation = p.getProperty("saturation", "1").toFloatOrNull() ?: 1f
            powerSave = p.getProperty("powerSave", "true") == "true"
        }
    }

    fun save() {
        runCatching {
            file.parentFile.mkdirs()
            Properties().apply {
                setProperty("host", host)
                setProperty("port", port.toString())
                setProperty("mode", if (mode == Mode.Usb) "usb" else "wifi")
                setProperty("rotate", rotate.toString())
                setProperty("mirror", mirror.toString())
                setProperty("autostart", autostart.toString())
                setProperty("flipV", flipV.toString())
                setProperty("fill", fill.toString())
                setProperty("brightness", brightness.toString())
                setProperty("contrast", contrast.toString())
                setProperty("saturation", saturation.toString())
                setProperty("powerSave", powerSave.toString())
            }.let { p -> file.outputStream().use { p.store(it, "Oko") } }
        }
    }
}

/**
 * Телефон → MJPEG → (поворот, зеркало, цвет) → камера Oko Camera и превью в окне.
 *
 * Экономия:
 *  - кадр рисуется прямо в общую память камеры (без промежуточных копий и выделений памяти);
 *  - превью — уменьшенное и только пока окно видно;
 *  - если камерой никто не пользуется и окно свёрнуто, видео с телефона не принимается вовсе —
 *    телефон перестаёт кодировать кадры. Как только программа откроет камеру, поток возобновится.
 */
class StreamEngine(val settings: Settings) {
    var phase by mutableStateOf(Phase.Stopped)
        private set
    var message by mutableStateOf("")
        private set
    var fps by mutableIntStateOf(0)
        private set
    var mbps by mutableFloatStateOf(0f)
        private set
    var frameWidth by mutableIntStateOf(0)
        private set
    var frameHeight by mutableIntStateOf(0)
        private set
    var preview by mutableStateOf<ImageBitmap?>(null)
        private set
    var cameraInUse by mutableStateOf(false)
        private set
    var running by mutableStateOf(false)
        private set
    /** Чем на самом деле подключён телефон: кабель, беспроводная отладка adb или Wi‑Fi. */
    var link by mutableStateOf("")
        private set

    /** Окно на экране (не свёрнуто) — нужно превью. Выставляет Main. */
    @Volatile var windowVisible = true

    @Volatile private var viaCable = false
    @Volatile private var demo = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
        set(value) {
            field = value
            running = value != null
        }
    private val sink = FrameSink()
    /** Настройки камеры телефона (ISO, выдержка, выбор камеры…). */
    val control = PhoneControl(scope)
    @Volatile private var socket: Socket? = null

    init {
        // Статус камеры обновляется и без трансляции
        scope.launch {
            while (isActive) {
                cameraInUse = demo || sink.cameraInUse
                delay(500)
            }
        }
        // ADB без кабеля (беспроводная отладка): как только кабель подключат — переходим на него.
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(4000)
                if (running && settings.mode == Mode.Usb && phase == Phase.Live && !viaCable) {
                    val adb = findAdb() ?: continue
                    if (runCatching { adbSerials(adb) }.getOrDefault(emptyList()).any(::isCable)) {
                        runCatching { socket?.close() }
                    }
                }
            }
        }
    }

    fun start() {
        if (job != null) return
        settings.save()
        val j = scope.launch { runLoop() }
        job = j
        // цикл завершился сам (например, не задан адрес) — кнопка снова «Подключить»
        j.invokeOnCompletion { if (job === j) job = null }
    }

    fun stop() {
        val j = job ?: return
        job = null
        control.endpoint = null
        runCatching { socket?.close() }
        j.cancel()
        phase = Phase.Stopped
        message = ""
        fps = 0
        mbps = 0f
        preview = null
    }

    /**
     * Демо для снимков в README (OKO_DEMO=1): нарисованная сцена вместо камеры и правдоподобные
     * настройки телефона, без настоящего устройства и чужих данных.
     */
    fun startDemo() {
        demo = true
        running = true
        phase = Phase.Live
        link = "через ADB (беспроводная отладка)"
        message = "Подключено $link"
        fps = 30
        mbps = 34.6f
        frameWidth = 1920
        frameHeight = 1080
        preview = demoScene(960, 540).toComposeImageBitmap()
        control.demo()
    }

    private fun demoScene(w: Int, h: Int): Image {
        val surf = Surface.makeRasterN32Premul(w, h)
        val c = surf.canvas
        // небо: плавный переход сверху вниз полосами по 2 пикселя
        val stops = intArrayOf(0xFF1E2A4A.toInt(), 0xFF6B4E7A.toInt(), 0xFFE9A46A.toInt())
        fun mix(a: Int, b: Int, t: Float): Int {
            fun ch(sh: Int) = ((((a shr sh) and 0xFF) * (1 - t) + ((b shr sh) and 0xFF) * t).toInt() and 0xFF) shl sh
            return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
        }
        val band = Paint()
        var y = 0
        while (y < h) {
            val t = y.toFloat() / h * 2
            band.color = if (t < 1) mix(stops[0], stops[1], t) else mix(stops[1], stops[2], t - 1)
            c.drawRect(Rect.makeXYWH(0f, y.toFloat(), w.toFloat(), 2f), band)
            y += 2
        }
        val sun = Paint().apply { color = 0xFFFFD9A0.toInt(); isAntiAlias = true }
        c.drawCircle(w * 0.68f, h * 0.58f, h * 0.13f, sun)
        // мягкие «боке»
        val bokeh = Paint().apply { isAntiAlias = true }
        val rnd = java.util.Random(7)
        repeat(26) {
            bokeh.color = (((10 + rnd.nextInt(26)) shl 24) or 0xFFF1E4)
            c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h * 0.7f, h * (0.03f + rnd.nextFloat() * 0.07f), bokeh)
        }
        // холмы
        val hills = listOf(0xFF3E3657.toInt() to 0.70f, 0xFF2A2540.toInt() to 0.80f, 0xFF18152A.toInt() to 0.90f)
        hills.forEachIndexed { i, (col, base) ->
            val paint = Paint().apply { color = col }
            var x = 0
            while (x < w) {
                val top = h * base - (kotlin.math.sin(x.toFloat() / w * 6.3 + i * 1.7) * h * 0.05).toFloat()
                c.drawRect(Rect.makeLTRB(x.toFloat(), top, x + 2f, h.toFloat()), paint)
                x += 2
            }
        }
        return surf.makeImageSnapshot()
    }

    private fun idle() = settings.powerSave && !windowVisible && !sink.cameraInUse

    private suspend fun runLoop() {
        val placeholder = placeholder("Oko · ждём телефон")
        while (coroutineContext.isActive) {
            if (idle()) {
                phase = Phase.Paused
                message = "Пауза — камерой никто не пользуется"
                fps = 0
                mbps = 0f
                control.endpoint = null
                while (coroutineContext.isActive && idle()) delay(250)
                continue
            }
            try {
                var host = settings.host.trim()
                if (settings.mode == Mode.Usb) {
                    phase = Phase.Connecting
                    message = "Настраиваю ADB…"
                    viaCable = withContext(Dispatchers.IO) { adbForward(settings.port) }
                    link = if (viaCable) "через ADB (кабель)" else "через ADB (беспроводная отладка)"
                    host = "127.0.0.1"
                } else if (host.isEmpty()) {
                    phase = Phase.Error
                    message = "Введите IP-адрес с экрана телефона"
                    return
                } else {
                    link = "по Wi‑Fi"
                }
                phase = Phase.Connecting
                message = "Подключаюсь к телефону…"
                control.endpoint = "$host:${settings.port}"
                withContext(Dispatchers.IO) { pump(host, settings.port) }
                continue // вышли сами — значит, пауза
            } catch (e: Exception) {
                if (!coroutineContext.isActive) return
                phase = Phase.Error
                message = friendly(e)
                fps = 0
                mbps = 0f
            }
            // пока нет связи — заставка в камере, чтобы она не «замирала»
            repeat(5) {
                if (!coroutineContext.isActive || idle()) return@repeat
                sink.write(placeholder.pixels, placeholder.width, placeholder.height)
                delay(400)
            }
        }
    }

    private suspend fun pump(host: String, port: Int) {
        val s = Socket()
        socket = s
        s.use {
            s.tcpNoDelay = true
            s.soTimeout = 5000
            s.receiveBufferSize = 1 shl 20
            s.connect(InetSocketAddress(host, port), 4000)
            s.getOutputStream().write("GET /video HTTP/1.1\r\nHost: $host\r\n\r\n".toByteArray())
            val input = BufferedInputStream(s.getInputStream(), 1 shl 18)
            val status = readLine(input) ?: error("соединение закрыто")
            if (!status.contains("200")) error("телефон ответил неожиданно — это точно Oko?")
            while (true) {
                val line = readLine(input) ?: error("соединение закрыто")
                if (line.isEmpty()) break
            }
            phase = Phase.Live
            message = "Подключено $link"

            val renderer = FrameRenderer()
            var frames = 0
            var bytes = 0L
            var t0 = System.nanoTime()
            var idleSince = 0L
            try {
                while (coroutineContext.isActive) {
                    val (jpeg, rotation) = readPart(input)
                    bytes += jpeg.size
                    val now = System.nanoTime()
                    // никто не смотрит уже 5 с — отпускаем телефон
                    if (idle()) {
                        if (idleSince == 0L) idleSince = now
                        if (now - idleSince > 5_000_000_000L) return
                    } else {
                        idleSince = 0L
                    }
                    if (renderer.render(jpeg, (rotation + settings.rotate) % 360, settings)) frames++
                    if (now - t0 >= 1_000_000_000L) {
                        val secs = (now - t0) / 1e9
                        fps = (frames / secs).toInt()
                        mbps = (bytes * 8 / secs / 1e6).toFloat()
                        frameWidth = renderer.width
                        frameHeight = renderer.height
                        frames = 0
                        bytes = 0
                        t0 = now
                    }
                }
            } finally {
                renderer.close()
            }
        }
    }

    /** Одна часть multipart: заголовки, затем ровно Content-Length байт JPEG. */
    private fun readPart(input: InputStream): Pair<ByteArray, Int> {
        var length = -1
        var rotation = 0
        var sawHeader = false
        while (true) {
            val line = readLine(input) ?: error("соединение закрыто")
            if (line.isEmpty()) {
                if (sawHeader) break else continue
            }
            if (line.startsWith("--")) continue
            val colon = line.indexOf(':')
            if (colon > 0) {
                sawHeader = true
                val key = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim()
                when (key) {
                    "content-length" -> length = value.toInt()
                    "x-rotation" -> rotation = value.toIntOrNull() ?: 0
                }
            }
        }
        if (length <= 0) error("кадр без длины")
        val data = ByteArray(length)
        var off = 0
        while (off < length) {
            val n = input.read(data, off, length - off)
            if (n < 0) error("соединение закрыто")
            off += n
        }
        return data to rotation
    }

    private val lineBuf = StringBuilder()

    private fun readLine(input: InputStream): String? {
        val sb = lineBuf
        sb.setLength(0)
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) error("слишком длинная строка")
        }
    }

    private fun friendly(e: Exception): String {
        val m = e.message ?: e.toString()
        return when {
            e is java.net.ConnectException -> "Телефон не отвечает. Откройте Oko на телефоне."
            e is java.net.SocketTimeoutException -> "Телефон не отвечает (тайм-аут). Проверьте связь."
            e is java.net.UnknownHostException -> "Неверный адрес"
            else -> m
        }
    }

    // ---------------- adb ----------------

    private fun run(vararg cmd: String, timeoutSec: Long = 15): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        return (runCatching { p.exitValue() }.getOrDefault(-1)) to out
    }

    /** Телефоны, которые видит adb (готовые к работе). */
    private fun adbSerials(adb: String): List<String> =
        run(adb, "devices").second.lines()
            .map { it.trim() }
            .filter { it.endsWith("\tdevice") }
            .map { it.substringBefore('\t') }

    /** Кабельный серийник — без адреса и без mDNS-имени беспроводной отладки. */
    private fun isCable(serial: String) = ':' !in serial && "._adb-tls" !in serial && "._tcp" !in serial

    /** Ищет телефоны с беспроводной отладкой в сети (mDNS) и подключается к ним. */
    private fun mdnsConnect(adb: String): Boolean {
        val services = run(adb, "mdns", "services").second.lines().filter { "_adb-tls-connect" in it }
        var ok = false
        for (line in services) {
            val addr = line.trim().split(Regex("\\s+")).lastOrNull { ':' in it } ?: continue
            if ("connected" in run(adb, "connect", addr).second) ok = true
        }
        return ok
    }

    /** Пробрасывает порт телефона на 127.0.0.1. Возвращает true, если связь по кабелю. */
    private fun adbForward(port: Int): Boolean {
        val adb = findAdb() ?: error("adb не найден")
        // Один телефон может числиться дважды (кабель + беспроводная отладка) — берём один:
        // сначала подключённый кабелем, иначе беспроводной.
        var serials = adbSerials(adb)
        if (serials.isEmpty() && mdnsConnect(adb)) serials = adbSerials(adb)
        if (serials.isEmpty()) error("adb не видит телефон — включите на нём «Беспроводную отладку» (как — написано в Oko на телефоне)")
        val serial = serials.firstOrNull(::isCable) ?: serials.first()
        val (code, out) = run(adb, "-s", serial, "forward", "tcp:$port", "tcp:$port")
        if (code != 0) {
            error(
                when {
                    "unauthorized" in out -> "Разрешите отладку на телефоне"
                    else -> "adb: ${out.trim()}"
                }
            )
        }
        return isCable(serial)
    }

    /**
     * Сопряжение с телефоном по коду (Беспроводная отладка → «Подключить устройство с помощью кода»).
     * Нужно один раз; потом adb находит телефон сам.
     */
    suspend fun pair(address: String, code: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val adb = findAdb() ?: error("adb не найден")
            val (_, out) = run(adb, "pair", address.trim(), code.trim(), timeoutSec = 20)
            if ("Successfully paired" !in out) {
                error(
                    when {
                        "Wrong password" in out || "protocol fault" in out -> "Неверный или устаревший код — откройте окно с кодом заново"
                        "Unable to start pairing" in out || "connect" in out.lowercase() -> "Не удалось связаться с телефоном — проверьте IP и порт"
                        else -> out.trim().ifEmpty { "Сопряжение не удалось" }
                    }
                )
            }
            // после сопряжения adb обычно подключается сам; поможем ему
            repeat(5) {
                if (adbSerials(adb).isNotEmpty()) return@runCatching "Телефон подключён"
                mdnsConnect(adb)
                Thread.sleep(800)
            }
            "Сопряжение выполнено. Нажмите «Подключить»."
        }
    }

    private fun findAdb(): String? {
        val appDir = System.getProperty("compose.application.resources.dir")?.let { File(it) }
        val candidates = listOfNotNull(
            appDir?.resolve("platform-tools/adb.exe"),
            System.getenv("LOCALAPPDATA")?.let { File(it, "Android/Sdk/platform-tools/adb.exe") },
        ) + (System.getenv("PATH") ?: "").split(File.pathSeparator).map { File(it, "adb.exe") }
        return candidates.firstOrNull { it.isFile }?.absolutePath
    }

    // ---------------- кадр ----------------

    /**
     * Декодирует JPEG и рисует его (поворот, зеркало, цвет) прямо в общую память камеры.
     * Отдельно — маленькое превью для окна, только когда окно видно.
     */
    private inner class FrameRenderer {
        var width = 0
        var height = 0
        private var direct: Surface? = null
        private var directKey = Triple(0L, 0, 0)
        private var small: Surface? = null
        private val paint = Paint()
        private var colorKey = Triple(0f, 1f, 1f)
        private val sampling = SamplingMode.LINEAR

        fun render(jpeg: ByteArray, rotation: Int, s: Settings): Boolean {
            val wantPreview = windowVisible
            val encoded = runCatching { Image.makeFromEncoded(jpeg) }.getOrNull() ?: return false
            encoded.use {
                val swap = rotation == 90 || rotation == 270
                val rw = if (swap) encoded.height else encoded.width
                val rh = if (swap) encoded.width else encoded.height
                // «Заполнить»: вырезаем середину 16:9, камера растянет её на весь кадр
                var w = rw
                var h = rh
                if (s.fill) {
                    if (rw * 9 > rh * 16) w = rh * 16 / 9 else h = rw * 9 / 16
                    w = w and 1.inv()
                    h = h and 1.inv()
                }
                width = w
                height = h
                updateColor(s)

                val target = sink.begin(w, h)
                if (target == null && !wantPreview) return false
                // кадр нужен дважды — декодируем один раз
                val decoded = if (target != null && wantPreview) Bitmap.makeFromImage(encoded).apply { setImmutable() } else null
                val img = decoded?.let { Image.makeFromBitmap(it) } ?: encoded
                try {
                    if (target != null) {
                        val key = Triple(target.address(), w, h)
                        val surf = direct?.takeIf { directKey == key }
                            ?: Surface.makeRasterDirect(ImageInfo.makeN32(w, h, ColorAlphaType.PREMUL), target.address(), w * 4)
                                .also { direct?.close(); direct = it; directKey = key }
                        draw(surf.canvas, img, rotation, s, rw, rh, w, h, 1f)
                        sink.commit(w, h)
                    }
                    if (wantPreview) {
                        val pw = minOf(w, 960)
                        val ph = (h.toLong() * pw / w).toInt().coerceAtLeast(1)
                        val surf = small?.takeIf { it.width == pw && it.height == ph }
                            ?: Surface.makeRasterN32Premul(pw, ph).also { small?.close(); small = it }
                        draw(surf.canvas, img, rotation, s, rw, rh, w, h, pw.toFloat() / w)
                        preview = surf.makeImageSnapshot().toComposeImageBitmap()
                    } else if (preview != null) {
                        preview = null
                    }
                } finally {
                    if (img !== encoded) img.close()
                    decoded?.close()
                }
                return true
            }
        }

        private fun draw(canvas: Canvas, img: Image, rotation: Int, s: Settings, rw: Int, rh: Int, w: Int, h: Int, scale: Float) {
            canvas.clear(0xFF000000.toInt())
            canvas.save()
            canvas.scale(scale, scale)
            canvas.translate(-(rw - w) / 2f, -(rh - h) / 2f)
            if (s.mirror) {
                canvas.translate(rw.toFloat(), 0f)
                canvas.scale(-1f, 1f)
            }
            if (s.flipV) {
                canvas.translate(0f, rh.toFloat())
                canvas.scale(1f, -1f)
            }
            when (rotation) {
                90 -> { canvas.translate(rw.toFloat(), 0f); canvas.rotate(90f) }
                180 -> { canvas.translate(rw.toFloat(), rh.toFloat()); canvas.rotate(180f) }
                270 -> { canvas.translate(0f, rh.toFloat()); canvas.rotate(270f) }
            }
            val r = Rect.makeWH(img.width.toFloat(), img.height.toFloat())
            canvas.drawImageRect(img, r, r, sampling, paint, true)
            canvas.restore()
        }

        /** Яркость, контраст и насыщенность — одной цветовой матрицей Skia. */
        private fun updateColor(s: Settings) {
            val key = Triple(s.brightness, s.contrast, s.saturation)
            if (key == colorKey) return
            colorKey = key
            if (s.brightness == 0f && s.contrast == 1f && s.saturation == 1f) {
                paint.colorFilter = null
                return
            }
            val sat = s.saturation
            val lr = 0.2126f * (1 - sat)
            val lg = 0.7152f * (1 - sat)
            val lb = 0.0722f * (1 - sat)
            val c = s.contrast
            val t = 0.5f * (1 - c) + s.brightness
            paint.colorFilter = ColorFilter.makeMatrix(
                ColorMatrix(
                    c * (lr + sat), c * lg, c * lb, 0f, t,
                    c * lr, c * (lg + sat), c * lb, 0f, t,
                    c * lr, c * lg, c * (lb + sat), 0f, t,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        }

        fun close() {
            direct?.close()
            small?.close()
            direct = null
            small = null
        }
    }

    private class Placeholder(val pixels: ByteArray, val width: Int, val height: Int)

    private fun placeholder(text: String): Placeholder {
        val w = 1280
        val h = 720
        val surf = Surface.makeRasterN32Premul(w, h)
        val c = surf.canvas
        c.clear(0xFF0B0B0D.toInt())
        val typeface = FontMgr.default.matchFamilyStyle("Segoe UI", FontStyle.NORMAL)
        val font = Font(typeface, 40f)
        val paint = Paint().apply { color = 0xFFC8CCD0.toInt(); isAntiAlias = true }
        val width = font.measureTextWidth(text, paint)
        c.drawString(text, (w - width) / 2f, h / 2f + 14f, font, paint)
        val bmp = Bitmap().apply { allocPixels(ImageInfo.makeN32(w, h, ColorAlphaType.PREMUL)) }
        surf.readPixels(bmp, 0, 0)
        return Placeholder(bmp.readPixels()!!, w, h)
    }
}
