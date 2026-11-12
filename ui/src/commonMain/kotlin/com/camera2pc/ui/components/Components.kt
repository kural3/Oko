package com.camera2pc.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Pier.doon's big rounded card corner. */
val TileShape = RoundedCornerShape(28.dp)

/**
 * An oversized [shape] peeking in from the card's top-right corner, behind its content: the
 * home cards' mark in Pier.doon. Drawn, not laid out, and cached until the card resizes.
 * Goes after the card's background and before its padding.
 */
fun Modifier.cornerDecor(
    shape: Shape,
    color: Color,
    size: Dp = 132.dp,
    rotation: Float = 18f,
): Modifier = drawWithCache {
    val px = size.toPx()
    val outline = shape.createOutline(Size(px, px), layoutDirection, this)
    onDrawBehind {
        translate(left = this.size.width - px * 0.62f, top = -px * 0.34f) {
            rotate(rotation, pivot = Offset(px / 2, px / 2)) {
                drawOutline(outline, color)
            }
        }
    }
}

/** An icon on a soft expressive shape, as on Pier.doon's home cards. */
@Composable
fun ShapeIcon(
    icon: ImageVector,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    shape: Shape = MaterialShapes.Cookie9Sided.toShape(),
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(size).clip(shape).background(container),
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(size * 0.5f))
    }
}

/**
 * A home-screen card: tonal container, a big shape in the corner, a shape icon, a title and a
 * subtitle, then whatever [content] adds.
 */
@Composable
fun Tile(
    title: String,
    icon: ImageVector,
    container: Color,
    onContainer: Color,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    decor: Shape = MaterialShapes.Cookie12Sided.toShape(),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val accent = onContainer.copy(alpha = 0.10f)
    Column(
        modifier = modifier
            .clip(TileShape)
            .background(container)
            .cornerDecor(decor, accent)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
    ) {
        ShapeIcon(icon, container = onContainer.copy(alpha = 0.16f), content = onContainer)
        Spacer(Modifier.height(12.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = onContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = onContainer.copy(alpha = 0.78f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        content()
    }
}

/** Live status as a capsule with a dot that breathes while [live]. */
@Composable
fun StatusPill(
    text: String,
    live: Boolean,
    modifier: Modifier = Modifier,
    warn: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val dotColor by animateColorAsState(
        when {
            warn -> colors.error
            live -> Color(0xFF6DD58C)
            else -> colors.onSurfaceVariant
        },
        label = "dot",
    )
    val pulse = rememberInfiniteTransition(label = "pulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (live) 0.35f else 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "alpha",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .clip(CircleShape)
            .background(colors.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .graphicsLayer { this.alpha = alpha }
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(text, style = MaterialTheme.typography.labelLarge, color = colors.onSurface, maxLines = 1)
    }
}

/** A label over a big value, for the stats row. */
@Composable
fun StatValue(label: String, value: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.titleLargeEmphasized, color = color, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Smoothly animated float, for counters that shouldn't jump. */
@Composable
fun animatedValue(target: Float): Float {
    val v by animateFloatAsState(target, tween(400), label = "value")
    return v
}

@Composable
fun HSpace(w: Dp) = Spacer(Modifier.width(w))

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))
