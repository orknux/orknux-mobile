package io.mszymanski.orknux.mobile.voice

import io.mszymanski.orknux.mobile.chat.TurnEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class VoicePhase { LISTENING, THINKING, SPEAKING }

data class VoiceUi(
    val phase: VoicePhase = VoicePhase.LISTENING,
    val level: Float = 0f,
    /** What it made of the last turn, where a mishearing is obvious. */
    val caption: String = "",
    /** Said while the model had the turn, held for when the turn comes round. */
    val waiting: String = "",
    val muted: Boolean = false,
    val speed: Float = 1f,
    val trouble: String? = null,
)

/** What voice needs of the chat it is open on. */
interface VoiceConversation {
    /** Says this as a voice turn, once any turn being closed has closed. */
    fun say(text: String)
    fun interrupt()
}

/**
 * A conversation held out loud: listens, sends what it heard, reads the answer
 * back, listens again, with no key pressed between turns. The rules are the web
 * panel's, in `orknux-ui/src/docs/06-chat.md` under "Talking to it".
 *
 * State changes on [main]; the microphone runs off it and only reads what it
 * must, so a slow screen never drops audio.
 */
class VoiceSession(
    private val scope: CoroutineScope,
    private val microphone: Microphone,
    private val tuning: VoiceTuning,
    private val chunking: SpeechChunking,
    private val conversation: VoiceConversation,
    private val transcribe: suspend (ByteArray) -> String,
    private val speak: suspend (String) -> ByteArray,
    private val fetchSound: suspend (String) -> ByteArray,
    private val player: ClipPlayer,
    initialSpeed: Float,
    private val onSpeedChosen: (Float) -> Unit = {},
    private val thinkingSound: ThinkingSound = ThinkingSound.None,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    /** How long one frame of what the microphone hands over lasts. */
    private val frameMs: Long = 20,
) {
    private val _ui = MutableStateFlow(VoiceUi(speed = initialSpeed))
    val ui: StateFlow<VoiceUi> = _ui.asStateFlow()

    private var micJob: Job? = null
    private var reading: Reading? = null
    private var voiced: VoicedTurn? = null
    private val answer = StringBuilder()
    private var turnRunning = false
    /** The running turn was given up on: nothing more of it is read. */
    private var cutting = false
    private var transcribing = 0
    private var speaking = false
    private var closed = false

    @Volatile private var muted = false
    @Volatile private var interruptible = false
    @Volatile private var endNow = false

    fun start() {
        micJob = scope.launch(Dispatchers.Default) {
            try {
                listen()
            } catch (stopped: CancellationException) {
                throw stopped
            } catch (failure: Exception) {
                withContext(main) { trouble(failure.message ?: "The microphone stopped.") }
            }
        }
    }

    private suspend fun listen() {
        val detector = TurnDetector(tuning)
        val capture = TurnCapture()
        var now = 0L
        var levelShown = 0f
        microphone.listen { frame, count ->
            now += frameMs
            val heard = detector.frame(TurnDetector.loudnessOf(frame, count), now, muted, interruptible)
            if (!muted) capture.add(frame, count, kept = detector.anythingSaid)
            if (heard.bargeIn) withContext(main) { cut(keepWaiting = false) }
            if (kotlin.math.abs(heard.level - levelShown) > 0.02f) {
                levelShown = heard.level
                _ui.update { it.copy(level = levelShown) }
            }
            if (heard.turnOver || endNow) {
                val said = detector.anythingSaid
                endNow = false
                val wav = if (said) capture.wav() else null
                capture.clear()
                detector.nextTurn()
                if (wav != null) withContext(main) { heardATurn(wav) }
            }
        }
    }

    private fun heardATurn(wav: ByteArray) {
        transcribing++
        refreshPhase()
        scope.launch(main) {
            val text = try {
                transcribe(wav).trim()
            } catch (stopped: CancellationException) {
                throw stopped
            } catch (failure: Exception) {
                trouble(failure.message ?: "That could not be transcribed.")
                ""
            }
            transcribing--
            if (text.isNotEmpty()) {
                _ui.update { it.copy(caption = text, trouble = null) }
                if (turnRunning || reading != null) {
                    _ui.update { it.copy(waiting = if (it.waiting.isEmpty()) text else "${it.waiting} $text") }
                } else {
                    submit(text)
                }
            }
            refreshPhase()
        }
    }

    private fun submit(text: String) {
        turnRunning = true
        cutting = false
        refreshPhase()
        conversation.say(text)
    }

    /** A turn began on this chat: asked here, typed in the composer, or woken by the server. */
    fun onTurnBegan() {
        turnRunning = true
        if (cutting) return
        beginReading()
        refreshPhase()
    }

    private fun beginReading() {
        reading?.stop()
        speaking = false
        answer.clear()
        var self: Reading? = null
        self = Reading(
            scope = scope,
            chunking = chunking,
            speak = speak,
            fetchSound = fetchSound,
            player = player,
            speed = { _ui.value.speed },
            onPlaying = {
                speaking = true
                refreshPhase()
            },
            onTrouble = ::trouble,
            onFinished = {
                if (reading === self) {
                    reading = null
                    speaking = false
                    flushWaiting()
                    refreshPhase()
                }
            },
        )
        reading = self
        voiced = VoicedTurn { soFar, settled -> self.push(soFar, settled) }
    }

    fun onTurnEvent(event: TurnEvent) {
        if (cutting) return
        when (event) {
            is TurnEvent.Said -> voiced?.said(event.text)
            is TurnEvent.Chunk -> {
                answer.append(event.text)
                voiced?.answered(answer.toString())
            }
            is TurnEvent.Play -> reading?.sound(event.url)
            is TurnEvent.Failed -> trouble(event.reason)
            else -> Unit
        }
    }

    fun onTurnOver() {
        turnRunning = false
        cutting = false
        reading?.finish(voiced?.text().orEmpty())
        flushWaiting()
        refreshPhase()
    }

    private fun flushWaiting() {
        val held = _ui.value.waiting
        if (held.isEmpty() || turnRunning || reading != null) return
        _ui.update { it.copy(waiting = "") }
        submit(held)
    }

    /**
     * The circle. Listening, it takes what was said so far as the whole turn;
     * otherwise it drops the turn, and what was waiting with it, and listens.
     */
    fun tap() {
        if (_ui.value.phase == VoicePhase.LISTENING) endNow = true else cut(keepWaiting = false)
    }

    private fun cut(keepWaiting: Boolean) {
        reading?.stop()
        reading = null
        voiced = null
        speaking = false
        if (!keepWaiting) _ui.update { it.copy(waiting = "") }
        if (turnRunning && !cutting) {
            cutting = true
            conversation.interrupt()
        }
        refreshPhase()
    }

    fun cancelWaiting() = _ui.update { it.copy(waiting = "") }

    fun toggleMute() {
        muted = !muted
        _ui.update { it.copy(muted = muted, level = 0f) }
    }

    fun setSpeed(speed: Float) {
        _ui.update { it.copy(speed = speed) }
        onSpeedChosen(speed)
    }

    /** Closes the panel. The turn stays running: leaving is not stopping, and the answer lands in the history. */
    fun close() {
        closed = true
        thinkingSound.close()
        micJob?.cancel()
        reading?.stop()
        reading = null
    }

    private fun trouble(message: String) {
        _ui.update { it.copy(trouble = message) }
    }

    private fun refreshPhase() {
        if (closed) return
        val phase = when {
            speaking -> VoicePhase.SPEAKING
            turnRunning || reading != null || transcribing > 0 -> VoicePhase.THINKING
            else -> VoicePhase.LISTENING
        }
        interruptible = phase == VoicePhase.SPEAKING || (phase == VoicePhase.THINKING && turnRunning && tuning.bargeWhileThinking)
        if (phase == VoicePhase.THINKING) thinkingSound.start() else thinkingSound.stop()
        _ui.update { it.copy(phase = phase) }
    }
}

/** What has been heard of this turn, with a moment before speech so its first word is not clipped. */
internal class TurnCapture(private val preRollFrames: Int = 15) {
    private val frames = ArrayDeque<ShortArray>()

    fun add(frame: ShortArray, count: Int, kept: Boolean) {
        frames.addLast(frame.copyOf(count))
        if (!kept) while (frames.size > preRollFrames) frames.removeFirst()
    }

    fun wav(): ByteArray {
        val all = ShortArray(frames.sumOf { it.size })
        var at = 0
        frames.forEach {
            it.copyInto(all, at)
            at += it.size
        }
        return pcmToWav(all)
    }

    fun clear() = frames.clear()
}
