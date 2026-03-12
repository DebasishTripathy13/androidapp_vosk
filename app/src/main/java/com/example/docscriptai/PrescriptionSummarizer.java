package com.example.docscriptai;

import android.util.Log;

/**
 * Makes 5 sequential LLM calls — one per section — to produce a structured
 * Hindi prescription in the standard format:
 *   शिकायतें / Rx / सलाह / जांच / फॉलो-अप
 */
public class PrescriptionSummarizer {

    private static final String TAG = "PrescriptionSummarizer";

    // One-shot example that matches the exact desired output format.
    private static final String BASE =
        "EXAMPLE\n" +
        "Transcription: \"patient ko teen din se paseliyan chal rahi hain, sans lene mein takleef hai, " +
        "bukhaar hai, kamzori hai, khansi hai, thand lag rahi hai. " +
        "Amoxicillin 500mg subah 1 raat 1 saat din tak. " +
        "Paracetamol 500mg subah 1 dopahar 1 raat 1 teen din tak. " +
        "Vitamin C subah 1 pandrah din tak. " +
        "Hafta bhar baad follow-up. X-ray aur blood test karana hoga. " +
        "Halka khana khayein, pani piyen, dhoomrapan se bachen, do hafte aaraam karein.\"\n" +
        "\n" +
        "शिकायतें (c/o) answer:\n" +
        "- पसली चलना × 3 दिन\n" +
        "- सांस लेने में तकलीफ × 3 दिन\n" +
        "- बुखार × 3 दिन\n" +
        "- शरीर में कमज़ोरी × 3 दिन\n" +
        "- खांसी × 3 दिन\n" +
        "- शरीर में ठंड लगना × 3 दिन\n" +
        "\n" +
        "Rx / दवाइयाँ answer:\n" +
        "- एमोक्सिसिलिन 500 mg  सुबह 1 – दोपहर 0 – रात 1 × 7 दिन\n" +
        "- पैरासिटामोल 500 mg  सुबह 1 – दोपहर 1 – रात 1 × 3 दिन\n" +
        "- विटामिन सी  सुबह 1 – दोपहर 0 – रात 0 × 15 दिन\n" +
        "\n" +
        "सलाह answer:\n" +
        "- हल्का, पौष्टिक आहार (सूप, दलिया, स्टीम्ड सब्ज़ियाँ) लें।\n" +
        "- भरपूर पानी पिएँ और धूम्रपान/शराब से परहेज़ करें।\n" +
        "- कम से कम 2 हफ़्ते तक आराम करें।\n" +
        "\n" +
        "जांच answer:\n" +
        "- एक्स-रे (छाती)\n" +
        "- ब्लड टेस्ट\n" +
        "\n" +
        "फॉलो-अप answer:\n" +
        "- अगले हफ़्ते\n" +
        "END EXAMPLE\n\n" +
        "Rules:\n" +
        "1. Extract ONLY what was explicitly said. Do NOT invent or guess.\n" +
        "2. Answer in Hindi (Devanagari script) only. No English except medicine names.\n" +
        "3. Each item on its own line starting with -.\n" +
        "4. Medicines format: - नाम dose  सुबह X – दोपहर X – रात X × N दिन\n" +
        "5. If a section has no information write exactly: उल्लेख नहीं किया गया\n" +
        "6. No asterisks, no markdown, no extra commentary.\n\n" +
        "Now extract from this transcription:\n" +
        "Transcription: \"%s\"\n\n";

    private static final String[] SECTION_LABELS = {
        "शिकायतें (c/o)",
        "Rx / दवाइयाँ",
        "सलाह",
        "जांच",
        "फॉलो-अप"
    };

    private static final String[] SECTION_QUESTIONS = {
        // 1. Complaints
        "Question: List all the patient's symptoms and complaints mentioned.\n" +
        "Format each as: - लक्षण × N दिन  (include duration if mentioned)\n" +
        "Hindi only, one item per line starting with -.\n" +
        "शिकायतें (c/o) answer:",

        // 2. Medicines
        "Question: List every medicine the doctor prescribed.\n" +
        "Format each as: - दवा का नाम dose  सुबह X – दोपहर X – रात X × N दिन\n" +
        "Hindi/mixed medicine names are fine, one medicine per line starting with -.\n" +
        "Rx / दवाइयाँ answer:",

        // 3. Advice
        "Question: List all lifestyle advice, diet instructions, or precautions the doctor gave.\n" +
        "Hindi only, one instruction per line starting with -.\n" +
        "सलाह answer:",

        // 4. Tests
        "Question: List all medical tests, investigations, or lab work the doctor ordered.\n" +
        "Hindi only, one test per line starting with -.\n" +
        "जांच answer:",

        // 5. Follow-up
        "Question: When did the doctor ask the patient to return for follow-up?\n" +
        "Hindi only, one short line starting with -.\n" +
        "फॉलो-अप answer:"
    };

    private static final String[] ANSWER_MARKERS = {
        "शिकायतें (c/o) answer:",
        "Rx / दवाइयाँ answer:",
        "सलाह answer:",
        "जांच answer:",
        "फॉलो-अप answer:"
    };

    public interface SummaryCallback {
        void onToken(String token);
        void onSectionStart(int sectionIndex, String label);
        void onComplete(String fullSummary);
        void onError(String error);
    }

    private final LlmInferenceHelper llm;

    public PrescriptionSummarizer(android.content.Context context, LlmInferenceHelper llm) {
        this.llm = llm;
    }

    public void summarize(String transcription, android.app.Activity activity, SummaryCallback callback) {
        if (!llm.isInitialized()) {
            activity.runOnUiThread(() -> callback.onError("AI model not loaded. Please select a model first."));
            return;
        }

        new Thread(() -> {
            StringBuilder fullOutput = new StringBuilder();
            String base = String.format(BASE, transcription.trim());

            for (int i = 0; i < SECTION_LABELS.length; i++) {
                final String label = SECTION_LABELS[i];
                final String prompt = base + SECTION_QUESTIONS[i];
                final int idx = i;

                activity.runOnUiThread(() -> callback.onSectionStart(idx, label));

                final String header = (i == 0 ? "" : "\n\n") + label + ":\n";
                activity.runOnUiThread(() -> callback.onToken(header));
                fullOutput.append(header);

                try {
                    String raw = llm.generateSync(prompt);
                    String answer = clean(raw, ANSWER_MARKERS[i]);
                    fullOutput.append(answer);
                    activity.runOnUiThread(() -> callback.onToken(answer));
                    Log.d(TAG, "Section " + (i + 1) + " done: " + answer.length() + " chars");
                } catch (Throwable t) {
                    Log.e(TAG, "Section " + (i + 1) + " failed", t);
                    String err = "उल्लेख नहीं किया गया";
                    fullOutput.append(err);
                    activity.runOnUiThread(() -> callback.onToken(err));
                }
            }

            final String full = fullOutput.toString();
            activity.runOnUiThread(() -> callback.onComplete(full));
        }).start();
    }

    private static String clean(String s, String answerMarker) {
        if (s == null || s.trim().isEmpty()) return "उल्लेख नहीं किया गया";
        String lower = s.toLowerCase();
        String markerLower = answerMarker.toLowerCase();
        int idx = lower.lastIndexOf(markerLower);
        if (idx >= 0) {
            s = s.substring(idx + answerMarker.length());
        }
        s = s.replaceAll("\\*{1,3}", "")
             .replaceAll("#{1,6}\\s?", "")
             .replaceAll("_{1,2}", "")
             .trim();
        return s.isEmpty() ? "उल्लेख नहीं किया गया" : s;
    }
}

