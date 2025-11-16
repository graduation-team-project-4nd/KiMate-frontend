package com.example.kioskassistapp.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class SpeechRecognizerManager(
    private val context: Context,
    private val onResult: (String) -> Unit
) {

    private var speechRecognizer: SpeechRecognizer? = null

    fun startListening() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            }

            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val data = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spokenText = data?.firstOrNull() ?: ""
                    onResult(spokenText)
                }

                override fun onError(error: Int) {
                    Log.e("SpeechRecognizer", "음성 인식 오류: $error")
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            speechRecognizer?.startListening(intent)
        } else {
            Log.e("SpeechRecognizer", "음성 인식 불가")
        }
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
    }
}
