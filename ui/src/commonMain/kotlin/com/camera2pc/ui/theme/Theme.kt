package com.camera2pc.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** Запасной цвет, если обои не дали цвета: красный Pier.doon. */
const val CAMERA2PC_SEED: Int = 0xFFB3261E.toInt()

/** Тёмная схема из одного цвета, с чёрным фоном как в Pier.doon. */
fun schemeFromSeed(seed: Int): ColorScheme = SchemeGenerator.scheme(seed, dark = true).pureBlack()

val Camera2PCColors: ColorScheme = schemeFromSeed(CAMERA2PC_SEED)

/** True-black backgrounds with containers stepped up just enough to stay distinguishable. */
fun ColorScheme.pureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0D),
    surfaceContainer = Color(0xFF121215),
    surfaceContainerHigh = Color(0xFF1A1A1E),
    surfaceContainerHighest = Color(0xFF232328),
)

/** Тема приложения. Смена схемы (новые обои) — плавный переход цветов. */
@Composable
fun Camera2PCTheme(colorScheme: ColorScheme = Camera2PCColors, content: @Composable () -> Unit) {
    var from by remember { mutableStateOf(colorScheme) }
    var to by remember { mutableStateOf(colorScheme) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(colorScheme) {
        if (colorScheme == to) return@LaunchedEffect
        from = lerpScheme(from, to, progress.value)
        to = colorScheme
        progress.snapTo(0f)
        progress.animateTo(1f, tween(700))
    }
    val scheme = if (progress.value >= 1f) to else lerpScheme(from, to, progress.value)
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurface,
            content = content,
        )
    }
}
