package com.example.indicvoicebridge.speech

import android.content.Context
import android.content.Intent
import android.media.ToneGenerator
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import com.example.indicvoicebridge.model.Language
import com.example.indicvoicebridge.model.SUPPORTED_LANGUAGES
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.*
import java.util.Locale

class SpeechAndTranslationManager(private val context: Context) {

    companion object {
        private const val TAG = "SpeechMgr"

        // Offline emergency quick phrases for tactical comms
        val QUICK_PHRASES = listOf(
            "Emergency! Need help!",
            "I am injured",
            "Send medical assistance",
            "Safe location confirmed",
            "Moving to rally point",
            "Danger ahead",
            "All clear",
            "Wait for my signal"
        )
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    var onSpeechResult: ((String) -> Unit)? = null
    var onSpeechError: ((String) -> Unit)? = null
    var onRmsChanged: ((Float) -> Unit)? = null
    var onStatusUpdate: ((String) -> Unit)? = null

    init {
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(context) { status ->
            isTtsReady = (status == TextToSpeech.SUCCESS)
        }
    }

    fun startListening(language: Language) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onSpeechError?.invoke("Speech recognition not available. Install Google Speech Services.")
            return
        }
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { onStatusUpdate?.invoke("Listening in ${language.displayName}…") }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) { onRmsChanged?.invoke(rmsdB) }
            override fun onBufferReceived(b: ByteArray?) = Unit
            override fun onEndOfSpeech() { onStatusUpdate?.invoke("Processing speech…") }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: ""
                if (text.isNotEmpty()) onSpeechResult?.invoke(text)
                else onSpeechError?.invoke("No speech recognized.")
            }
            override fun onPartialResults(partial: Bundle?) = Unit
            override fun onEvent(type: Int, params: Bundle?) = Unit
            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timed out"
                    SpeechRecognizer.ERROR_NETWORK -> "Network error – using offline mode"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                    SpeechRecognizer.ERROR_NOT_SUPPORTED -> "Speech service not supported"
                    else -> "STT Error: $error"
                }
                onSpeechError?.invoke(msg)
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.locale.toString())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language.locale.toString())
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) // Feature 6: Force offline STT
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer?.startListening(intent)
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        onRmsChanged?.invoke(0f)
    }

    fun translateText(text: String, sourceLanguage: Language, targetLanguage: Language,
                      onComplete: (String) -> Unit, onError: (String) -> Unit) {
        if (sourceLanguage.mlKitCode == targetLanguage.mlKitCode) {
            onComplete(text); return
        }
        onStatusUpdate?.invoke("Translating to ${targetLanguage.displayName}…")
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLanguage.mlKitCode)
            .setTargetLanguage(targetLanguage.mlKitCode)
            .build()
        val translator = Translation.getClient(options)
        val conditions = DownloadConditions.Builder().requireWifi().build()
        translator.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                translator.translate(text)
                    .addOnSuccessListener { translated ->
                        onComplete(translated)
                        translator.close()
                    }
                    .addOnFailureListener { e ->
                        onError("Translation failed: ${e.message}")
                        translator.close()
                    }
            }
            .addOnFailureListener { e ->
                // Try without wifi restriction (use cached model)
                translator.translate(text)
                    .addOnSuccessListener { translated -> onComplete(translated); translator.close() }
                    .addOnFailureListener { _ -> onError("No translation model. Connect to Wi-Fi once to download."); translator.close() }
            }
    }

    fun speakText(text: String, locale: Locale) {
        if (!isTtsReady) { initTts(); return }
        tts?.let { t ->
            if (t.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) {
                t.setLanguage(Locale.ENGLISH)
            }
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "BRIDGE_TTS")
        }
    }

    fun playAlertTone() {
        try {
            val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME)
            toneGen.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 2000)
        } catch (e: Exception) { Log.e(TAG, "Tone error: ${e.message}") }
    }

    fun destroy() {
        scope.cancel()
        speechRecognizer?.destroy()
        tts?.shutdown()
    }
}

