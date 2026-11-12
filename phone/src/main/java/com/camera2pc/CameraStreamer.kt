package com.camera2pc

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Разрешения, которые можно выбрать. */
enum class StreamResolution(val label: String, val size: Size) {
    SD("540p", Size(960, 540)),
    HD("720p", Size(1280, 720)),
    FullHD("1080p", Size(1920, 1080)),
}

/** Качество JPEG. */
enum class StreamQuality(val label: String, val jpeg: Int) {
    Normal("Обычное", 80),
    High("Высокое", 90),
    Max("Макс", 96),
}

/** Камера телефона: id для Camera2 и понятное имя («Широкоугольная (13 мм)»). */
class CamInfo(val id: String, val name: String, val front: Boolean)

/** Что умеет выбранная камера. */
data class Caps(
    val iso: Range<Int>? = null,
    val exposure: Range<Long>? = null,
    val manualSensor: Boolean = false,
    val evMin: Int = 0,
    val evMax: Int = 0,
    val evStep: Float = 0f,
    val zoomMin: Float = 1f,
    val zoomMax: Float = 1f,
    val minFocus: Float = 0f, // в диоптриях; 0 — фокус фиксированный
    val flash: Boolean = false,
    val fpsList: List<Int> = listOf(30),
    val fpsRanges: List<Range<Int>> = emptyList(),
    val awbModes: List<Int> = listOf(CameraMetadata.CONTROL_AWB_MODE_AUTO),
    val nrModes: List<Int> = emptyList(),
    val edgeModes: List<Int> = emptyList(),
    val effects: List<Int> = emptyList(),
    val antibanding: List<Int> = emptyList(),
    val ois: Boolean = false,
    val stab: Boolean = false,
)

/**
 * Камера → JPEG → MJPEG-сервер, плюс все ручные настройки (ISO, выдержка, баланс белого, фокус…).
 * Настройки меняются и с телефона, и с компьютера (через /api/set на том же порту).
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraStreamer(private val context: Context) : MjpegServer.Api {
    val server = MjpegServer(PORT, this)
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("stream", Context.MODE_PRIVATE)

    // --- выбор камеры и потока ---
    var cameras by mutableStateOf<List<CamInfo>>(emptyList())
        private set
    var cameraId by mutableStateOf<String?>(null)
        private set
    var resolution by mutableStateOf(StreamResolution.FullHD)
        private set
    var quality by mutableStateOf(StreamQuality.High)
        private set
    var fpsTarget by mutableIntStateOf(30)
        private set

    // --- ручные настройки ---
    var aeManual by mutableStateOf(false)
        private set
    var ev by mutableIntStateOf(0)
        private set
    var iso by mutableIntStateOf(400)
        private set
    var exposureNs by mutableLongStateOf(16_666_667L)
        private set
    var aeLock by mutableStateOf(false)
        private set
    var awbMode by mutableIntStateOf(CameraMetadata.CONTROL_AWB_MODE_AUTO)
        private set
    var awbLock by mutableStateOf(false)
        private set
    var afManual by mutableStateOf(false)
        private set
    var focusDistance by mutableFloatStateOf(0f)
        private set
    var zoom by mutableFloatStateOf(1f)
        private set
    var torch by mutableStateOf(false)
        private set
    var stabilization by mutableStateOf(false)
        private set
    var ois by mutableStateOf(true)
        private set
    var antibanding by mutableIntStateOf(CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO)
        private set
    var noiseReduction by mutableIntStateOf(-1) // -1 — как решит камера
        private set
    var edge by mutableIntStateOf(-1)
        private set
    var effect by mutableIntStateOf(CameraMetadata.CONTROL_EFFECT_MODE_OFF)
        private set
    /** Поток поворачивается вместе с телефоном (можно держать вертикально). */
    var autoRotate by mutableStateOf(true)
        private set
    /** Экран телефона притушен, камера работает. */
    var dim by mutableStateOf(false)
        private set
    /** Камера выключена: экран притушен и компьютер не смотрит — телефон почти не тратит энергию. */
    var sleeping by mutableStateOf(false)
        private set

    var caps by mutableStateOf(Caps())
        private set

    // --- статистика ---
    var frameSize by mutableStateOf<Size?>(null)
        private set
    var fps by mutableIntStateOf(0)
        private set
    var mbps by mutableFloatStateOf(0f)
        private set
    var clients by mutableIntStateOf(0)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var liveIso by mutableIntStateOf(0)
        private set
    var liveExposure by mutableLongStateOf(0L)
        private set

    @Volatile private var resIso = 0
    @Volatile private var resExposure = 0L
    @Volatile private var resFocus = 0f

    val useFront get() = cameras.firstOrNull { it.id == cameraId }?.front == true
    val hasFlash get() = caps.flash

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var analysis: ImageAnalysis? = null
    private var owner: LifecycleOwner? = null
    private var previewView: PreviewView? = null
    private var deviceRotation = Surface.ROTATION_90

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val encoders = Executors.newFixedThreadPool(ENCODER_THREADS)
    private val inFlight = AtomicInteger()
    private val frameSeq = AtomicLong()
    private var lastPublished = 0L
    private val publishLock = Any()
    private val pool = ArrayBlockingQueue<ByteArray>(ENCODER_THREADS + 2)
    private val jpegOut = ThreadLocal.withInitial { ByteArrayOutputStream(1 shl 20) }
    private val framesCounter = AtomicInteger()
    private val bytesCounter = AtomicLong()
    @Volatile private var jpegQuality = quality.jpeg

    init {
        load()
        server.clientsListener = Runnable { main.post(::onClientsChanged) }
        server.start()
    }

    fun attach(owner: LifecycleOwner, view: PreviewView) {
        this.owner = owner
        this.previewView = view
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val p = future.get()
            provider = p
            cameras = listCameras(p)
            if (cameras.none { it.id == cameraId }) cameraId = cameras.firstOrNull { !it.front }?.id ?: cameras.firstOrNull()?.id
            bind()
        }, ContextCompat.getMainExecutor(context))
    }

    // ---------- действия с телефона ----------

    fun switchCamera() {
        val list = cameras
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.id == cameraId }
        selectCamera(list[(i + 1) % list.size].id)
    }

    fun selectCamera(id: String) {
        if (id == cameraId || cameras.none { it.id == id }) return
        cameraId = id
        torch = false
        afManual = false
        zoom = 1f
        save()
        bind()
    }

    fun selectResolution(r: StreamResolution) {
        if (r == resolution) return
        resolution = r
        save()
        bind()
    }

    fun selectQuality(q: StreamQuality) {
        quality = q
        jpegQuality = q.jpeg
        save()
    }

    fun selectFps(f: Int) {
        if (f == fpsTarget) return
        fpsTarget = f
        save()
        bind()
    }

    fun toggleTorch() = applyTorch(!torch)

    private fun applyTorch(on: Boolean) {
        val cam = camera ?: return
        if (!caps.flash) return
        torch = on
        cam.cameraControl.enableTorch(on)
    }

    fun toggleDim() = changeDim(!dim)

    /**
     * Притушить экран. Превью на телефоне при этом не нужно — камера работает только на компьютер,
     * а если и компьютер не смотрит, через 15 с камера выключается совсем.
     */
    fun changeDim(on: Boolean) {
        if (on == dim) return
        dim = on
        sleeping = false
        bind()
        scheduleSleep()
    }

    private val sleepCheck = Runnable {
        if (dim && server.clientCount == 0 && camera != null) {
            provider?.unbindAll()
            camera = null
            analysis = null
            sleeping = true
        }
    }

    private fun scheduleSleep() {
        main.removeCallbacks(sleepCheck)
        if (dim && server.clientCount == 0) main.postDelayed(sleepCheck, 15_000)
    }

    private fun onClientsChanged() {
        clients = server.clientCount
        if (clients > 0 && sleeping) {
            sleeping = false
            bind()
        }
        scheduleSleep()
    }

    fun toggleAutoRotate() {
        autoRotate = !autoRotate
        updateAnalysisRotation()
        save()
    }

    fun focusAt(x: Float, y: Float) {
        val view = previewView ?: return
        val cam = camera ?: return
        if (afManual) {
            afManual = false
            applyOptions()
        }
        val point = view.meteringPointFactory.createPoint(x, y)
        cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
    }

    fun zoomBy(factor: Float) = applyZoom(zoom * factor)

    private fun applyZoom(z: Float) {
        val cam = camera ?: return
        val v = z.coerceIn(caps.zoomMin, caps.zoomMax)
        cam.cameraControl.setZoomRatio(v)
        zoom = v
    }

    /** Ориентация телефона в руке (Surface.ROTATION_*), от OrientationEventListener. */
    fun setDeviceRotation(rotation: Int) {
        if (rotation == deviceRotation) return
        deviceRotation = rotation
        updateAnalysisRotation()
    }

    private fun updateAnalysisRotation() {
        val a = analysis ?: return
        a.targetRotation = if (autoRotate) deviceRotation else displayRotation()
    }

    private fun displayRotation() = previewView?.display?.rotation ?: Surface.ROTATION_90

    /** Раз в секунду: обновляет счётчики для интерфейса. */
    fun tick() {
        fps = framesCounter.getAndSet(0)
        mbps = bytesCounter.getAndSet(0) * 8f / 1_000_000f
        clients = server.clientCount
        liveIso = resIso
        liveExposure = resExposure
    }

    fun release() {
        server.stop()
        analysisExecutor.shutdown()
        encoders.shutdown()
    }

    // ---------- управление с компьютера ----------

    override fun handle(path: String, query: Map<String, String>): String {
        var out = ""
        val done = CountDownLatch(1)
        main.post {
            try {
                if (path == "/api/set") applyRemote(query)
                out = stateText()
            } catch (e: Exception) {
                out = "error=${e.message}\n"
            } finally {
                done.countDown()
            }
        }
        done.await(3, TimeUnit.SECONDS)
        return out
    }

    private fun applyRemote(q: Map<String, String>) {
        var rebind = false
        var options = false
        for ((k, v) in q) {
            when (k) {
                "camera" -> if (v != cameraId && cameras.any { it.id == v }) {
                    cameraId = v; torch = false; afManual = false; zoom = 1f; rebind = true
                }
                "res" -> StreamResolution.entries.firstOrNull { it.name == v }?.let { if (it != resolution) { resolution = it; rebind = true } }
                "quality" -> StreamQuality.entries.firstOrNull { it.name == v }?.let { quality = it; jpegQuality = it.jpeg }
                "fps" -> v.toIntOrNull()?.let { if (it != fpsTarget) { fpsTarget = it; rebind = true } }
                "ae" -> {
                    val manual = v == "manual"
                    if (manual && !aeManual) {
                        // переходим в ручной режим с тех значений, что камера подобрала сама
                        if (resIso > 0) iso = resIso
                        if (resExposure > 0) exposureNs = resExposure
                    }
                    aeManual = manual; options = true
                }
                "ev" -> v.toIntOrNull()?.let { ev = it; options = true }
                "iso" -> v.toIntOrNull()?.let { iso = it; aeManual = true; options = true }
                "exp" -> v.toLongOrNull()?.let { exposureNs = it; aeManual = true; options = true }
                "aeLock" -> { aeLock = v == "1"; options = true }
                "awb" -> v.toIntOrNull()?.let { awbMode = it; options = true }
                "awbLock" -> { awbLock = v == "1"; options = true }
                "af" -> { afManual = v == "manual"; if (afManual && focusDistance == 0f && resFocus > 0f) focusDistance = resFocus; options = true }
                "focus" -> v.toFloatOrNull()?.let { focusDistance = it; afManual = true; options = true }
                "zoom" -> v.toFloatOrNull()?.let { applyZoom(it) }
                "torch" -> applyTorch(v == "1")
                "stab" -> { stabilization = v == "1"; options = true }
                "ois" -> { ois = v == "1"; options = true }
                "antiband" -> v.toIntOrNull()?.let { antibanding = it; options = true }
                "nr" -> v.toIntOrNull()?.let { noiseReduction = it; options = true }
                "edge" -> v.toIntOrNull()?.let { edge = it; options = true }
                "effect" -> v.toIntOrNull()?.let { effect = it; options = true }
                "autorot" -> { autoRotate = v == "1"; updateAnalysisRotation() }
                "dim" -> changeDim(v == "1")
                "reset" -> { resetManual(); options = true; applyZoom(1f) }
            }
        }
        save()
        if (rebind) bind() else if (options) applyOptions()
    }

    private fun resetManual() {
        aeManual = false; ev = 0; aeLock = false
        awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO; awbLock = false
        afManual = false; focusDistance = 0f
        stabilization = false; ois = true
        antibanding = CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
        noiseReduction = -1; edge = -1
        effect = CameraMetadata.CONTROL_EFFECT_MODE_OFF
    }

    /** Состояние в виде строк key=value — легко разобрать на любой стороне. */
    private fun stateText(): String {
        val c = caps
        val sb = StringBuilder()
        fun put(k: String, v: Any?) { sb.append(k).append('=').append(v ?: "").append('\n') }
        put("app", "oko")
        put("cams", cameras.joinToString(";") { "${it.id}|${it.name.replace(';', ',').replace('|', '/')}" })
        put("camera", cameraId)
        put("res", resolution.name)
        put("quality", quality.name)
        put("fps", fpsTarget)
        put("fpsList", c.fpsList.joinToString(","))
        put("ae", if (aeManual) "manual" else "auto")
        put("ev", ev)
        put("evMin", c.evMin); put("evMax", c.evMax); put("evStep", c.evStep)
        put("iso", iso)
        put("isoMin", c.iso?.lower ?: 0); put("isoMax", c.iso?.upper ?: 0)
        put("exp", exposureNs)
        put("expMin", c.exposure?.lower ?: 0); put("expMax", c.exposure?.upper ?: 0)
        put("manualSensor", if (c.manualSensor) 1 else 0)
        put("aeLock", if (aeLock) 1 else 0)
        put("awb", awbMode)
        put("awbModes", c.awbModes.joinToString(","))
        put("awbLock", if (awbLock) 1 else 0)
        put("af", if (afManual) "manual" else "auto")
        put("focus", focusDistance)
        put("focusMax", c.minFocus)
        put("zoom", zoom)
        put("zoomMin", c.zoomMin); put("zoomMax", c.zoomMax)
        put("torch", if (torch) 1 else 0)
        put("flash", if (c.flash) 1 else 0)
        put("stab", if (stabilization) 1 else 0); put("stabAvail", if (c.stab) 1 else 0)
        put("ois", if (ois) 1 else 0); put("oisAvail", if (c.ois) 1 else 0)
        put("antiband", antibanding); put("antibandModes", c.antibanding.joinToString(","))
        put("nr", noiseReduction); put("nrModes", c.nrModes.joinToString(","))
        put("edge", edge); put("edgeModes", c.edgeModes.joinToString(","))
        put("effect", effect); put("effects", c.effects.joinToString(","))
        put("autorot", if (autoRotate) 1 else 0)
        put("dim", if (dim) 1 else 0)
        put("sleeping", if (sleeping) 1 else 0)
        // что камера выставила сама прямо сейчас
        put("liveIso", resIso)
        put("liveExp", resExposure)
        put("liveFocus", resFocus)
        put("outFps", fps)
        put("w", frameSize?.width ?: 0); put("h", frameSize?.height ?: 0)
        put("error", error?.replace('\n', ' '))
        battery()?.let { (level, temp, charging) ->
            put("battery", level); put("temp", temp); put("charging", if (charging) 1 else 0)
        }
        return sb.toString()
    }

    private fun battery(): Triple<Int, Float, Boolean>? {
        val i: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return Triple(level * 100 / scale, temp, plugged)
    }

    // ---------- сохранение ----------

    private fun load() {
        cameraId = prefs.getString("camera", null)
        if (cameraId == null && prefs.getBoolean("front", false)) cameraId = "1"
        resolution = StreamResolution.entries.firstOrNull { it.name == prefs.getString("resName", null) } ?: StreamResolution.FullHD
        quality = StreamQuality.entries.getOrElse(prefs.getInt("quality", 1)) { StreamQuality.High }
        jpegQuality = quality.jpeg
        fpsTarget = prefs.getInt("fps", 30)
        aeManual = prefs.getBoolean("aeManual", false)
        ev = prefs.getInt("ev", 0)
        iso = prefs.getInt("iso", 400)
        exposureNs = prefs.getLong("exp", 16_666_667L)
        awbMode = prefs.getInt("awb", CameraMetadata.CONTROL_AWB_MODE_AUTO)
        afManual = prefs.getBoolean("afManual", false)
        focusDistance = prefs.getFloat("focus", 0f)
        stabilization = prefs.getBoolean("stab", false)
        ois = prefs.getBoolean("ois", true)
        antibanding = prefs.getInt("antiband", CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO)
        noiseReduction = prefs.getInt("nr", -1)
        edge = prefs.getInt("edge", -1)
        effect = prefs.getInt("effect", CameraMetadata.CONTROL_EFFECT_MODE_OFF)
        autoRotate = prefs.getBoolean("autorot", true)
    }

    private fun save() {
        prefs.edit()
            .putString("camera", cameraId)
            .putString("resName", resolution.name)
            .putInt("quality", quality.ordinal)
            .putInt("fps", fpsTarget)
            .putBoolean("aeManual", aeManual)
            .putInt("ev", ev)
            .putInt("iso", iso)
            .putLong("exp", exposureNs)
            .putInt("awb", awbMode)
            .putBoolean("afManual", afManual)
            .putFloat("focus", focusDistance)
            .putBoolean("stab", stabilization)
            .putBoolean("ois", ois)
            .putInt("antiband", antibanding)
            .putInt("nr", noiseReduction)
            .putInt("edge", edge)
            .putInt("effect", effect)
            .putBoolean("autorot", autoRotate)
            .apply()
    }

    // ---------- камера ----------

    private fun listCameras(p: ProcessCameraProvider): List<CamInfo> {
        class Raw(val id: String, val front: Boolean, val eq: Float)
        val raw = p.availableCameraInfos.mapNotNull { info ->
            runCatching {
                val c2 = Camera2CameraInfo.from(info)
                val facing = c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
                val f = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 0f
                val s = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                val eq = if (s != null && f > 0f) f * 43.27f / hypot(s.width, s.height) else 0f
                Raw(c2.cameraId, facing == CameraCharacteristics.LENS_FACING_FRONT, eq)
            }.getOrNull()
        }.distinctBy { it.id }
        val backs = raw.filter { !it.front }
        val mainEq = backs.minByOrNull { kotlin.math.abs(it.eq - 26f) }?.eq ?: 26f
        val used = HashMap<String, Int>()
        fun unique(base: String): String {
            val n = (used[base] ?: 0) + 1
            used[base] = n
            return if (n == 1) base else "$base $n"
        }
        val named = raw.map { r ->
            val base = when {
                r.front -> "Фронтальная"
                r.eq in 1f..20f -> "Широкоугольная"
                r.eq > 0f && r.eq / mainEq > 1.6f -> "Телефото ${"%.0f".format(r.eq / mainEq)}×"
                else -> "Основная"
            }
            val mm = if (r.eq > 0f) " (${r.eq.roundToInt()} мм)" else ""
            CamInfo(r.id, unique(base) + mm, r.front)
        }
        // основная камера первой, фронтальные — в конце
        return named.sortedWith(compareBy({ it.front }, { !it.name.startsWith("Основная") }))
    }

    private fun readCaps(info: CameraInfo): Caps {
        val c2 = Camera2CameraInfo.from(info)
        fun <T> get(key: CameraCharacteristics.Key<T>): T? = runCatching { c2.getCameraCharacteristic(key) }.getOrNull()
        val capsList = get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
        val ranges = get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty()
        val ev = get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val step = get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        val zoomState = info.zoomState.value
        val nr = get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toList().orEmpty()
        val edge = get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toList().orEmpty()
        return Caps(
            iso = get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            exposure = get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capsList,
            evMin = ev?.lower ?: 0,
            evMax = ev?.upper ?: 0,
            evStep = step?.toFloat() ?: 0f,
            zoomMin = zoomState?.minZoomRatio ?: 1f,
            zoomMax = zoomState?.maxZoomRatio ?: 1f,
            minFocus = get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            flash = info.hasFlashUnit(),
            fpsList = ranges.map { it.upper }.filter { it >= 10 }.distinct().sorted().ifEmpty { listOf(30) },
            fpsRanges = ranges,
            awbModes = get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList()
                ?.filter { it != CameraMetadata.CONTROL_AWB_MODE_OFF }.orEmpty().ifEmpty { listOf(CameraMetadata.CONTROL_AWB_MODE_AUTO) },
            nrModes = nr,
            edgeModes = edge,
            effects = get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS)?.toList().orEmpty(),
            antibanding = get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)?.toList().orEmpty(),
            ois = (get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList().orEmpty())
                .contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON),
            stab = (get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toList().orEmpty())
                .contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON),
        )
    }

    /** Лучший диапазон для нужной частоты: фиксированный [f,f], иначе [x,f] с наибольшим x. */
    private fun fpsRangeFor(target: Int, ranges: List<Range<Int>>): Range<Int>? {
        if (ranges.isEmpty()) return Range(target, target)
        ranges.firstOrNull { it.lower == target && it.upper == target }?.let { return it }
        ranges.filter { it.upper == target }.maxByOrNull { it.lower }?.let { return it }
        return ranges.filter { it.upper <= target }.maxWithOrNull(compareBy({ it.upper }, { it.lower }))
    }

    private fun selectorFor(id: String?): CameraSelector {
        if (id == null) return CameraSelector.DEFAULT_BACK_CAMERA
        return CameraSelector.Builder()
            .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == id } }
            .build()
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { resIso = it }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { resExposure = it }
            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { resFocus = it }
        }
    }

    private fun bind() {
        val provider = provider ?: return
        val owner = owner ?: return
        val view = previewView ?: return
        camera?.cameraInfo?.zoomState?.removeObservers(owner)
        provider.unbindAll()
        camera = null
        analysis = null

        val selector = selectorFor(cameraId)
        val info = provider.availableCameraInfos.firstOrNull { runCatching { Camera2CameraInfo.from(it).cameraId }.getOrNull() == cameraId }
        val ranges = info?.let { readCaps(it).fpsRanges }.orEmpty()
        val range = fpsRangeFor(fpsTarget, ranges)
        val rotation = displayRotation()

        // Сначала с выбранной частотой кадров; если камера так не умеет — без этого.
        for (fixedFps in listOf(true, false)) {
            try {
                val previewBuilder = Preview.Builder()
                    .setResolutionSelector(selectorFor(resolution.size))
                    .setTargetRotation(rotation)
                if (fixedFps && range != null) {
                    Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                }
                // экран притушен — превью не рисуем, камера работает только на компьютер
                val preview = if (dim) null else previewBuilder.build().also { it.surfaceProvider = view.surfaceProvider }

                val analysisBuilder = ImageAnalysis.Builder()
                    .setResolutionSelector(selectorFor(resolution.size))
                    .setTargetRotation(if (autoRotate) deviceRotation else rotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                Camera2Interop.Extender(analysisBuilder).apply {
                    setSessionCaptureCallback(captureCallback)
                    if (fixedFps && range != null) setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
                }
                val a = analysisBuilder.build()
                a.setAnalyzer(analysisExecutor, ::analyze)

                val cam = if (preview != null) provider.bindToLifecycle(owner, selector, preview, a)
                else provider.bindToLifecycle(owner, selector, a)
                camera = cam
                analysis = a
                caps = readCaps(cam.cameraInfo)
                zoom = zoom.coerceIn(caps.zoomMin, caps.zoomMax)
                if (zoom != 1f) cam.cameraControl.setZoomRatio(zoom)
                if (torch && caps.flash) cam.cameraControl.enableTorch(true) else torch = false
                cam.cameraInfo.zoomState.observe(owner) { zoom = it.zoomRatio }
                applyOptions()
                error = null
                return
            } catch (e: Exception) {
                provider.unbindAll()
                error = e.message
            }
        }
    }

    /** Все ручные настройки — поверх того, что выставляет CameraX. Меняются без перезапуска камеры. */
    private fun applyOptions() {
        val cam = camera ?: return
        val c = caps
        val b = CaptureRequestOptions.Builder()
        fun <T : Any> set(key: CaptureRequest.Key<T>, v: T) { b.setCaptureRequestOption(key, v) }

        if (aeManual && c.manualSensor) {
            val isoV = c.iso?.let { iso.coerceIn(it.lower, it.upper) } ?: iso
            val expV = c.exposure?.let { exposureNs.coerceIn(it.lower, it.upper) } ?: exposureNs
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.SENSOR_SENSITIVITY, isoV)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, expV)
            set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(expV, 1_000_000_000L / fpsTarget.coerceAtLeast(1)))
        } else {
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev.coerceIn(c.evMin, c.evMax))
            set(CaptureRequest.CONTROL_AE_LOCK, aeLock)
        }
        if (antibanding in c.antibanding) set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, antibanding)
        if (awbMode in c.awbModes) set(CaptureRequest.CONTROL_AWB_MODE, awbMode)
        set(CaptureRequest.CONTROL_AWB_LOCK, awbLock)
        if (afManual && c.minFocus > 0f) {
            set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance.coerceIn(0f, c.minFocus))
        }
        if (c.stab) {
            set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (stabilization) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            )
        }
        if (c.ois) {
            set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (ois) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF,
            )
        }
        if (noiseReduction in c.nrModes) set(CaptureRequest.NOISE_REDUCTION_MODE, noiseReduction)
        if (edge in c.edgeModes) set(CaptureRequest.EDGE_MODE, edge)
        if (effect in c.effects) set(CaptureRequest.CONTROL_EFFECT_MODE, effect)

        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())
    }

    private fun selectorFor(size: Size) = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
        .build()

    // ---- кодирование ----

    private fun analyze(image: ImageProxy) {
        try {
            val w = image.width
            val h = image.height
            val size = Size(w, h)
            if (frameSize != size) frameSize = size
            if (!server.hasClients() || inFlight.get() >= ENCODER_THREADS) return

            val need = w * h * 3 / 2
            val buf = pool.poll()?.takeIf { it.size == need } ?: ByteArray(need)
            toNv21(image, buf)
            val id = frameSeq.incrementAndGet()
            val rot = image.imageInfo.rotationDegrees
            val q = jpegQuality
            inFlight.incrementAndGet()
            encoders.execute {
                try {
                    val out = jpegOut.get()!!
                    out.reset()
                    YuvImage(buf, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), q, out)
                    val jpeg = out.toByteArray()
                    synchronized(publishLock) {
                        // кадры кодируются параллельно — не отдаём более старый после нового
                        if (id > lastPublished) {
                            lastPublished = id
                            server.publish(jpeg, rot)
                            framesCounter.incrementAndGet()
                            bytesCounter.addAndGet(jpeg.size.toLong())
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    pool.offer(buf)
                    inFlight.decrementAndGet()
                }
            }
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }

    private var rowU = ByteArray(0)
    private var rowV = ByteArray(0)

    /** YUV_420_888 (с учётом stride) → NV21. */
    private fun toNv21(image: ImageProxy, out: ByteArray) {
        val w = image.width
        val h = image.height
        val planes = image.planes
        val y = planes[0].buffer
        val yRow = planes[0].rowStride
        val yPix = planes[0].pixelStride
        var pos = 0
        if (yPix == 1) {
            for (r in 0 until h) {
                y.position(r * yRow)
                y.get(out, pos, w)
                pos += w
            }
        } else {
            for (r in 0 until h) for (c in 0 until w) out[pos++] = y.get(r * yRow + c * yPix)
        }

        val u = planes[1].buffer
        val v = planes[2].buffer
        val uvRow = planes[1].rowStride
        val uvPix = planes[1].pixelStride
        val cw = w / 2
        val ch = h / 2
        if (rowU.size < uvRow) {
            rowU = ByteArray(uvRow)
            rowV = ByteArray(uvRow)
        }
        // Частый случай: VU уже чередуются в буфере V (NV21 внутри) — копируем строками.
        // Проверяем, что это именно VUVU (а не UVUV): в NV21 байт V[i+1] совпадает с U[i].
        val interleaved = uvPix == 2 && planes[2].rowStride == uvRow && v.capacity() > 64 &&
            (0 until 16).all { k -> v.get(2 * k + 1) == u.get(2 * k) }
        for (r in 0 until ch) {
            val start = r * uvRow
            if (interleaved) {
                val len = minOf(cw * 2 - 1, v.capacity() - start)
                v.position(start)
                v.get(out, pos, len)
                if (len < cw * 2) out[pos + cw * 2 - 1] = u.get(start + (cw - 1) * 2)
                pos += cw * 2
            } else {
                val lenU = minOf(uvRow, u.capacity() - start)
                val lenV = minOf(uvRow, v.capacity() - start)
                u.position(start); u.get(rowU, 0, lenU)
                v.position(start); v.get(rowV, 0, lenV)
                for (c in 0 until cw) {
                    val i = c * uvPix
                    out[pos++] = rowV[i]
                    out[pos++] = rowU[i]
                }
            }
        }
    }

    companion object {
        const val PORT = 8080
        private const val ENCODER_THREADS = 4
    }
}
