package com.example.vosk

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.SpeechStreamService
import org.vosk.android.StorageService
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

class VoskTranscriptionService {

    companion object {
        private const val TAG = "VoskService"
        private const val MODEL_NAME = "model-hi"
        private const val SAMPLE_RATE = 16000.0f
        private const val STREAM_BUFFER_BYTES = 8192  // 8 KB per chunk → ~256 ms at 16kHz 16-bit mono
    }

    interface Listener {
        fun onModelReady()
        fun onModelError(error: String)
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onError(error: String)
    }

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var speechStreamService: SpeechStreamService? = null

    var isModelReady = false
        private set

    // ── Model init ────────────────────────────────────────────────────────────

    fun initModel(context: Context, listener: Listener) {
        LibVosk.setLogLevel(LogLevel.WARNINGS)  // reduce logcat noise

        StorageService.unpack(context, MODEL_NAME, "model",
            { mdl ->
                model = mdl
                isModelReady = true
                Log.d(TAG, "Vosk Hindi model loaded successfully")
                listener.onModelReady()
            },
            { exception ->
                Log.e(TAG, "Failed to load Vosk model", exception)
                listener.onModelError("मॉडल लोड करने में विफल: ${exception.message}")
            }
        )
    }

    // ── File transcription ────────────────────────────────────────────────────

    fun transcribeFile(file: File, listener: Listener) {
        stopStreamTranscription()   // cancel any previous file job

        val currentModel = model ?: run {
            listener.onError("मॉडल लोड नहीं है")
            return
        }

        val inputStream: FileInputStream
        try {
            inputStream = FileInputStream(file)
        } catch (e: IOException) {
            listener.onError("फ़ाइल खोलने में विफल: ${e.message}")
            return
        }

        // Parse WAV header properly — finds the "data" chunk regardless of header size
        if (!skipWavHeader(inputStream)) {
            try { inputStream.close() } catch (_: Exception) {}
            listener.onError("Invalid WAV file — cannot find audio data")
            return
        }

        val rec = try {
            Recognizer(currentModel, SAMPLE_RATE).apply {
                setMaxAlternatives(0)   // only best hypothesis, skip alternatives
                setWords(false)         // no word-level timestamps needed
            }
        } catch (e: Exception) {
            try { inputStream.close() } catch (_: Exception) {}
            listener.onError("Recognizer init failed: ${e.message}")
            return
        }

        var lastPartial = ""

        val recognitionListener = object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) {
                val text = extractText(hypothesis, "partial")
                if (text.isNotEmpty() && text != lastPartial) {
                    lastPartial = text
                    listener.onPartialResult(text)
                }
            }

            // onResult fires after each silence-delimited sentence during file streaming
            override fun onResult(hypothesis: String?) {
                val text = extractText(hypothesis, "text")
                if (text.isNotEmpty()) {
                    lastPartial = ""
                    listener.onFinalResult(text)
                }
            }

            // onFinalResult fires once at the very end of the stream
            override fun onFinalResult(hypothesis: String?) {
                val text = extractText(hypothesis, "text")
                // Only emit if it's different from the last onResult to avoid duplicates
                if (text.isNotEmpty() && text != lastPartial) {
                    listener.onFinalResult(text)
                }
                cleanup()
                speechStreamService = null
            }

            override fun onError(exception: Exception?) {
                Log.e(TAG, "Stream transcription error", exception)
                cleanup()
                speechStreamService = null
                listener.onError(exception?.message ?: "Transcription error")
            }

            override fun onTimeout() {
                cleanup()
                speechStreamService = null
            }

            private fun cleanup() {
                try { rec.close() } catch (_: Exception) {}
                try { inputStream.close() } catch (_: Exception) {}
            }
        }

        try {
            speechStreamService = SpeechStreamService(rec, inputStream, SAMPLE_RATE)
            speechStreamService!!.start(recognitionListener)
        } catch (e: Exception) {
            try { rec.close() } catch (_: Exception) {}
            try { inputStream.close() } catch (_: Exception) {}
            listener.onError("Transcription start failed: ${e.message}")
        }
    }

    fun stopStreamTranscription() {
        speechStreamService?.stop()
        speechStreamService = null
    }

    // ── Live mic recognition ──────────────────────────────────────────────────

    fun startLiveRecognition(listener: Listener) {
        stopLiveRecognition()

        val currentModel = model ?: run {
            listener.onError("मॉडल लोड नहीं है")
            return
        }

        val rec = try {
            Recognizer(currentModel, SAMPLE_RATE).apply {
                setMaxAlternatives(0)
                setWords(false)
            }
        } catch (e: Exception) {
            listener.onError("Recognizer init failed: ${e.message}")
            return
        }

        var lastPartial = ""

        val recognitionListener = object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) {
                val text = extractText(hypothesis, "partial")
                if (text.isNotEmpty() && text != lastPartial) {
                    lastPartial = text
                    listener.onPartialResult(text)
                }
            }

            override fun onResult(hypothesis: String?) {
                val text = extractText(hypothesis, "text")
                if (text.isNotEmpty()) {
                    lastPartial = ""
                    listener.onFinalResult(text)
                }
            }

            override fun onFinalResult(hypothesis: String?) {
                val text = extractText(hypothesis, "text")
                if (text.isNotEmpty() && text != lastPartial) {
                    listener.onFinalResult(text)
                }
            }

            override fun onError(exception: Exception?) {
                Log.e(TAG, "Live recognition error", exception)
                listener.onError(exception?.message ?: "Recognition error")
            }

            override fun onTimeout() {}
        }

        try {
            speechService = SpeechService(rec, SAMPLE_RATE)
            speechService!!.startListening(recognitionListener)
        } catch (e: IOException) {
            try { rec.close() } catch (_: Exception) {}
            Log.e(TAG, "Error starting live recognition", e)
            listener.onError("माइक्रोफ़ोन शुरू करने में विफल: ${e.message}")
        }
    }

    fun stopLiveRecognition() {
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    fun destroy() {
        stopLiveRecognition()
        stopStreamTranscription()
        model?.close()
        model = null
        isModelReady = false
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Parses the WAV RIFF header to find and skip past the "data" chunk header.
     * Works with any WAV header size — not just the standard 44 bytes.
     * Leaves the stream positioned at the first audio sample byte.
     */
    private fun skipWavHeader(stream: InputStream): Boolean {
        val dis = DataInputStream(stream)
        return try {
            val riff = ByteArray(4).also { dis.readFully(it) }
            if (String(riff) != "RIFF") return false
            dis.skipBytes(4)    // file size (not needed)
            val wave = ByteArray(4).also { dis.readFully(it) }
            if (String(wave) != "WAVE") return false

            // Walk chunks until we hit "data"
            while (true) {
                val chunkId = ByteArray(4).also { dis.readFully(it) }
                // WAV uses little-endian chunk sizes
                val b0 = dis.read(); val b1 = dis.read()
                val b2 = dis.read(); val b3 = dis.read()
                val chunkSize = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
                if (String(chunkId) == "data") return true   // stream now at audio data
                dis.skipBytes(chunkSize)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse WAV header", e)
            false
        }
    }

    /** Extract text/partial from Vosk JSON: {"text":"..."} or {"partial":"..."} */
    private fun extractText(jsonStr: String?, key: String): String {
        if (jsonStr.isNullOrEmpty()) return ""
        return try {
            JSONObject(jsonStr).optString(key, "").trim()
        } catch (_: Exception) {
            jsonStr.trim()
        }
    }
}
