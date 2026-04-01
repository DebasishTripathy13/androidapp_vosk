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

        // ── Test cases: (id, conversation, expected_fields_not_empty) ──────────
        val CASES = listOf(
            TestCase(
                id = "TC1_fever_flu",
                conversation = "मरीज़ को 3 दिन से तेज़ बुखार है, सिरदर्द और बदन दर्द भी है। " +
                    "डॉक्टर ने वायरल फ्लू बताया। Paracetamol 500mg दिन में 3 बार और " +
                    "Cetirizine 10mg रात को दी। 5 दिन बाद ठीक न हो तो आएं। CBC करवाएं।",
                expectDiagnosis = true, expectMeds = true, expectDosage = true,
                expectTests = true, expectFollowup = true
            ),
            TestCase(
                id = "TC2_hypertension",
                conversation = "BP 160/100 है। मरीज़ को सीने में हल्का दर्द है। " +
                    "Hypertension confirm हुआ। Amlodipine 5mg रोज़ सुबह लिखी। " +
                    "ECG और Kidney Function Test करवाएं। 2 हफ्ते बाद रिपोर्ट लेकर आएं।",
                expectDiagnosis = true, expectMeds = true, expectDosage = true,
                expectTests = true, expectFollowup = true
            ),
            TestCase(
                id = "TC3_cough_no_tests",
                conversation = "खांसी है 5 दिन से, बुखार नहीं है। डॉक्टर ने खांसी की दवा " +
                    "और Vitamin C दिए। 7 दिन में ठीक न हो तो वापस आना। कोई जांच नहीं।",
                expectDiagnosis = true, expectMeds = true, expectDosage = true,
                expectTests = false,   // should say उल्लेख नहीं
                expectFollowup = true
            ),
            TestCase(
                id = "TC4_diabetes",
                conversation = "मरीज़ को बहुत प्यास लगती है और बार-बार पेशाब आता है। " +
                    "Blood sugar fasting 210 mg/dl। Diabetes Type 2 confirm। " +
                    "Metformin 500mg सुबह-शाम खाने के बाद दें। HbA1c और kidney function test लें। " +
                    "1 महीने बाद रिपोर्ट के साथ आएं।",
                expectDiagnosis = true, expectMeds = true, expectDosage = true,
                expectTests = true, expectFollowup = true
            ),
            TestCase(
                id = "TC5_no_meds",
                conversation = "मरीज़ की routine checkup है। सब नॉर्मल है। " +
                    "कोई दवा नहीं दी। Lipid profile test करवाएं। 6 महीने बाद आएं।",
                expectDiagnosis = true, expectMeds = false,  // उल्लेख नहीं expected
                expectDosage = false, expectTests = true, expectFollowup = true
            )
        )

        data class TestCase(
            val id: String,
            val conversation: String,
            val expectDiagnosis: Boolean,
            val expectMeds: Boolean,
            val expectDosage: Boolean,
            val expectTests: Boolean,
            val expectFollowup: Boolean
        )

        data class TestResult(
            val id: String,
            val report: MedicalReport?,
            val error: String?,
            val passed: Boolean,
            val failures: List<String>
        )

        val results = mutableListOf<TestResult>()

        @BeforeClass
        @JvmStatic
        fun loadModel() {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            processor = LlmProcessor()
            val result = runBlocking { processor.loadModel(ctx, MODEL_PATH) }
            result.onFailure { throw AssertionError("Model load failed: ${it.message}") }
            Log.i(TAG, "Model loaded OK")
        }

        @AfterClass
        @JvmStatic
        fun printSummary() {
            Log.i(TAG, "═══════════════════════════════════════════")
            Log.i(TAG, "  LLM TEST SUMMARY")
            Log.i(TAG, "═══════════════════════════════════════════")
            var passed = 0; var failed = 0
            for (r in results) {
                if (r.passed) {
                    Log.i(TAG, "  ✓ ${r.id}")
                    Log.i(TAG, "    डॉक्टर  : ${r.report?.diagnosis}")
                    Log.i(TAG, "    दवाई   : ${r.report?.medication}")
                    Log.i(TAG, "    खुराक  : ${r.report?.dosage}")
                    Log.i(TAG, "    जांच   : ${r.report?.otherTests}")
                    Log.i(TAG, "    फॉलोअप: ${r.report?.followUp}")
                    passed++
                } else {
                    Log.e(TAG, "  ✗ ${r.id}  FAILED: ${r.failures.joinToString("; ")}")
                    if (r.error != null) Log.e(TAG, "    ERROR: ${r.error}")
                    else {
                        Log.e(TAG, "    डॉक्टर  : ${r.report?.diagnosis}")
                        Log.e(TAG, "    दवाई   : ${r.report?.medication}")
                        Log.e(TAG, "    खुराक  : ${r.report?.dosage}")
                        Log.e(TAG, "    जांच   : ${r.report?.otherTests}")
                        Log.e(TAG, "    फॉलोअप: ${r.report?.followUp}")
                    }
                    failed++
                }
                Log.i(TAG, "───────────────────────────────────────────")
            }
            Log.i(TAG, "  TOTAL: $passed passed, $failed failed out of ${results.size}")
            Log.i(TAG, "═══════════════════════════════════════════")
            processor.destroy()
        }

        // Phrases that all mean "nothing mentioned" — model sometimes varies wording
        private val NONE_PHRASES = setOf(
            "उल्लेख नहीं", "कोई नहीं", "कोई दवा नहीं", "कोई जांच नहीं",
            "नहीं", "कुछ नहीं", "न/a", "n/a", "none"
        )

        private fun looksLikeNone(value: String) =
            NONE_PHRASES.any { value.trim().lowercase().contains(it.lowercase()) }

        private fun evaluate(tc: TestCase, report: MedicalReport): List<String> {
            val failures = mutableListOf<String>()
            val DEFAULT = "उल्लेख नहीं"

            fun check(field: String, value: String, expectNonDefault: Boolean) {
                if (expectNonDefault && looksLikeNone(value))
                    failures.add("$field should have content but got '$value'")
                if (!expectNonDefault && !looksLikeNone(value))
                    failures.add("$field expected '$DEFAULT' but got '$value'")
            }

            check("डॉक्टर", report.diagnosis, tc.expectDiagnosis)
            check("दवाई", report.medication, tc.expectMeds)
            check("खुराक", report.dosage, tc.expectDosage)
            check("जांच", report.otherTests, tc.expectTests)
            check("फॉलोअप", report.followUp, tc.expectFollowup)

            // Dosage format sanity: if present, should contain a digit pattern like X-X-X
            if (tc.expectDosage && report.dosage != DEFAULT) {
                if (!report.dosage.contains(Regex("""\d-\d""")))
                    failures.add("खुराक format issue — expected X-X-X pattern, got '${report.dosage}'")
            }

            return failures
        }
    }

    private fun runCase(tc: TestCase) {
        Log.i(TAG, "Running ${tc.id}…")
        try {
            val report = runBlocking { processor.processTranscription(tc.conversation).getOrThrow() }
            val failures = evaluate(tc, report)
            results.add(TestResult(tc.id, report, null, failures.isEmpty(), failures))
            if (failures.isNotEmpty()) fail("${tc.id} failures: ${failures.joinToString("; ")}")
        } catch (e: AssertionError) {
            throw e
        } catch (e: Exception) {
            val msg = "Exception: ${e.message}"
            results.add(TestResult(tc.id, null, msg, false, listOf(msg)))
            fail(msg)
        }
    }

    @Test fun test1_fever_flu()     = runCase(CASES[0])
    @Test fun test2_hypertension()  = runCase(CASES[1])
    @Test fun test3_cough_no_tests()= runCase(CASES[2])
    @Test fun test4_diabetes()      = runCase(CASES[3])
    @Test fun test5_no_meds()       = runCase(CASES[4])
}
