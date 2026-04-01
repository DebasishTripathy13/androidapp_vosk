package com.example.vosk

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LlmProcessor {

    companion object {
        private const val TAG = "LlmProcessor"
        private const val DEFAULT_VALUE = "उल्लेख नहीं"

        private const val MAX_INPUT_CHARS = 6000
        private const val TRUNCATION_HEAD = 2500
        private const val TRUNCATION_TAIL = 3200

        private val LABELS = listOf("डॉक्टर", "दवाई", "खुराक", "जांच", "फॉलोअप")

        private const val SYSTEM_INSTRUCTION = """आप एक अनुभवी मेडिकल स्क्राइब हैं। डॉक्टर और मरीज़ की बातचीत पढ़कर हिंदी में नीचे दी गई ठीक 5 लाइनें इसी क्रम में लिखें:
डॉक्टर: [बीमारी और लक्षण — डॉक्टर की भाषा में]
दवाई: [दवाओं के नाम]
खुराक: [X-X-X (सुबह-दोपहर-रात) के रूप में]
जांच: [CBC, X-ray, ECG जैसी जांचें — दवाएं नहीं]
फॉलोअप: [कब और किस स्थिति में वापस आना है]

नियम:
- हमेशा इसी क्रम में लिखें: डॉक्टर → दवाई → खुराक → जांच → फॉलोअप
- खुराक हमेशा X-X-X रूप में लिखें (जैसे 1-0-1 या 1-1-1) और समय (सुबह-दोपहर-रात) ज़रूर लिखें
- दवाई में सिर्फ़ नाम लिखें, खुराक अलग लाइन पर लिखें
- जांच में सिर्फ़ जांचें लिखें, दवाएं नहीं
- जो जानकारी न हो या कोई दवा / जांच न दी गई हो: सिर्फ़ "उल्लेख नहीं" लिखें — "कोई नहीं", "कोई दवा नहीं" आदि मत लिखें
- कोई अतिरिक्त टेक्स्ट नहीं

उदाहरण 1:
Conversation: मरीज़ को 3 दिन से तेज़ बुखार, सिरदर्द और बदन दर्द है। डॉक्टर ने वायरल फ्लू बताया। Paracetamol 500mg दिन में 3 बार और Cetirizine 10mg रात को दी। 5 दिन बाद ठीक न हो तो आएं। CBC करवाएं।
डॉक्टर: वायरल फ्लू, तेज़ बुखार, सिरदर्द, बदन दर्द
दवाई: Paracetamol 500mg, Cetirizine 10mg
खुराक: 1-1-1 (सुबह-दोपहर-रात), 0-0-1 (रात)
जांच: CBC
फॉलोअप: 5 दिन बाद यदि सुधार न हो

उदाहरण 2:
Conversation: खांसी है 5 दिन से। डॉक्टर ने खांसी की दवा और Vitamin C दिए। 7 दिन में ठीक न हो तो आएं। कोई जांच नहीं।
डॉक्टर: खांसी, 5 दिन से
दवाई: खांसी की दवा, Vitamin C
खुराक: 1-1-1 (सुबह-दोपहर-रात)
जांच: उल्लेख नहीं
फॉलोअप: 7 दिन बाद यदि ठीक न हो

उदाहरण 3:
Conversation: BP 160/100 है, सीने में हल्का दर्द। Hypertension confirm। Amlodipine 5mg रोज़ सुबह लिखी। ECG और Kidney Function Test करवाएं। 2 हफ्ते बाद रिपोर्ट लेकर आएं।
डॉक्टर: उच्च रक्तचाप, सीने में दर्द, BP 160/100
दवाई: Amlodipine 5mg
खुराक: 1-0-0 (सुबह)
जांच: ECG, Kidney Function Test
फॉलोअप: 2 हफ्ते बाद रिपोर्ट के साथ"""

        // 0.4 — enough precision for structured output, enough freedom to capture all fields
        private val SAMPLER = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.4)

        // Prime with "डॉक्टर:" so the model starts filling the first field immediately.
        private fun extractionPrompt(text: String) = "$text\nडॉक्टर:"

        // Regex: match each full-word label, capture value up to next label or end.
        // ' *' (not '\s*') so we don't consume the newline separating fields.
        private val FIELD_PATTERN by lazy {
            val alt = LABELS.joinToString("|") { Regex.escape(it) }
            Regex("""(${alt}): *(.*?)(?=\n(?:${alt}):|$)""", setOf(RegexOption.DOT_MATCHES_ALL))
        }

        private fun parseResponse(raw: String): MedicalReport {
            // Prepend "डॉक्टर:" since we primed the model with it
            val full = "डॉक्टर:$raw"
            Log.d(TAG, "Raw LLM response:\n$full")

            val map = FIELD_PATTERN.findAll(full).associate { m ->
                m.groupValues[1] to cleanField(m.groupValues[1], m.groupValues[2])
            }

            Log.d(TAG, "Parsed: $map")
            return MedicalReport(
                diagnosis  = map["डॉक्टर"]?.ifEmpty { DEFAULT_VALUE } ?: DEFAULT_VALUE,
                medication = map["दवाई"]?.ifEmpty { DEFAULT_VALUE } ?: DEFAULT_VALUE,
                dosage     = map["खुराक"]?.ifEmpty { DEFAULT_VALUE } ?: DEFAULT_VALUE,
                otherTests = map["जांच"]?.ifEmpty { DEFAULT_VALUE } ?: DEFAULT_VALUE,
                followUp   = map["फॉलोअप"]?.ifEmpty { DEFAULT_VALUE } ?: DEFAULT_VALUE
            )
        }

        private fun cleanField(label: String, raw: String): String =
            raw.trim()
                .removePrefix("$label:").trim()      // strip echoed label if model repeated it
                .replace(Regex("\\*{1,2}([^*]+)\\*{1,2}"), "$1")  // bold/italic
                .replace(Regex("#{1,6}\\s*"), "")                  // headers
                .replace(Regex("(?m)^\\s*[-•–]\\s*"), "")          // bullet points
                .replace(Regex("\\s{2,}"), " ")                    // extra whitespace
                .trim()
    }

    private var engine: Engine? = null
    private val inferenceLock = Mutex()   // LiteRT-LM: only 1 conversation at a time

    @Volatile
    var isModelLoaded = false
        private set

    suspend fun loadModel(context: Context, modelPath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val file = java.io.File(modelPath)
                if (!file.exists()) throw Exception("Model file not found: $modelPath")
                Log.d(TAG, "Loading model: ${"%.1f".format(file.length() / 1_048_576.0)} MB")
                destroy()
                val newEngine = Engine(
                    EngineConfig(modelPath = modelPath, backend = Backend.CPU(), cacheDir = context.cacheDir.path)
                )
                newEngine.initialize()
                engine = newEngine
                isModelLoaded = true
                Log.d(TAG, "LLM model loaded successfully")
                Result.success(Unit)
            } catch (e: Throwable) {
                Log.e(TAG, "Model load failed", e)
                isModelLoaded = false; engine = null
                Result.failure(Exception(e))
            }
        }

    private fun truncateInput(text: String): Pair<String, Boolean> {
        if (text.length <= MAX_INPUT_CHARS) return text to false
        val head = text.take(TRUNCATION_HEAD)
        val tail = text.takeLast(TRUNCATION_TAIL)
        Log.w(TAG, "Input truncated: ${text.length} → ${head.length + tail.length} chars")
        return "$head\n[...]\n$tail" to true
    }

    fun wouldTruncate(text: String) = text.length > MAX_INPUT_CHARS

    suspend fun processTranscription(
        text: String,
        onFieldDone: suspend (field: String, value: String) -> Unit = { _, _ -> }
    ): Result<MedicalReport> = withContext(Dispatchers.IO) {
        val eng = engine ?: return@withContext Result.failure(Exception("LLM model not loaded"))

        inferenceLock.withLock {
            try {
                val (input, wasTruncated) = truncateInput(text)
                Log.d(TAG, "Transcript (${input.length} chars):\n$input")
                if (wasTruncated) Log.w(TAG, "Input truncated to fit context window")

                val conv = eng.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(SYSTEM_INSTRUCTION),
                        samplerConfig = SAMPLER
                    )
                )

                val raw = try {
                    conv.sendMessage(extractionPrompt(input)).toString()
                } finally {
                    try { conv.close() } catch (_: Exception) {}
                }

                val report = parseResponse(raw)

                onFieldDone("diagnosis", report.diagnosis)
                onFieldDone("medication", report.medication)
                onFieldDone("dosage", report.dosage)
                onFieldDone("tests", report.otherTests)
                onFieldDone("followUp", report.followUp)

                Result.success(report)

            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "processTranscription failed", e)
                Result.failure(e)
            }
        }
    }

    fun destroy() {
        isModelLoaded = false
        try { engine?.close() } catch (_: Exception) {}
        engine = null
    }
}
