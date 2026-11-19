package ai.jarvis.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * §11 — voice layer. On-device SpeechRecognizer + TextToSpeech.
 * No silent recording: `startListening` is only ever called from an explicit mic tap, and the UI
 * shows a listening indicator for the whole duration.
 */
class VoiceManager(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeak: String? = null

    init {
        runCatching {
            tts = TextToSpeech(context) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    runCatching { tts?.language = Locale("hi", "IN") }
                    pendingSpeak?.let { speak(it); pendingSpeak = null }
                }
            }
        }
    }

    val isListening: Boolean get() = recognizer != null

    fun startListening(
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition is device par available nahi hai")
            return
        }
        stop()
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = onPartial("")
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) {
                val message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Kuch sunai nahi diya"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Timeout — dobara try karo"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission nahi hai"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network issue"
                    else -> "Speech error $error"
                }
                stop()
                onError(message)
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                stop()
                if (text.isBlank()) onError("Kuch samajh nahi aaya") else onResult(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) onPartial(text)
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "hi-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching { sr.startListening(intent) }
            .onFailure {
                stop()
                onError(it.message ?: "Recognizer start nahi hua")
            }
    }

    fun stop() {
        runCatching { recognizer?.stopListening(); recognizer?.cancel(); recognizer?.destroy() }
        recognizer = null
    }

    /** Speaks a reply. Reports nothing as success — the TTS engine just does its best. */
    fun speak(text: String) {
        if (text.isBlank()) return
        if (!ttsReady) { pendingSpeak = text; return }
        runCatching { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jarvis-${System.nanoTime()}") }
    }

    fun stopSpeaking() {
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
    }
}
