package com.example.vosk

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Converts audio from a URI (MP3, AAC, M4A, etc.) to 16kHz 16-bit Mono WAV.
 * Uses Android's native MediaExtractor and MediaCodec.
 */
class AudioConverter(private val context: Context) {

    companion object {
        private const val TAG = "AudioConverter"
        private const val TARGET_SAMPLE_RATE = 16000
        private const val TIMEOUT_US = 10000L
    }

    suspend fun convertToWav(inputUri: Uri, outputFile: File): Result<File> = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var outputStream: FileOutputStream? = null

        try {
            extractor.setDataSource(context, inputUri, null)
            
            // value 0 is typically the audio track if it's an audio file. 
            // Better to search for "audio/".
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }

            if (trackIndex < 0 || format == null) {
                return@withContext Result.failure(Exception("No audio track found in file"))
            }

            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/raw"
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val sourceSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
            val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            
            Log.d(TAG, "Source: $mime, ${sourceSampleRate}Hz, $channelCount channels")

            outputStream = FileOutputStream(outputFile)
            // Write WAV header placeholder (44 bytes)
            writeWavHeader(outputStream, 0, TARGET_SAMPLE_RATE, 1)

            val bufferInfo = MediaCodec.BufferInfo()
            var inputEos = false
            var outputEos = false
            var totalPcmBytes = 0

            // Buffers for resampling
            // We'll read chunks of PCM and resample them
            
            while (!outputEos) {
                if (!inputEos) {
                    val inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                        val sampleSize = extractor.readSampleData(inputBuffer!!, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outputBufferIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                    if (bufferInfo.size > 0 && outputBuffer != null) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        
                        val pcmData = ByteArray(bufferInfo.size)
                        outputBuffer.get(pcmData)
                        
                        // Process PCM: Mix to Mono -> Resample
                        val processedData = processPcm(pcmData, sourceSampleRate, channelCount)
                        outputStream.write(processedData)
                        totalPcmBytes += processedData.size
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputEos = true
                    }
                }
            }
            
            // Go back and update WAV header with actual size
            outputStream.flush()
            outputStream.channel.position(0)
            writeWavHeader(outputStream, totalPcmBytes, TARGET_SAMPLE_RATE, 1)
            
            Log.d(TAG, "Conversion complete. Wrote $totalPcmBytes bytes to ${outputFile.name}")
            Result.success(outputFile)

        } catch (e: Exception) {
            Log.e(TAG, "Conversion failed", e)
            Result.failure(e)
        } finally {
            try {
                codec?.stop()
                codec?.release()
                extractor.release()
                outputStream?.close()
            } catch (e: Exception) {
                // Ignore cleanup errors
            }
        }
    }

    /**
     * Resample and Mono-mix raw PCM data (16-bit).
     * Simple linear interpolation for resampling.
     * Averaging for stereo->mono.
     */
    private fun processPcm(data: ByteArray, sourceRate: Int, channels: Int): ByteArray {
        val shorts = ShortArray(data.size / 2)
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)

        // 1. Stereo to Mono
        val monoShorts = if (channels == 1) {
            shorts
        } else {
            val count = shorts.size / channels
            val mono = ShortArray(count)
            for (i in 0 until count) {
                var sum = 0
                for (ch in 0 until channels) {
                    sum += shorts[i * channels + ch]
                }
                mono[i] = (sum / channels).toShort()
            }
            mono
        }

        // 2. Resample to 16kHz
        if (sourceRate == TARGET_SAMPLE_RATE) {
             val bytes = ByteArray(monoShorts.size * 2)
             ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(monoShorts)
             return bytes
        }

        // Simple Linear Interpolation
        val ratio = sourceRate.toDouble() / TARGET_SAMPLE_RATE
        val newLength = (monoShorts.size / ratio).toInt()
        val resampled = ShortArray(newLength)

        for (i in 0 until newLength) {
            val srcIndex = i * ratio
            val index0 = srcIndex.toInt()
            val index1 = (index0 + 1).coerceAtMost(monoShorts.size - 1)
            val frac = srcIndex - index0
            
            val val0 = monoShorts[index0]
            val val1 = monoShorts[index1]
            val interpolated = (val0 + frac * (val1 - val0)).toInt().toShort()
            resampled[i] = interpolated
        }

        val bytes = ByteArray(resampled.size * 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(resampled)
        return bytes
    }

    private fun writeWavHeader(out: FileOutputStream, totalAudioLen: Int, sampleRate: Int, channels: Int) {
        val totalDataLen = totalAudioLen + 36
        val longSampleRate = sampleRate.toLong()
        val byteRate = (sampleRate * channels * 16 / 8).toLong()
        val header = ByteArray(44)

        header[0] = 'R'.code.toByte() // RIFF/WAVE header
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = (totalDataLen shr 8 and 0xff).toByte()
        header[6] = (totalDataLen shr 16 and 0xff).toByte()
        header[7] = (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte() // 'fmt ' chunk
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16 // 4 bytes: size of 'fmt ' chunk
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // format = 1 (PCM)
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (longSampleRate and 0xff).toByte()
        header[25] = (longSampleRate shr 8 and 0xff).toByte()
        header[26] = (longSampleRate shr 16 and 0xff).toByte()
        header[27] = (longSampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = (channels * 16 / 8).toByte() // block align
        header[33] = 0
        header[34] = 16 // bits per sample
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = (totalAudioLen shr 8 and 0xff).toByte()
        header[42] = (totalAudioLen shr 16 and 0xff).toByte()
        header[43] = (totalAudioLen shr 24 and 0xff).toByte()

        out.write(header)
    }
}
