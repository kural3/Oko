package com.camera2pc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI

/** Проверка новой версии на GitHub Releases (последний выпуск kural3/Oko). */
object Updates {
    private const val API = "https://api.github.com/repos/kural3/Oko/releases/latest"
    const val PAGE = "https://github.com/kural3/Oko/releases/latest"

    /** Версия этой сборки; при запуске из IDE без jpackage — "dev". */
    val current: String = System.getProperty("oko.version") ?: "dev"

    data class Release(val version: String, val url: String)

    /** Найденный более новый выпуск (или null). */
    var available by mutableStateOf<Release?>(null)
        private set
    var checking by mutableStateOf(false)
        private set
    /** Итог последней ручной проверки для подписи в настройках. */
    var status by mutableStateOf("")
        private set

    suspend fun check() {
        if (checking) return
        checking = true
        try {
            val r = withContext(Dispatchers.IO) { fetch() }
            if (r != null && newer(r.version, current)) {
                available = r
                status = "Доступна версия ${r.version}"
                Log.i("обновление: есть ${r.version} (сейчас $current)")
            } else {
                available = null
                status = "Установлена последняя версия"
            }
        } catch (e: Exception) {
            status = "Не удалось проверить обновления"
            Log.i("обновление: ошибка проверки: $e")
        } finally {
            checking = false
        }
    }

    private fun fetch(): Release? {
        val c = URI(API).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "Oko")
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            val json = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val tag = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1) ?: return null
            val url = Regex("\"html_url\"\\s*:\\s*\"(https://github\\.com/[^\"]+/releases/[^\"]+)\"").find(json)?.groupValues?.get(1) ?: PAGE
            return Release(tag.removePrefix("v"), url)
        } finally {
            c.disconnect()
        }
    }

    /** 1.1 > 1.0.0; "dev" не обновляем. */
    fun newer(candidate: String, installed: String): Boolean {
        if (installed == "dev") return false
        fun parts(v: String) = v.removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun openPage() {
        runCatching { java.awt.Desktop.getDesktop().browse(URI(available?.url ?: PAGE)) }
    }
}
