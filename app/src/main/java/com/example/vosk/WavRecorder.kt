package com.example.vosk

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Records audio as a WAV file (16kHz, 16-bit, mono) suitable for Vosk processing.
 */
class WavRecorder(private val cacheDir: File) {

    companion object {
        private const val TAG = "WavRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var outputFile: File? = null

    val isCurrentlyRecording: Boolean get() = isRecording

    /**
     * Starts recording audio in the background. Call from a coroutine.
     * Returns the output File once recording is stopped.
     */
    suspend fun startRecording(): File = withContext(Dispatchers.IO) {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val file = File(cacheDir, "recording_${System.currentTimeMillis()}.wav")
        outputFile = file

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord failed to initialize. Check microphone permission.")
        }
        audioRecord = record

        val fos = FileOutputStream(file)

        // Write placeholder WAV header (44 bytes) — we'll fill in the sizes later
        val header = ByteArray(44)
        fos.write(header)

        audioRecord?.startRecording()
        isRecording = true
        Log.d(TAG, "Recording started: ${file.absolutePath}")

        val buffer = ByteArray(bufferSize)
        var totalBytesWritten = 0L

        try {
            while (isRecording && isActive) {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (bytesRead > 0) {
                    fos.write(buffer, 0, bytesRead)
                    totalBytesWritten += bytesRead
                }
            }
        } finally {
            fos.close()
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            // Write the actual WAV header
            writeWavHeader(file, totalBytesWritten)
            Log.d(TAG, "Recording saved: ${file.absolutePath} ($totalBytesWritten bytes)")
        }

        file
    }

    /**
     * Stops an ongoing recording.
     */
    fun stopRecording() {
        isRecording = false
    }

    /**
     * Writes a proper WAV header to the beginning of the file.
     */
    private fun writeWavHeader(file: File, dataSize: Long) {
        val raf = RandomAccessFile(file, "rw")
        val totalSize = dataSize + 36 // 44 - 8 = 36

        raf.seek(0)
        // RIFF header
        raf.writeBytes("RIFF")
        raf.writeInt(java.lang.Integer.reverseBytes(totalSize.toInt()))
        raf.writeBytes("WAVE")

        // fmt sub-chunk
        raf.writeBytes("fmt ")
        raf.writeInt(java.lang.Integer.reverseBytes(16))           // Sub-chunk size (16 for PCM)
        raf.writeShort(java.lang.Short.reverseBytes(1).toInt())    // Audio format (1 = PCM)
        raf.writeShort(java.lang.Short.reverseBytes(1).toInt())    // Num channels (1 = mono)
        raf.writeInt(java.lang.Integer.reverseBytes(SAMPLE_RATE))  // Sample rate
        raf.writeInt(java.lang.Integer.reverseBytes(SAMPLE_RATE * 2)) // Byte rate (SampleRate * NumChannels * BitsPerSample/8)
        raf.writeShort(java.lang.Short.reverseBytes(2).toInt())    // Block align (NumChannels * BitsPerSample/8)
        raf.writeShort(java.lang.Short.reverseBytes(16).toInt())   // Bits per sample

        // data sub-chunk
        raf.writeBytes("data")
        raf.writeInt(java.lang.Integer.reverseBytes(dataSize.toInt()))

        raf.close()
    }
}
