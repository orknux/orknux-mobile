package io.mszymanski.orknux.mobile.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.mszymanski.orknux.mobile.core.AppSession
import io.mszymanski.orknux.mobile.design.Ork
import io.mszymanski.orknux.mobile.design.OrkIconButton
import io.mszymanski.orknux.mobile.design.OrkIcons
import io.mszymanski.orknux.mobile.design.SectionLabel
import io.mszymanski.orknux.mobile.voice.AndroidThinkingSound
import io.mszymanski.orknux.mobile.voice.DeviceTranscriber
import io.mszymanski.orknux.mobile.voice.PULSE_ROUND_MS
import io.mszymanski.orknux.mobile.voice.SpeechEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

const val OPTIONS_ROUTE = "options"

private const val MOST_PAUSE_MS = 30_000f
private val THINKING_PITCHES = listOf(240 to "Low", 340 to "Medium", 480 to "High", 650 to "Higher")

@Composable
fun OptionsScreen(session: AppSession, onBack: () -> Unit) {
    val context = LocalContext.current
    var engine by remember { mutableStateOf(session.speechEngine) }
    var listening by remember { mutableStateOf(session.listenEngine) }
    var thinkingPause by remember { mutableStateOf(session.thinkingPauseMs) }
    var thinkingPitch by remember { mutableStateOf(THINKING_PITCHES.minBy { (hz, _) -> abs(hz - session.thinkingPitchHz) }.first) }
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<Job?>(null) }
    val phoneCanListen = remember(context) { DeviceTranscriber.isAvailable(context) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().background(Ork.surface).padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OrkIconButton(OrkIcons.ArrowLeft, "Back", onBack)
            Text("Options", style = MaterialTheme.typography.titleMedium)
        }
        HorizontalDivider(color = Ork.border)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val shape = RoundedCornerShape(Ork.radius8)
            SectionLabel("Speech to text")
            Column(Modifier.fillMaxWidth().clip(shape).background(Ork.surface).border(1.dp, Ork.border, shape).selectableGroup()) {
                EngineRow("Server", "The workspace's transcription model, or this phone if it has none", listening == SpeechEngine.SERVER) {
                    listening = SpeechEngine.SERVER
                    session.listenEngine = SpeechEngine.SERVER
                }
                HorizontalDivider(color = Ork.border)
                EngineRow(
                    "This phone",
                    if (phoneCanListen) "Android's on-device recognition, in the phone's language" else "This phone has no on-device recognition",
                    listening == SpeechEngine.DEVICE,
                    enabled = phoneCanListen,
                ) {
                    listening = SpeechEngine.DEVICE
                    session.listenEngine = SpeechEngine.DEVICE
                }
            }
            SectionLabel("Reading aloud", Modifier.padding(top = 10.dp))
            Column(Modifier.fillMaxWidth().clip(shape).background(Ork.surface).border(1.dp, Ork.border, shape).selectableGroup()) {
                EngineRow("Server", "The workspace's speech model, or this phone if it has none", engine == SpeechEngine.SERVER) {
                    engine = SpeechEngine.SERVER
                    session.speechEngine = SpeechEngine.SERVER
                }
                HorizontalDivider(color = Ork.border)
                EngineRow("This phone", "Android's text-to-speech, in the phone's language", engine == SpeechEngine.DEVICE) {
                    engine = SpeechEngine.DEVICE
                    session.speechEngine = SpeechEngine.DEVICE
                }
            }
            SectionLabel("Voice mode's thinking sound", Modifier.padding(top = 10.dp))
            Column(
                Modifier.fillMaxWidth().clip(shape).background(Ork.surface).border(1.dp, Ork.border, shape).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SliderRow("Pause between sounds", "%.1f s".format(Locale.ROOT, thinkingPause / 1000f), thinkingPause.toFloat(), 0f..MOST_PAUSE_MS, {
                    thinkingPause = (it / 100).roundToInt() * 100
                }, { session.thinkingPauseMs = thinkingPause })
                Text("Pitch", style = MaterialTheme.typography.titleSmall)
                ChipRow(THINKING_PITCHES, thinkingPitch) { hz ->
                    thinkingPitch = hz
                    session.thinkingPitchHz = hz
                    // One round, so the choice can be heard rather than guessed at.
                    preview?.cancel()
                    preview = scope.launch {
                        val sound = AndroidThinkingSound(pauseMs = 1_000, pitchHz = hz, conversation = false)
                        try {
                            sound.start()
                            delay(PULSE_ROUND_MS + 100)
                        } finally {
                            sound.close()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChipRow(choices: List<Pair<Int, String>>, chosen: Int, onChoose: (Int) -> Unit) {
    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        choices.forEach { (value, label) ->
            val on = value == chosen
            val chip = RoundedCornerShape(Ork.radius6)
            Text(
                label,
                Modifier.weight(1f).clip(chip).background(if (on) Ork.accentSoft else Ork.surfaceRaised)
                    .border(1.dp, if (on) Ork.accent else Ork.border, chip)
                    .selectable(on, role = Role.RadioButton) { onChoose(value) }.padding(vertical = 10.dp),
                style = MaterialTheme.typography.labelMedium,
                color = if (on) Ork.textPrimary else Ork.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text(value, style = MaterialTheme.typography.labelMedium, color = Ork.textSecondary)
        }
        Slider(
            current,
            onChange,
            valueRange = range,
            onValueChangeFinished = onDone,
            colors = SliderDefaults.colors(thumbColor = Ork.accent, activeTrackColor = Ork.accent, inactiveTrackColor = Ork.surfaceHover),
        )
    }
}

@Composable
private fun EngineRow(title: String, detail: String, selected: Boolean, enabled: Boolean = true, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) Ork.textPrimary else Ork.textMuted)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Ork.textMuted)
        }
        RadioButton(selected, null, enabled = enabled, colors = RadioButtonDefaults.colors(selectedColor = Ork.accent, unselectedColor = Ork.textMuted))
    }
}
