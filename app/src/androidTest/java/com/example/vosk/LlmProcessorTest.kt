package com.example.vosk

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class LlmProcessorTest {

    companion object {
        private const val TAG = "LlmTest"
        private const val MODEL_PATH =
            "/storage/emulated/0/Android/data/com.example.vosk/files/gemma-3n-E2B-it-int4.litertlm"

        private lateinit var processor: LlmProcessor

        data class TestCase(
            val id: String,
            val conversation: String,
            val expectDiagnosis: Boolean = true,
            val expectMeds: Boolean = true,      // false = no medicine given
            val expectTests: Boolean = true,     // false = no tests ordered
            val expectFollowup: Boolean = true
        )

        data class TestResult(
            val id: String,
            val report: MedicalReport?,
            val error: String?,
            val passed: Boolean,
            val failures: List<String>
        )

        val results = mutableListOf<TestResult>()

        val CASES = listOf(
            TestCase(
                id = "TC1_viral_fever",
                conversation = """
                    डॉक्टर: नमस्ते, क्या तकलीफ़ है?
                    मरीज़: 3 दिन से तेज़ बुखार है, सिरदर्द और बदन दर्द भी है।
                    डॉक्टर: बुखार कितना है?
                    मरीज़: कल रात 102 था।
                    डॉक्टर: यह वायरल फ्लू है। Paracetamol 500mg दिन में तीन बार लें —
                    सुबह, दोपहर, रात। और Cetirizine 10mg रात को। CBC blood test करवाएं।
                    5 दिन बाद भी ठीक न हो तो आ जाना।
                """.trimIndent(),
                expectTests = true
            ),
            TestCase(
                id = "TC2_hypertension",
                conversation = """
                    डॉक्टर: BP कितना है आज?
                    मरीज़: 160 ऊपर 100 नीचे। सीने में हल्का दर्द भी है।
                    डॉक्टर: यह hypertension है। Amlodipine 5mg रोज़ सुबह लेनी है।
                    ECG और Kidney Function Test करवाएं। 2 हफ्ते बाद रिपोर्ट लेकर आएं।
                """.trimIndent(),
                expectTests = true
            ),
            TestCase(
                id = "TC3_cough_no_tests",
                conversation = """
                    डॉक्टर: क्या परेशानी है?
                    मरीज़: 5 दिन से खांसी है, बुखार नहीं है।
                    डॉक्टर: ठीक है। खांसी की दवा सुबह-दोपहर-रात लें और Vitamin C सुबह लें।
                    कोई जांच नहीं करवानी। 7 दिन में ठीक न हो तो वापस आना।
                """.trimIndent(),
                expectTests = false
            ),
            TestCase(
                id = "TC4_diabetes",
                conversation = """
                    डॉक्टर: कौन सी तकलीफ़ है?
                    मरीज़: बहुत प्यास लगती है और बार-बार पेशाब आता है।
                    डॉक्टर: Fasting blood sugar 210 है। Diabetes Type 2 confirm हुआ है।
                    Metformin 500mg सुबह-शाम खाने के बाद लें।
                    HbA1c और Kidney Function Test करवाएं। 1 महीने बाद रिपोर्ट लेकर आएं।
                """.trimIndent(),
                expectTests = true
            ),
            TestCase(
                id = "TC5_routine_no_meds",
                conversation = """
                    डॉक्टर: आज रूटीन चेकअप के लिए आए हैं?
                    मरीज़: हाँ, कोई तकलीफ़ नहीं है, बस checkup करवाना था।
                    डॉक्टर: सब normal है। कोई दवा नहीं दे रहा।
                    Lipid profile test करवा लें। 6 महीने बाद आना।
                """.trimIndent(),
                expectMeds = false,
                expectTests = true
            )
        )

        private val NONE_PHRASES = setOf(
            "उल्लेख नहीं", "कोई नहीं", "कोई दवा नहीं", "कोई जांच नहीं", "नहीं", "कुछ नहीं"
        )

        private fun looksLikeNone(value: String): Boolean {
            val v = value.trim().lowercase()
            return NONE_PHRASES.any { phrase ->
                v == phrase.lowercase() ||
                v.split(",").map { it.trim() }.all { it == phrase.lowercase() }
            }
        }

        private fun evaluate(tc: TestCase, report: MedicalReport): List<String> {
            val failures = mutableListOf<String>()

            fun check(field: String, value: String, expectContent: Boolean) {
                if (expectContent && looksLikeNone(value))
                    failures.add("$field: should have content but got '$value'")
                if (!expectContent && !looksLikeNone(value))
                    failures.add("$field: expected none but got '$value'")
            }

            check("डॉक्टर", report.diagnosis, tc.expectDiagnosis)
            check("दवाई",   report.medication, tc.expectMeds)
            check("जांच",   report.otherTests,  tc.expectTests)
            check("फॉलोअप", report.followUp,   tc.expectFollowup)

            // If medicine present, dosage X-X-X should be inline
            if (tc.expectMeds && !looksLikeNone(report.medication)) {
                if (!report.medication.contains(Regex("""\d-\d""")))
                    failures.add("दवाई: dosage X-X-X missing inline, got '${report.medication}'")
            }

            return failures
        }

        @BeforeClass @JvmStatic
        fun loadModel() {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            processor = LlmProcessor()
            runBlocking { processor.loadModel(ctx, MODEL_PATH) }
                .onFailure { throw AssertionError("Model load failed: ${it.message}") }
            Log.i(TAG, "Model loaded OK")
        }

        @AfterClass @JvmStatic
        fun printSummary() {
            Log.i(TAG, "═══════════════════════════════════════════")
            Log.i(TAG, "  LLM TEST SUMMARY  (${results.size} cases)")
            Log.i(TAG, "═══════════════════════════════════════════")
            var passed = 0; var failed = 0
            for (r in results) {
                val mark = if (r.passed) "✓" else "✗"
                Log.i(TAG, "  $mark ${r.id}")
                if (r.error != null) {
                    Log.e(TAG, "    ERROR: ${r.error}")
                } else {
                    Log.i(TAG, "    डॉक्टर  : ${r.report?.diagnosis}")
                    Log.i(TAG, "    दवाई   : ${r.report?.medication}")
                    Log.i(TAG, "    जांच   : ${r.report?.otherTests}")
                    Log.i(TAG, "    फॉलोअप: ${r.report?.followUp}")
                    if (!r.passed) Log.e(TAG, "    FAIL: ${r.failures.joinToString("; ")}")
                }
                Log.i(TAG, "───────────────────────────────────────────")
                if (r.passed) passed++ else failed++
            }
            Log.i(TAG, "  TOTAL: $passed passed, $failed failed out of ${results.size}")
            Log.i(TAG, "═══════════════════════════════════════════")
            processor.destroy()
        }
    }

    private fun runCase(tc: TestCase) {
        Log.i(TAG, "Running ${tc.id}…")
        try {
            val report = runBlocking { processor.processTranscription(tc.conversation).getOrThrow() }
            val failures = evaluate(tc, report)
            results.add(TestResult(tc.id, report, null, failures.isEmpty(), failures))
            if (failures.isNotEmpty()) fail("${tc.id}: ${failures.joinToString("; ")}")
        } catch (e: AssertionError) { throw e
        } catch (e: Exception) {
            val msg = "Exception: ${e.message}"
            results.add(TestResult(tc.id, null, msg, false, listOf(msg)))
            fail(msg)
        }
    }

    @Test fun test1_viral_fever()      = runCase(CASES[0])
    @Test fun test2_hypertension()     = runCase(CASES[1])
    @Test fun test3_cough_no_tests()   = runCase(CASES[2])
    @Test fun test4_diabetes()         = runCase(CASES[3])
    @Test fun test5_routine_no_meds()  = runCase(CASES[4])
}
