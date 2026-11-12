package com.camera2pc

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.jetbrains.JBR
import com.jetbrains.WindowDecorations

/**
 * Своя строка заголовка на JetBrains Runtime (как в Pier.doon). Windows по-прежнему управляет
 * рамкой: растягивает, прилипает к краям, разворачивает по двойному щелчку сверху.
 * На обычном JDK остаётся системный заголовок.
 */
object WindowChrome {
    val HEIGHT = 48.dp
    private var bar: WindowDecorations.CustomTitleBar? = null
    val controls = mutableStateOf<WindowControls?>(null)

    fun install(window: java.awt.Frame, state: WindowState, onClose: () -> Unit): Boolean {
        val supported = runCatching { JBR.isWindowDecorationsSupported() }.getOrDefault(false)
        if (!supported) return false
        val decorations = JBR.getWindowDecorations()
        val titleBar = decorations.createCustomTitleBar().apply {
            height = HEIGHT.value
            putProperty("controls.visible", false)
        }
        decorations.setCustomTitleBar(window, titleBar)
        bar = titleBar
        controls.value = WindowControls(state, onClose)
        return true
    }

    internal fun hitTest(client: Boolean) {
        bar?.forceHitTest(client)
    }
}

class WindowControls(val state: WindowState, val onClose: () -> Unit)

/** Сообщает рантайму, чей клик в верхней полосе: наш (кнопка, поле) или окна (перетаскивание). */
fun Modifier.titleBarHitTest(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        var held = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val taken = event.changes.any { it.isConsumed }
            if (event.type == PointerEventType.Press) held = taken
            WindowChrome.hitTest(taken || held)
            if (event.type == PointerEventType.Release) held = false
        }
    }
}

/** Свернуть, развернуть, закрыть — капсулой в правом верхнем углу. */
@Composable
fun WindowButtons(controls: WindowControls, modifier: Modifier = Modifier) {
    val state = controls.state
    val colors = MaterialTheme.colorScheme
    val maximized = state.placement == WindowPlacement.Maximized
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(top = 10.dp, end = 10.dp)
            .clip(CircleShape)
            .background(colors.surfaceContainerHigh)
            .padding(3.dp),
    ) {
        ChromeButton("Свернуть", colors.surfaceContainerHighest, colors.onSurface, { state.isMinimized = true }) { minimizeGlyph(it) }
        ChromeButton(if (maximized) "Восстановить" else "Развернуть", colors.surfaceContainerHighest, colors.onSurface, {
            state.placement = if (maximized) WindowPlacement.Floating else WindowPlacement.Maximized
        }) { if (maximized) restoreGlyph(it) else maximizeGlyph(it) }
        ChromeButton("Закрыть", colors.error, colors.onError, controls.onClose) { closeGlyph(it) }
    }
}

@Composable
private fun ChromeButton(label: String, hover: Color, onHover: Color, onClick: () -> Unit, glyph: DrawScope.(Color) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val background by animateColorAsState(if (hovered) hover else Color.Transparent, label = "bg")
    val tint by animateColorAsState(if (hovered) onHover else colors.onSurfaceVariant, label = "fg")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 40.dp, height = 28.dp)
            .clip(CircleShape)
            .background(background)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        Canvas(Modifier.size(10.dp)) { glyph(tint) }
    }
}

private fun DrawScope.stroke() = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round)

private fun DrawScope.minimizeGlyph(tint: Color) {
    drawLine(tint, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.2.dp.toPx(), StrokeCap.Round)
}

private fun DrawScope.maximizeGlyph(tint: Color) {
    drawRoundRect(tint, style = stroke(), cornerRadius = CornerRadius(2.dp.toPx()))
}

private fun DrawScope.restoreGlyph(tint: Color) {
    val inset = 2.5.dp.toPx()
    val w = 1.2.dp.toPx()
    drawLine(tint, Offset(inset, 0f), Offset(size.width, 0f), w, StrokeCap.Round)
    drawLine(tint, Offset(size.width, 0f), Offset(size.width, size.height - inset), w, StrokeCap.Round)
    drawRoundRect(
        tint, topLeft = Offset(0f, inset),
        size = androidx.compose.ui.geometry.Size(size.width - inset, size.height - inset),
        style = stroke(), cornerRadius = CornerRadius(1.5.dp.toPx()),
    )
}

private fun DrawScope.closeGlyph(tint: Color) {
    val w = 1.2.dp.toPx()
    drawLine(tint, Offset.Zero, Offset(size.width, size.height), w, StrokeCap.Round)
    drawLine(tint, Offset(size.width, 0f), Offset(0f, size.height), w, StrokeCap.Round)
}
