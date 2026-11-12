package com.camera2pc.ui.components

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Значок Oko векторами — тот же, что в desktop/icons/Icon.java: графитовое «печенье» с градиентом,
 * светлая кайма и кремовый глаз. Не размывается и не обрезается ни в каком размере.
 */
val OkoLogo: ImageVector by lazy {
    val size = 108f
    val c = size / 2
    val r = size * 0.46f
    // «печенье» с 10 волнами по краю
    val cookie = PathBuilder().apply {
        val steps = 360
        for (i in 0..steps) {
            val t = i * 2 * PI / steps
            val rr = r * (1 + 0.042 * cos(10 * t))
            val x = (c + rr * sin(t)).toFloat()
            val y = (c - rr * cos(t)).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }.nodes

    // глаз в 108-единичной сетке, увеличен как на компьютерном значке (×1.45 от центра)
    val k = 1.45f
    fun p(v: Float) = c + (v - c) * k
    fun eye(rx: Float) = "M${p(54 - rx)},${p(54f)}"
    val outer = "M${p(31f)},${p(54f)}A${25.9f * k},${25.9f * k} 0,0 1,${p(77f)},${p(54f)}" +
        "A${25.9f * k},${25.9f * k} 0,0 1,${p(31f)},${p(54f)}Z"
    val inner = "M${p(36.8f)},${p(54f)}A${20.9f * k},${20.9f * k} 0,0 1,${p(71.2f)},${p(54f)}" +
        "A${20.9f * k},${20.9f * k} 0,0 1,${p(36.8f)},${p(54f)}Z"
    val pupil = "${eye(6f)}a${6 * k},${6 * k} 0,1 0,${12 * k},0a${6 * k},${6 * k} 0,1 0,${-12 * k},0Z"

    ImageVector.Builder(defaultWidth = 48.dp, defaultHeight = 48.dp, viewportWidth = size, viewportHeight = size)
        .addPath(
            pathData = cookie,
            fill = Brush.verticalGradient(listOf(Color(0xFF3D3E43), Color(0xFF232428)), startY = 0f, endY = size),
            stroke = Brush.verticalGradient(listOf(Color(0x46FFFFFF), Color(0x0EFFFFFF)), startY = 0f, endY = size),
            strokeLineWidth = 1.3f,
        )
        .addPath(pathData = addPathNodes(outer + inner), fill = SolidColor(Color(0xFFEEEAE3)), pathFillType = PathFillType.EvenOdd)
        .addPath(pathData = addPathNodes(pupil), fill = SolidColor(Color(0xFFEEEAE3)))
        .build()
}
