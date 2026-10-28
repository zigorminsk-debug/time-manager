package com.zigorminsk.timemanager.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zigorminsk.timemanager.voice.VoiceLine
import com.zigorminsk.timemanager.voice.VoicePhase
import com.zigorminsk.timemanager.voice.VoiceUiState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

private val NightTop = Color(0xFF17163A)
private val NightBottom = Color(0xFF0A0A12)
private val OrbViolet = Color(0xFF7C6CFF)
private val OrbMint = Color(0xFF00C2A8)
private val OrbAmber = Color(0xFFFF8A3D)
private val OrbRed = Color(0xFFE2554A)

/**
 * Полноэкранный «живой» ассистент: пульсирующая сфера, которая дышит в такт
 * голосу, расшифровка речи в реальном времени, история диалога и подсказки.
 */
@Composable
fun VoiceAssistantOverlay(
    state: VoiceUiState,
    micGranted: Boolean,
    onClose: () -> Unit,
    onMic: () -> Unit,
    onStop: () -> Unit,
    onSend: (String) -> Unit,
    onToggleHandsFree: (Boolean) -> Unit,
    onHint: (String) -> Unit,
    onRequestMic: () -> Unit,
) {
    var typed by remember { mutableStateOf("") }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(NightTop, NightBottom)))
                .systemBarsPadding(),
        ) {
            // В ландшафте и на низких экранах включаем прокрутку и уменьшаем сферу,
            // чтобы кнопки микрофона и «Стоп» всегда оставались на виду.
            val compact = maxHeight < 620.dp
            Column(
                Modifier
                    .fillMaxSize()
                    .then(if (compact) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                VoiceHeader(
                    handsFree = state.handsFree,
                    onToggleHandsFree = { onToggleHandsFree(!state.handsFree) },
                    onClose = onClose,
                )

                Spacer(Modifier.height(4.dp))

                VoiceStage(state = state, compact = compact)

                if (!micGranted) {
                    Surface(
                        color = OrbRed.copy(alpha = 0.18f),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "Нужен доступ к микрофону",
                                color = Color.White,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Разрешите запись звука — и я начну слушать команды.",
                                color = Color.White.copy(alpha = 0.75f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.height(10.dp))
                            PrimaryButton(
                                text = "Разрешить микрофон",
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onRequestMic,
                            )
                        }
                    }
                } else {
                    if (state.partial.isNotBlank()) {
                        Text(
                            "«${state.partial}»",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = FontStyle.Italic,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                        )
                    }

                    VoiceHistory(
                        history = state.history,
                        modifier = if (compact) {
                            Modifier.height(150.dp).fillMaxWidth()
                        } else {
                            Modifier.weight(1f).fillMaxWidth()
                        },
                    )
                }

                if (state.hints.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(state.hints) { hint ->
                            Surface(
                                color = Color.White.copy(alpha = 0.10f),
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.clickable { onHint(hint) },
                            ) {
                                Text(
                                    hint,
                                    Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                                    color = Color.White.copy(alpha = 0.9f),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                }

                VoiceInput(
                    value = typed,
                    onValueChange = { typed = it },
                    onSend = {
                        if (typed.isNotBlank()) {
                            onSend(typed)
                            typed = ""
                        }
                    },
                )

                VoiceControls(
                    phase = state.phase,
                    micGranted = micGranted,
                    onMic = onMic,
                    onStop = onStop,
                    onClose = onClose,
                )
            }
        }
    }
}

private fun safeIconSize(orb: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
    if (orb < 180.dp) 36.dp else 56.dp

// ------------------------------------------------------------------ части экрана

@Composable
private fun VoiceHeader(
    handsFree: Boolean,
    onToggleHandsFree: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(OrbViolet.copy(alpha = 0.9f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Тайм",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "голосовой помощник",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Surface(
            color = if (handsFree) OrbMint.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.10f),
            shape = RoundedCornerShape(50),
            modifier = Modifier.clickable { onToggleHandsFree() },
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (handsFree) OrbMint else Color.White.copy(alpha = 0.4f)),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Без рук",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.White.copy(alpha = 0.8f))
        }
    }
}

@Composable
private fun VoiceStage(state: VoiceUiState, compact: Boolean = false) {
    val pulse by rememberInfiniteTransition(label = "orb").animateFloat(
        initialValue = 0.96f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val level by animateFloatAsState(
        targetValue = state.amplitude.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 110),
        label = "level",
    )
    val phase by rememberInfiniteTransition(label = "wave").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wavePhase",
    )
    val activeListening = state.phase == VoicePhase.LISTENING || state.phase == VoicePhase.WAKE
    val color = when (state.phase) {
        VoicePhase.LISTENING, VoicePhase.WAKE -> OrbViolet
        VoicePhase.THINKING -> OrbMint
        VoicePhase.SPEAKING -> OrbAmber
        VoicePhase.ERROR -> OrbRed
        VoicePhase.IDLE -> Color(0xFF8A8AA8)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            state.statusLabel,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(if (compact) 4.dp else 10.dp))
        VoiceOrb(
            color = color,
            level = if (activeListening || state.phase == VoicePhase.SPEAKING) level else 0.12f,
            pulse = pulse,
            thinking = state.phase == VoicePhase.THINKING,
            diameter = if (compact) 140.dp else 210.dp,
        )
        if (!compact) {
            Spacer(Modifier.height(6.dp))
            Waveform(
                level = if (activeListening) level else if (state.phase == VoicePhase.SPEAKING) 0.55f else 0.08f,
                phase = phase,
                color = color,
            )
        }
        Text(
            state.hintLine,
            color = Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

@Composable
private fun VoiceOrb(
    color: Color,
    level: Float,
    pulse: Float,
    thinking: Boolean,
    diameter: androidx.compose.ui.unit.Dp = 210.dp,
) {
    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val base = size.minDimension / 2f
            repeat(3) { index ->
                val ring = index + 1
                drawCircle(
                    color = color.copy(alpha = 0.14f / ring),
                    radius = base * (0.58f + ring * 0.12f) * pulse,
                    center = center,
                )
            }
            val radius = base * (0.44f + 0.16f * level) * pulse
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        color.copy(alpha = if (thinking) 0.7f else 0.95f),
                        color.copy(alpha = 0.25f),
                    ),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
            if (thinking) {
                // «Думаю»: три точки внутри сферы
                val dotRadius = base * 0.045f
                val offset = base * 0.16f
                listOf(-1f, 0f, 1f).forEachIndexed { index, shift ->
                    drawCircle(
                        color = Color.White.copy(alpha = 0.5f + 0.15f * index),
                        radius = dotRadius,
                        center = Offset(center.x + shift * offset, center.y),
                    )
                }
            }
        }
        if (!thinking) {
            Icon(
                AppIcons.Mic,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(safeIconSize(diameter)),
            )
        }
    }
}

@Composable
private fun Waveform(level: Float, phase: Float, color: Color) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 36.dp),
    ) {
        val bars = 29
        val step = size.width / bars
        for (index in 0 until bars) {
            val wave = sin(index * 0.55f + phase * 2f * PI.toFloat())
            val height = size.height * (0.08f + level * (0.2f + 0.8f * abs(wave)))
            val x = index * step + step * 0.3f
            drawRoundRect(
                color = color.copy(alpha = 0.25f + 0.55f * level),
                topLeft = Offset(x, (size.height - height) / 2f),
                size = Size(step * 0.4f, height),
                cornerRadius = CornerRadius(step * 0.2f, step * 0.2f),
            )
        }
    }
}

@Composable
private fun VoiceHistory(history: List<VoiceLine>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val shown = history.takeLast(24)
    LaunchedEffect(shown.size) {
        if (shown.isNotEmpty()) listState.animateScrollToItem(shown.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(shown) { line ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start,
            ) {
                Surface(
                    color = if (line.fromUser) Color.White.copy(alpha = 0.14f) else OrbViolet.copy(alpha = 0.28f),
                    shape = RoundedCornerShape(
                        topStart = 18.dp,
                        topEnd = 18.dp,
                        bottomStart = if (line.fromUser) 18.dp else 6.dp,
                        bottomEnd = if (line.fromUser) 6.dp else 18.dp,
                    ),
                    modifier = Modifier.widthIn(max = 300.dp),
                ) {
                    Text(
                        line.text,
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        color = Color.White.copy(alpha = if (line.fromUser) 0.95f else 0.9f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        singleLine = true,
        shape = RoundedCornerShape(18.dp),
        placeholder = {
            Text(
                "Напишите команду, если не слышно",
                color = Color.White.copy(alpha = 0.5f),
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingIcon = {
            IconButton(onClick = onSend, enabled = value.isNotBlank()) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = "Отправить",
                    tint = if (value.isNotBlank()) Color.White else Color.White.copy(alpha = 0.3f),
                )
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSend() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = Color.White,
            focusedBorderColor = Color.White.copy(alpha = 0.5f),
            unfocusedBorderColor = Color.White.copy(alpha = 0.22f),
            focusedContainerColor = Color.White.copy(alpha = 0.06f),
            unfocusedContainerColor = Color.White.copy(alpha = 0.04f),
        ),
    )
}

@Composable
private fun VoiceControls(
    phase: VoicePhase,
    micGranted: Boolean,
    onMic: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
) {
    val listening = phase == VoicePhase.LISTENING || phase == VoicePhase.WAKE
    val speaking = phase == VoicePhase.SPEAKING
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            TextButton(
                onClick = onClose,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.7f)),
            ) {
                Text("Закрыть")
            }
        }
        Box(
            Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(
                    if (listening) OrbViolet else Color.White.copy(alpha = 0.14f),
                )
                .clickable(enabled = micGranted) { onMic() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                AppIcons.Mic,
                contentDescription = "Говорить",
                tint = if (micGranted) Color.White else Color.White.copy(alpha = 0.35f),
                modifier = Modifier.size(34.dp),
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            TextButton(
                onClick = onStop,
                enabled = speaking,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = Color.White.copy(alpha = 0.84f),
                    disabledContentColor = Color.White.copy(alpha = 0.3f),
                ),
            ) {
                Text("Стоп")
            }
        }
    }
}
