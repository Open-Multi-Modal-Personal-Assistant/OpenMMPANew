package ai.wristmind.app.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SpeechUiState(
    val isListening: Boolean = false,
    val partialTranscript: String = "",
    val ttsReady: Boolean = false,
    val preferOfflineRecognition: Boolean = true,
    val lastError: String? = null
)

/**
 * Replaces legacy OpenMMPA `STTSFunctions` Cloud Functions + `TTSEncodings.xlsx` with zero-latency
 * native Android 15 `SpeechRecognizer` (`EXTRA_PREFER_OFFLINE`) and system `TextToSpeech`.
 */
class WristSpeechController(
    private val context: Context
) : AutoCloseable {

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null

    private val _state = MutableStateFlow(SpeechUiState())
    val state: StateFlow<SpeechUiState> = _state.asStateFlow()

    init {
        textToSpeech = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale.US
                textToSpeech?.setSpeechRate(1.08f)
                _state.value = _state.value.copy(ttsReady = true)
            }
        }
    }

    /**
     * Must be invoked on the Main thread (e.g., from a Compose Push-to-Talk button handler).
     */
    fun startPushToTalk(
        preferOffline: Boolean = true,
        onFinalUtterance: (String) -> Unit
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _state.value = _state.value.copy(
                isListening = false,
                lastError = "On-device SpeechRecognizer unavailable on this device image."
            )
            return
        }

        stopListening()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer = recognizer

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _state.value = _state.value.copy(
                    isListening = true,
                    partialTranscript = "Listening on wrist...",
                    preferOfflineRecognition = preferOffline,
                    lastError = null
                )
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                _state.value = _state.value.copy(isListening = false)
            }

            override fun onError(error: Int) {
                _state.value = _state.value.copy(
                    isListening = false,
                    lastError = "Speech code $error"
                )
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val best = matches?.firstOrNull().orEmpty().trim()
                _state.value = _state.value.copy(
                    isListening = false,
                    partialTranscript = best
                )
                if (best.isNotEmpty()) {
                    onFinalUtterance(best)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (partial.isNotBlank()) {
                    _state.value = _state.value.copy(partialTranscript = partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer.startListening(intent)
    }

    fun stopListening() {
        runCatching { speechRecognizer?.stopListening() }
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
        _state.value = _state.value.copy(isListening = false)
    }

    fun speakConciseReply(text: String) {
        if (!_state.value.ttsReady || text.isBlank()) return
        val cleaned = text
            .replace(Regex("""\[[^\]]*]"""), "")
            .replace("*", "")
            .trim()
            .take(260)
        textToSpeech?.speak(cleaned, TextToSpeech.QUEUE_FLUSH, null, "wristmind-reply")
    }

    override fun close() {
        stopListening()
        runCatching {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        }
        textToSpeech = null
    }
}
