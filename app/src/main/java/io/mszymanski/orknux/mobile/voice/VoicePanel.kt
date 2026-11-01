package io.mszymanski.orknux.mobile.voice

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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.mszymanski.orknux.mobile.design.MonoLabel
import io.mszymanski.orknux.mobile.design.Ork
import io.mszymanski.orknux.mobile.design.SecondaryButton
import io.mszymanski.orknux.mobile.design.Sky
import kotlin.math.abs
import kotlin.math.sin

private val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

/**
 * One large circle, and the circle is the state: moving with your voice while it
 * listens, breathing while it thinks, ringing outward while it speaks.
 */
@Composable
fun VoicePanel(
    ui: VoiceUi,
    onTap: () -> Unit,
    onMute: () -> Unit,
    onSpeed: (Float) -> Unit,
    onCancelWaiting: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().background(Ork.surface.copy(alpha = 0.6f))) {
        Sky(Modifier.matchParentSize(), drift = true, washAt = 0.42f)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            VoiceCircle(ui.phase, ui.level, ui.muted, onTap)
            Text(
                when {
                    ui.muted -> "Muted"
                    ui.phase == VoicePhase.LISTENING -> "Listening"
                    ui.phase == VoicePhase.THINKING -> "Thinking"
                    else -> "Speaking"
                }.uppercase(),
                style = MonoLabel,
                color = if (ui.muted) Ork.amber else Ork.textSecondary,
            )
            if (ui.caption.isNotEmpty()) {
                Text(
                    ui.caption,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ork.textBody,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ui.trouble?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Ork.danger, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (ui.waiting.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(Ork.radius6)).background(Ork.amberSoft).padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("WAITING", style = MonoLabel, color = Ork.amber)
                    Text(ui.waiting, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Ork.textBody, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("Cancel", Modifier.clickable(onClick = onCancelWaiting).padding(4.dp), style = MaterialTheme.typography.labelSmall, color = Ork.textSecondary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SecondaryButton(if (ui.muted) "Unmute" else "Mute", onMute, compact = true, color = if (ui.muted) Ork.amber else Ork.textPrimary)
                SpeedPicker(ui.speed, onSpeed)
                SecondaryButton("End", onClose, compact = true)
            }
        }
    }
}

@Composable
private fun SpeedPicker(speed: Float, onSpeed: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        SecondaryButton(speedLabel(speed), { open = true }, compact = true)
        DropdownMenu(open, { open = false }, containerColor = Ork.surfaceRaised) {
            SPEEDS.forEach { one ->
                DropdownMenuItem(text = { Text(speedLabel(one)) }, onClick = { open = false; onSpeed(one) })
            }
        }
    }
}

private fun speedLabel(speed: Float) = if (speed == 1f) "1×" else "${speed.toString().trimEnd('0').trimEnd('.')}×"

@Composable
private fun VoiceCircle(phase: VoicePhase, level: Float, muted: Boolean, onTap: () -> Unit) {
    val time by rememberInfiniteTransition(label = "voice").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2_400, easing = LinearEasing), RepeatMode.Restart), label = "time",
    )
    val shownLevel by animateFloatAsState(if (phase == VoicePhase.LISTENING) level else 0f, tween(90), label = "level")
    val breath = (sin(time * 2 * Math.PI).toFloat() + 1f) / 2f
    val scale = when (phase) {
        VoicePhase.LISTENING -> 1f + shownLevel * 0.28f
        VoicePhase.THINKING -> 0.94f + breath * 0.1f
        VoicePhase.SPEAKING -> 1f
    }
    Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        if (phase == VoicePhase.SPEAKING) {
            Canvas(Modifier.fillMaxSize()) {
                repeat(3) { ring ->
                    val at = (time + ring / 3f) % 1f
                    drawCircle(
                        Ork.accent.copy(alpha = (1f - at) * 0.55f),
                        radius = size.minDimension / 2 * (0.68f + at * 0.32f),
                        style = Stroke(1.5.dp.toPx()),
                    )
                }
            }
        }
        Box(
            Modifier.size(104.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .background(if (muted) Ork.surfaceHover else Ork.accent)
                .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onTap),
            contentAlignment = Alignment.Center,
        ) {
            Waveform(Modifier.size(44.dp), if (muted) Ork.textMuted else Ork.onAccent, phase, time, shownLevel)
        }
    }
}

/** The bars in the circle, and on the voice button in the composer. */
@Composable
fun Waveform(modifier: Modifier, color: Color, phase: VoicePhase? = null, time: Float = 0f, level: Float = 0f) {
    Canvas(modifier) {
        val shape = listOf(0.35f, 0.7f, 1f, 0.55f, 0.85f, 0.4f)
        val bar = size.width / (shape.size * 2 - 1)
        shape.forEachIndexed { index, base ->
            val moving = when (phase) {
                VoicePhase.LISTENING -> 0.45f + level * 1.5f
                VoicePhase.THINKING -> 0.6f + abs(sin((time + index * 0.12f) * Math.PI.toFloat())) * 0.4f
                VoicePhase.SPEAKING -> 0.4f + abs(sin((time * 2 + index * 0.2f) * Math.PI.toFloat())) * 0.6f
                null -> 1f
            }.coerceAtMost(1f)
            val height = size.height * base * moving
            drawRoundRect(
                color,
                topLeft = Offset(index * bar * 2, (size.height - height) / 2),
                size = Size(bar, height),
                cornerRadius = CornerRadius(bar / 2),
            )
        }
    }
}
