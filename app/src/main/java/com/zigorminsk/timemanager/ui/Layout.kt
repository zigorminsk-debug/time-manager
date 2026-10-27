package com.zigorminsk.timemanager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Единые размеры интерфейса: все кнопки одного назначения выглядят одинаково
 * на любом экране — от 320 dp до планшета.
 */
object Dims {
    /** Высота основной кнопки действия. */
    val Button = 52.dp

    /** Высота вторичной кнопки внутри карточки. */
    val ButtonSmall = 44.dp

    /** Минимальная область нажатия (требование Material). */
    val Touch = 48.dp

    /** Единый радиус скругления кнопок. */
    val Radius = 16.dp

    /** Ширина читаемой колонки: на планшете контент не растягивается. */
    val ContentMax = 640.dp

    /** Высота чипа. */
    val Chip = 40.dp
}

/**
 * Центрирует содержимое и ограничивает ширину на больших экранах
 * (планшет, ландшафт). На телефоне ведёт себя как обычный fillMaxSize.
 */
@Composable
fun AdaptiveScreen(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.fillMaxHeight().widthIn(max = Dims.ContentMax).fillMaxWidth()) {
            content()
        }
    }
}

/** true для узких экранов (телефон в портрете). */
@Composable
fun isCompactWidth(): Boolean {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return widthDp < 400
}

/** Основная кнопка: одинаковая высота, радиус и типографика везде. */
@Composable
fun PrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = Dims.Button),
        enabled = enabled,
        shape = RoundedCornerShape(Dims.Radius),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Вторичная кнопка того же размера, что и основная. */
@Composable
fun SecondaryButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = Dims.Button),
        enabled = enabled,
        shape = RoundedCornerShape(Dims.Radius),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Компактная кнопка внутри карточки — одна высота у всех. */
@Composable
fun SmallButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = Dims.ButtonSmall),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Кнопки в ряд с равной шириной и одинаковой высотой. */
@Composable
fun EqualButtonRow(
    modifier: Modifier = Modifier,
    spacing: Dp = 8.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Чип одного размера с остальными (высота и радиус фиксированы). */
@Composable
fun AppFilterChip(
    selected: Boolean,
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        },
        modifier = modifier.heightIn(min = Dims.Chip),
        shape = RoundedCornerShape(12.dp),
        colors = FilterChipDefaults.filterChipColors(),
    )
}

/** Текстовая кнопка с гарантированной областью нажатия. */
@Composable
fun AppTextButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = Dims.Touch)) {
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Квадратная иконка-кнопка ровно 48×48 dp — не «прыгает» рядом с кнопками. */
@Composable
fun SquareIconButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = modifier.size(Dims.Touch)) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(22.dp))
    }
}

/**
 * Быстрый выбор времени: сетка с равной шириной кнопок.
 * На узком экране — 2 колонки, на широком — 3.
 */
@Composable
fun QuickTimeGrid(
    modifier: Modifier = Modifier,
    onPick: (Long) -> Unit,
) {
    val options = quickOptions()
    val columns = if (isCompactWidth()) 2 else 3
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { (label, millis) ->
                    OutlinedButton(
                        onClick = { onPick(millis) },
                        modifier = Modifier.weight(1f).heightIn(min = Dims.ButtonSmall),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                repeat(columns - row.size) {
                    Box(Modifier.weight(1f))
                }
            }
        }
    }
}
