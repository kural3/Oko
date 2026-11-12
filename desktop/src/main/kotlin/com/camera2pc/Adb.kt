package com.camera2pc

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * adb для Oko: встроенный (app\resources\platform-tools), иначе из Android SDK или PATH.
 * Сервер общий, на обычном порту 5037: два сервера на разных портах дерутся за телефон по кабелю
 * (у одного он становится offline). С adb из Android Studio или scrcpy это не спорит — у всех
 * нынешних версий один протокол (41), и чужой сервер не перезапускается.
 */
class Adb private constructor(private val exe: String, val bundled: Boolean) {

    data class Device(val serial: String, val state: String) {
        /** Кабельный серийник — без адреса и без mDNS-имени беспроводной отладки. */
        val cable get() = ':' !in serial && "._adb-tls" !in serial && "._tcp" !in serial
    }

    /** Сервер adb запустил сам Oko — значит, при выходе его можно остановить. */
    @Volatile private var startedServer = false
    private val lastOut = ConcurrentHashMap<String, String>()

    fun run(vararg args: String, timeoutSec: Long = 15): Pair<Int, String> {
        val p = ProcessBuilder(listOf(exe) + args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) p.destroyForcibly()
        val code = runCatching { p.exitValue() }.getOrDefault(-1)
        if ("daemon started successfully" in out) startedServer = true
        // повторы (опрос каждые несколько секунд) в журнал не пишем — только когда ответ изменился
        val key = args.joinToString(" ")
        if (lastOut.put(key, "$code $out") != "$code $out") {
            Log.i("adb $key → $code\n" + out.lines().filter { it.isNotBlank() }.joinToString("\n") { "    $it" })
        }
        return code to out
    }

    /** Все телефоны, которые видит adb, с состоянием (device, unauthorized, offline…). */
    fun devices(): List<Device> =
        run("devices").second.lines()
            .map { it.trim() }
            .filter { '\t' in it }
            .map { Device(it.substringBefore('\t'), it.substringAfter('\t').trim()) }

    /** Готовые к работе. */
    fun ready() = devices().filter { it.state == "device" }

    /** Ищет телефоны с беспроводной отладкой в сети (mDNS) и подключается к ним. */
    fun mdnsConnect(): Boolean {
        val services = run("mdns", "services").second.lines().filter { "_adb-tls-connect" in it }
        var ok = false
        for (line in services) {
            val addr = line.trim().split(Regex("\\s+")).lastOrNull { ':' in it } ?: continue
            if ("connected" in run("connect", addr).second) ok = true
        }
        return ok
    }

    /** Подвисшие (offline) подключения переподключить. */
    fun reconnectOffline() {
        run("reconnect", "offline")
    }

    companion object {
        @Volatile private var cached: Adb? = null
        private val stopped = AtomicBoolean(false)

        fun find(): Adb? {
            cached?.let { if (File(it.exe).isFile) return it }
            val appDir = System.getProperty("compose.application.resources.dir")?.let { File(it) }
            val own = appDir?.resolve("platform-tools/adb.exe")?.takeIf { it.isFile }
            val found = if (own != null) Adb(own.absolutePath, bundled = true) else {
                (listOfNotNull(System.getenv("LOCALAPPDATA")?.let { File(it, "Android/Sdk/platform-tools/adb.exe") }) +
                    (System.getenv("PATH") ?: "").split(File.pathSeparator).map { File(it, "adb.exe") })
                    .firstOrNull { it.isFile }?.let { Adb(it.absolutePath, bundled = false) }
            }
            if (found != null && cached == null) {
                // и при выходе не через окно (завершение сеанса и т. п.)
                Runtime.getRuntime().addShutdownHook(Thread { shutdown() })
            }
            if (found != null) Log.i("adb: ${found.exe}" + if (found.bundled) " (встроенный)" else "")
            cached = found
            return found
        }

        /**
         * При выходе из Oko останавливаем сервер adb, если его запустил сам Oko своим adb: иначе он
         * висит в фоне и держит файлы в папке Oko. Чужой (Android Studio, scrcpy) не трогаем.
         */
        fun shutdown() {
            val adb = cached?.takeIf { it.bundled && it.startedServer } ?: return
            if (stopped.compareAndSet(false, true)) runCatching { adb.run("kill-server", timeoutSec = 5) }
        }

        /**
         * Телефон подключён кабелем, но у его интерфейса ADB нет драйвера: Windows видит устройство
         * с ошибкой, а adb — ничего. Интерфейс ADB узнаём по классу USB ff/42/01.
         */
        fun usbDriverMissing(): Boolean = runCatching {
            val p = ProcessBuilder("pnputil.exe", "/enum-devices", "/connected", "/problem", "/ids")
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(10, TimeUnit.SECONDS)
            "subclass_42&prot_01" in out.lowercase()
        }.getOrDefault(false)
    }
}
