package com.camera2pc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * Управление камерой телефона с компьютера: /api/state раз в секунду и /api/set при изменениях.
 * Состояние — плоский словарь key=value, как его отдаёт телефон.
 */
class PhoneControl(scope: CoroutineScope) {
    var state by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var online by mutableStateOf(false)
        private set

    /** "host:port" телефона, пока идёт трансляция; null — не опрашивать. */
    @Volatile var endpoint: String? = null

    /** Окно свёрнуто — опрашиваем телефон реже. */
    @Volatile var slow = false

    private val pending = LinkedHashMap<String, String>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var lastEdit = 0L
    @Volatile private var demoMode = false

    init {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                val ep = endpoint
                if (ep == null || demoMode) {
                    if (!demoMode) online = false
                    delay(400)
                    continue
                }
                try {
                    val s = fetch(ep, "/api/state")
                    // пока пользователь двигает ползунок — не перетираем его значение старым
                    if (System.currentTimeMillis() - lastEdit > 1500 && s["app"] == "oko") state = s
                    online = s["app"] == "oko"
                } catch (_: Exception) {
                    online = false
                }
                delay(if (slow) 5000 else 1000)
            }
        }
        scope.launch(Dispatchers.IO) {
            for (u in signal) {
                val ep = endpoint ?: continue
                val batch = synchronized(pending) { LinkedHashMap(pending).also { pending.clear() } }
                if (batch.isEmpty()) continue
                val q = batch.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
                try {
                    val s = fetch(ep, "/api/set?$q")
                    if (synchronized(pending) { pending.isEmpty() } && s["app"] == "oko") state = s
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Правдоподобный телефон для демо-режима (снимки для README). */
    fun demo() {
        demoMode = true
        online = true
        state = listOf(
            "app=oko", "cams=0|Основная (23 мм);2|Широкоугольная (14 мм);1|Фронтальная (25 мм)", "camera=0",
            "res=FullHD", "quality=High", "fps=30", "fpsList=15,24,30,60", "ae=auto", "ev=3", "evMin=-20", "evMax=20",
            "evStep=0.1", "iso=400", "isoMin=25", "isoMax=3200", "exp=16666667", "expMin=49854", "expMax=149993768",
            "manualSensor=1", "aeLock=0", "awb=1", "awbModes=1,2,3,5,6", "awbLock=0", "af=auto", "focus=0", "focusMax=10",
            "zoom=1.0", "zoomMin=0.6", "zoomMax=10", "torch=0", "flash=1", "stab=1", "stabAvail=1", "ois=1", "oisAvail=0",
            "antiband=1", "antibandModes=0,1,2,3", "nr=-1", "nrModes=0,1,2", "edge=-1", "edgeModes=0,1,2", "effect=0",
            "effects=0,1,2,4", "autorot=1", "dim=0", "liveIso=160", "liveExp=10000000", "liveFocus=2.5",
            "battery=82", "temp=34.1", "charging=1",
        ).associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    fun set(vararg kv: Pair<String, Any>) {
        lastEdit = System.currentTimeMillis()
        state = state + kv.map { it.first to it.second.toString() }
        synchronized(pending) { kv.forEach { pending[it.first] = it.second.toString() } }
        signal.trySend(Unit)
    }

    operator fun get(key: String): String? = state[key]
    fun int(key: String, def: Int = 0) = state[key]?.toIntOrNull() ?: def
    fun long(key: String, def: Long = 0) = state[key]?.toLongOrNull() ?: def
    fun float(key: String, def: Float = 0f) = state[key]?.toFloatOrNull() ?: def
    fun flag(key: String) = state[key] == "1"
    fun ints(key: String): List<Int> = state[key].orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }

    /** Камеры телефона: id → имя. */
    fun cameras(): List<Pair<String, String>> = state["cams"].orEmpty().split(';').mapNotNull {
        val i = it.indexOf('|')
        if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
    }

    fun nextCamera() {
        val list = cameras()
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.first == state["camera"] }
        set("camera" to list[(i + 1) % list.size].first)
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    private fun fetch(ep: String, path: String): Map<String, String> {
        val c = URI("http://$ep$path").toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 2000
        c.readTimeout = 5000
        c.useCaches = false
        try {
            val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return text.lineSequence().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
            }.toMap()
        } finally {
            c.disconnect()
        }
    }
}
