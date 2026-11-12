package com.camera2pc

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.camera2pc.ui.theme.CAMERA2PC_SEED
import com.camera2pc.ui.theme.SchemeGenerator
import com.camera2pc.ui.theme.schemeFromSeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets

/**
 * Цвет темы — с обоев Windows, как Material You на Android: берём текущую картинку рабочего стола,
 * уменьшаем до 48×48 и выбираем самый заметный оттенок. Если обои однотонные или недоступны —
 * цвет акцента Windows (он тоже может браться из обоев), иначе — красный по умолчанию.
 * Проверяем раз в 5 секунд только время изменения файла — это почти ничего не стоит.
 */
object WallpaperTheme {
    private val wallpaper = File(System.getenv("APPDATA") ?: "", "Microsoft/Windows/Themes/TranscodedWallpaper")

    @Composable
    fun scheme(): State<ColorScheme> = produceState(schemeFromSeed(CAMERA2PC_SEED)) {
        if (System.getenv("OKO_DEMO") == "1") return@produceState // снимки для README — в красном по умолчанию
        var lastKey = ""
        while (true) {
            val key = "${wallpaper.lastModified()}:${accentColor()}"
            if (key != lastKey) {
                lastKey = key
                val seed = withContext(Dispatchers.IO) { runCatching { seed() }.getOrNull() } ?: CAMERA2PC_SEED
                value = schemeFromSeed(seed)
            }
            delay(5000)
        }
    }

    private fun seed(): Int? {
        fromWallpaper()?.let { if (it != SchemeGenerator.MONOCHROME_SEED) return it }
        return accentColor()
    }

    private fun fromWallpaper(): Int? {
        if (!wallpaper.isFile) return null
        val img = Image.makeFromEncoded(wallpaper.readBytes())
        img.use {
            val n = 48
            val surf = Surface.makeRasterN32Premul(n, n)
            surf.use {
                surf.canvas.drawImageRect(
                    img, Rect.makeWH(img.width.toFloat(), img.height.toFloat()), Rect.makeWH(n.toFloat(), n.toFloat()),
                    SamplingMode.LINEAR, null, true,
                )
                val bmp = Bitmap().apply { allocPixels(ImageInfo(n, n, ColorType.BGRA_8888, ColorAlphaType.PREMUL)) }
                bmp.use {
                    surf.readPixels(bmp, 0, 0)
                    val bytes = bmp.readPixels() ?: return null
                    val px = IntArray(n * n) { i ->
                        val o = i * 4
                        (0xFF shl 24) or ((bytes[o + 2].toInt() and 0xFF) shl 16) or
                            ((bytes[o + 1].toInt() and 0xFF) shl 8) or (bytes[o].toInt() and 0xFF)
                    }
                    return SchemeGenerator.seedFromPixels(px)
                }
            }
        }
    }

    /** HKCU\Software\Microsoft\Windows\DWM\AccentColor (ABGR) → ARGB. */
    private fun accentColor(): Int? = runCatching {
        Arena.ofConfined().use { a ->
            val data = a.allocate(ValueLayout.JAVA_INT)
            val size = a.allocate(ValueLayout.JAVA_INT).also { it.set(ValueLayout.JAVA_INT, 0, 4) }
            val rc = regGetValue.invokeWithArguments(
                MemorySegment.ofAddress(HKEY_CURRENT_USER), a.allocateFrom("Software\\Microsoft\\Windows\\DWM", StandardCharsets.UTF_16LE),
                a.allocateFrom("AccentColor", StandardCharsets.UTF_16LE), RRF_RT_REG_DWORD, MemorySegment.NULL, data, size,
            ) as Int
            if (rc != 0) return@use null
            val abgr = data.get(ValueLayout.JAVA_INT, 0)
            val r = abgr and 0xFF
            val g = (abgr shr 8) and 0xFF
            val b = (abgr shr 16) and 0xFF
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }.getOrNull()

    private const val HKEY_CURRENT_USER = -2147483647L // (HKEY)(LONG)0x80000001
    private const val RRF_RT_REG_DWORD = 0x10

    private val regGetValue by lazy {
        Linker.nativeLinker().downcallHandle(
            SymbolLookup.libraryLookup("advapi32", Arena.global()).find("RegGetValueW").get(),
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ),
        )
    }
}
