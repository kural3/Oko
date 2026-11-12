package com.camera2pc

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets

/**
 * Передаёт кадры в виртуальную камеру Camera2PC через разделяемую память (см. driver/shared.h).
 * Память создаёт сама камера, когда её открывает какая-нибудь программа; до тех пор писать некуда.
 */
class FrameSink {
    private var view: MemorySegment? = null
    private var lastTry = 0L

    /** Камеру сейчас кто-то смотрит: она отмечает время каждого отданного кадра. */
    val cameraInUse: Boolean
        get() {
            val v = view ?: return false
            val consumer = v.get(ValueLayout.JAVA_LONG_UNALIGNED, OFF_CONSUMER_TICK)
            return consumer != 0L && tickCount() - consumer < 2000
        }

    /** Пишет кадр BGRA (сверху вниз). False — камера ещё никем не открыта. */
    fun write(bgra: ByteArray, width: Int, height: Int): Boolean {
        val v = ensure() ?: return false
        if (width.toLong() * height > MAX_PIXELS || bgra.size < width * height * 4) return false
        var seq = v.get(ValueLayout.JAVA_INT_UNALIGNED, OFF_SEQ)
        if (seq and 1 == 0) seq += 1
        v.set(ValueLayout.JAVA_INT_UNALIGNED, OFF_SEQ, seq)               // нечётный — идёт запись
        MemorySegment.copy(bgra, 0, v, ValueLayout.JAVA_BYTE, HEADER.toLong(), width * height * 4)
        v.set(ValueLayout.JAVA_INT_UNALIGNED, OFF_WIDTH, width)
        v.set(ValueLayout.JAVA_INT_UNALIGNED, OFF_HEIGHT, height)
        v.set(ValueLayout.JAVA_INT_UNALIGNED, OFF_BPP, 4)
        v.set(ValueLayout.JAVA_LONG_UNALIGNED, OFF_TICK, tickCount())
        v.set(ValueLayout.JAVA_INT_UNALIGNED, OFF_MAGIC, MAGIC)
        v.set(ValueLayout.JAVA_INT_UNALIGNED, OFF_VERSION, 1)
        v.set(ValueLayout.JAVA_INT_UNALIGNED, OFF_SEQ, seq + 1)           // чётный — кадр готов
        return true
    }

    /**
     * Начинает запись кадра на месте: помечает кадр как «пишется» (нечётный seq) и отдаёт память
     * под пиксели BGRA — в неё можно рисовать прямо. Затем обязательно [commit].
     * null — камера никем не открыта (писать незачем) или кадр слишком большой.
     */
    fun begin(width: Int, height: Int): MemorySegment? {
        val v = ensure() ?: return null
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS) return null
        // камеру давно никто не читал — не тратим силы на запись
        val consumer = v.get(ValueLayout.JAVA_LONG_UNALIGNED, OFF_CONSUMER_TICK)
        if (consumer == 0L || tickCount() - consumer > 3000) return null
        var seq = v.get(ValueLayout.JAVA_INT_UNALIGNED, OFF_SEQ)
        if (seq and 1 == 0) seq += 1
        v.set(ValueLayout.JAVA_INT_UNALIGNED, OFF_SEQ, seq)
        return v.asSlice(HEADER.toLong(), width.toLong() * height * 4)
    }

    /** Кадр нарисован — публикуем его (чётный seq). */
    fun commit(width: Int, height: Int) {
        val v = view ?: return
        val seq = v.get(ValueLayout.JAVA_INT_UNALIGNED, OFF_SEQ)
        v.set(ValueLayout.JAVA_INT_UNALIGNED, OFF_WIDTH, width)
        v.set(ValueLayout.JAVA_INT_UNALIGNED, OFF_HEIGHT, height)
        v.set(ValueLayout.JAVA_INT_UNALIGNED, OFF_BPP, 4)
        v.set(ValueLayout.JAVA_LONG_UNALIGNED, OFF_TICK, tickCount())
        v.set(ValueLayout.JAVA_INT_UNALIGNED, OFF_MAGIC, MAGIC)
        v.set(ValueLayout.JAVA_INT_UNALIGNED, OFF_VERSION, 1)
        v.set(ValueLayout.JAVA_INT_UNALIGNED, OFF_SEQ, if (seq and 1 == 1) seq + 1 else seq + 2)
    }

    private fun ensure(): MemorySegment? {
        view?.let { return it }
        val now = System.currentTimeMillis()
        if (now - lastTry < 1000) return null
        lastTry = now
        val handle = openFileMapping.invokeWithArguments(FILE_MAP_ALL, 0, NAME) as MemorySegment
        if (handle.address() == 0L) return null
        val v = mapViewOfFile.invokeWithArguments(handle, FILE_MAP_ALL, 0, 0, SHM_SIZE) as MemorySegment
        if (v.address() == 0L) return null
        return v.reinterpret(SHM_SIZE).also { view = it }
    }

    companion object {
        const val MAX_PIXELS = 1920L * 1920L
        private const val HEADER = 64
        private const val SHM_SIZE = HEADER + MAX_PIXELS * 4
        private const val MAGIC = 0x43325043
        private const val OFF_MAGIC = 0L
        private const val OFF_VERSION = 4L
        private const val OFF_SEQ = 8L
        private const val OFF_WIDTH = 12L
        private const val OFF_HEIGHT = 16L
        private const val OFF_BPP = 20L
        private const val OFF_TICK = 24L
        private const val OFF_CONSUMER_TICK = 32L
        private const val FILE_MAP_ALL = 0x2 or 0x4

        private val linker = Linker.nativeLinker()
        private val kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global())
        private val NAME: MemorySegment = Arena.global().allocateFrom("Global\\Camera2PC_Frame", StandardCharsets.UTF_16LE)

        private val openFileMapping = linker.downcallHandle(
            kernel32.find("OpenFileMappingW").get(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
        )
        private val mapViewOfFile = linker.downcallHandle(
            kernel32.find("MapViewOfFile").get(),
            FunctionDescriptor.of(
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
            ),
        )
        private val getTickCount64 = linker.downcallHandle(
            kernel32.find("GetTickCount64").get(), FunctionDescriptor.of(ValueLayout.JAVA_LONG),
        )

        /** Тот же счётчик, что использует камера (GetTickCount64). */
        fun tickCount(): Long = getTickCount64.invokeWithArguments() as Long
    }
}
