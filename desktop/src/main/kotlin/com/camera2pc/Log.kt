package com.camera2pc

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Журнал в %APPDATA%\Camera2PC\log.txt: команды adb с ответами, ошибки связи. Когда «не работает» —
 * видно почему. Больше 512 КБ — старый уходит в log.old.txt.
 */
object Log {
    val file = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "Camera2PC/log.txt")
    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    init {
        runCatching {
            file.parentFile.mkdirs()
            if (file.length() > 512 * 1024) {
                val old = File(file.parentFile, "log.old.txt")
                old.delete()
                file.renameTo(old)
            }
        }
    }

    @Synchronized
    fun i(msg: String) {
        runCatching { file.appendText("${LocalDateTime.now().format(time)}  ${msg.trimEnd()}\n", Charsets.UTF_8) }
    }

    /** Открывает журнал в Проводнике, с выделенным файлом. */
    fun reveal() {
        runCatching {
            if (!file.exists()) i("журнал создан")
            ProcessBuilder("explorer.exe", "/select,${file.absolutePath}").start()
        }
    }
}
