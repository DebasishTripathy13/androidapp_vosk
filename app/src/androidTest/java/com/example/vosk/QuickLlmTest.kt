package com.example.vosk

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Quick one-off LLM test — pass any conversation via adb -e argument.
 *
 * Usage:
 *   adb shell am instrument -w -r \
 *     -e conversation "मरीज़ को बुखार है..." \
 *     -e class com.example.vosk.QuickLlmTest \
 *     com.example.vosk.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class QuickLlmTest {

    companion object {
        private const val MODEL_PATH =
            "/storage/emulated/0/Android/data/com.example.vosk/files/gemma-3n-E2B-it-int4.litertlm"
        private const val TAG = "QuickLlmTest"
        private const val SEPARATOR = "══════════════════════════════════════════════"
    }

    @Test
    fun run() {
        val args   = InstrumentationRegistry.getArguments()
        val ctx    = InstrumentationRegistry.getInstrumentation().targetContext
        val input  = args.getString("conversation")
            ?: error("Pass -e conversation \"<text>\" to adb instrument")

        println(SEPARATOR)
        println("INPUT:")
        println(input)
        println(SEPARATOR)

        val processor = LlmProcessor()
        runBlocking { processor.loadModel(ctx, MODEL_PATH) }
            .onFailure { error("Model load failed: ${it.message}") }

        val report = runBlocking { processor.processTranscription(input).getOrThrow() }
        processor.destroy()

        val output = buildString {
            appendLine(SEPARATOR)
            appendLine("OUTPUT:")
            appendLine("  डॉक्टर  : ${report.diagnosis}")
            appendLine("  दवाई   : ${report.medication}")
            appendLine("  जांच   : ${report.otherTests}")
            appendLine("  फॉलोअप: ${report.followUp}")
            appendLine(SEPARATOR)
        }

        println(output)
        Log.i(TAG, output)
    }
}
