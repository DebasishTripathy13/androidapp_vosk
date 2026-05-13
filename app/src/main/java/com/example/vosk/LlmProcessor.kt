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

        // 4 fields — dosage is now inline with medicine
        private val LABELS = listOf("डॉक्टर", "दवाई", "जांच", "फॉलोअप")

        private const val SYSTEM_INSTRUCTION = """आप एक अनुभवी मेडिकल स्क्राइब हैं। डॉक्टर और मरीज़ की बातचीत पढ़कर हिंदी में नीचे दी गई ठीक 4 लाइनें इसी क्रम में लिखें:
डॉक्टर: [बीमारी और लक्षण — डॉक्टर की भाषा में]
दवाई: [दवा का नाम (X-X-X सुबह-दोपहर-रात), दवा का नाम (X-X-X सुबह-दोपहर-रात)]
जांच: [जांचों के नाम हिंदी में]
फॉलोअप: [कब और किस स्थिति में वापस आना है]

नियम:
- हमेशा इसी क्रम में लिखें: डॉक्टर → दवाई → जांच → फॉलोअप
- दवाई में हर दवा के साथ उसी की खुराक X-X-X रूप में लिखें — जैसे: पैरासिटामोल 500mg (1-1-1 सुबह-दोपहर-रात)
- दवाई और जांच के नाम हमेशा हिंदी (देवनागरी) में लिखें — जैसे Paracetamol → पैरासिटामोल, CBC → सीबीसी, ECG → ईसीजी
- जांच में सिर्फ़ जांचें लिखें, दवाएं नहीं
- जो जानकारी न हो: सिर्फ़ "उल्लेख नहीं" लिखें
- कोई अतिरिक्त टेक्स्ट नहीं

उदाहरण 1:
Conversation: मरीज़ को 3 दिन से तेज़ बुखार, सिरदर्द और बदन दर्द है। वायरल फ्लू बताया। Paracetamol 500mg दिन में 3 बार और Cetirizine 10mg रात को दी। 5 दिन बाद आएं। CBC करवाएं।
डॉक्टर: वायरल फ्लू, तेज़ बुखार, सिरदर्द, बदन दर्द
दवाई: पैरासिटामोल 500mg (1-1-1 सुबह-दोपहर-रात), सेटिरिज़िन 10mg (0-0-1 रात)
जांच: सीबीसी
फॉलोअप: 5 दिन बाद यदि सुधार न हो

उदाहरण 2:
Conversation: खांसी है 5 दिन से। खांसी की दवा और Vitamin C दिए। 7 दिन में ठीक न हो तो आएं। कोई जांच नहीं।
डॉक्टर: खांसी, 5 दिन से
दवाई: खांसी की दवा (1-1-1 सुबह-दोपहर-रात), विटामिन सी (1-0-0 सुबह)
जांच: उल्लेख नहीं
फॉलोअप: 7 दिन बाद यदि ठीक न हो

उदाहरण 3:
Conversation: BP 160/100 है, सीने में हल्का दर्द। Hypertension confirm। Amlodipine 5mg रोज़ सुबह। ECG और Kidney Function Test। 2 हफ्ते बाद रिपोर्ट के साथ।
डॉक्टर: उच्च रक्तचाप, सीने में दर्द, बीपी 160/100
दवाई: अम्लोडिपिन 5mg (1-0-0 सुबह)
जांच: ईसीजी, किडनी फंक्शन टेस्ट
फॉलोअप: 2 हफ्ते बाद रिपोर्ट के साथ"""

        private val SAMPLER = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.4)

        private fun extractionPrompt(text: String) = "$text\nडॉक्टर:"

        private val FIELD_PATTERN by lazy {
            val alt = LABELS.joinToString("|") { Regex.escape(it) }
            Regex("""(${alt}): *(.*?)(?=\n(?:${alt}):|$)""", setOf(RegexOption.DOT_MATCHES_ALL))
        }

        private fun parseResponse(raw: String): MedicalReport {
            val full = "डॉक्टर:$raw"
            Log.d(TAG, "Raw LLM response:\n$full")
            val map = FIELD_PATTERN.findAll(full).associate { m ->
                m.groupValues[1] to cleanField(m.groupValues[1], m.groupValues[2])
            }
            Log.d(TAG, "Parsed: $map")
            return MedicalReport(
                diagnosis  = map["डॉक्टर"]?.ifEmpty { DEFAULT_VALUE } ?: DEFAULT_VALUE,
                medication = map["दवाई"]?.ifEmpty { DEFAULT_VALUE } ?: DEFAULT_VALUE,
                otherTests = map["जांच"]?.ifEmpty { DEFAULT_VALUE } ?: DEFAULT_VALUE,
                followUp   = map["फॉलोअप"]?.ifEmpty { DEFAULT_VALUE } ?: DEFAULT_VALUE
            )
        }

        private fun cleanField(label: String, raw: String): String =
            raw.trim()
                .removePrefix("$label:").trim()
                .replace(Regex("\\*{1,2}([^*]+)\\*{1,2}"), "$1")
                .replace(Regex("#{1,6}\\s*"), "")
                .replace(Regex("(?m)^\\s*[-•–]\\s*"), "")
                .replace(Regex("\\s{2,}"), " ")
                .trim()
    }

    private var engine: Engine? = null
    private val inferenceLock = Mutex()

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
