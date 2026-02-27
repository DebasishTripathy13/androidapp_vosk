package com.example.docscriptai;

import android.util.Log;

/**
 * Makes 4 separate LLM calls — one per section — so each prompt is tiny,
 * focused, and far less likely to enter a repetition loop.
 */
public class PrescriptionSummarizer {

    private static final String TAG = "PrescriptionSummarizer";

    // Base: one-shot example + rules, prepended to every section question.
    // English instructions give small on-device models the clearest signal.
    private static final String BASE =
        // ---- One-shot example so the model learns the expected format ----
        "EXAMPLE\n" +
        "Transcription: \"patient hai viral fever hai paracetamol 500mg din mein teen baar do " +
        "aur ek hafte baad dobara aao blood test nahi chahiye\"\n" +
        "Diagnosis answer (Hindi): वायरल बुखार\n" +
        "Medications answer (English):\n- Paracetamol 500mg three times a day\n" +
        "Follow-up answer (Hindi): एक हफ्ते बाद दोबारा आएं\n" +
        "Tests answer (Hindi): उल्लेख नहीं किया गया\n" +
        "END EXAMPLE\n\n" +
        // ---- Hard rules ----
        "Rules:\n" +
        "1. Extract ONLY what is explicitly spoken in the transcription. Do NOT add anything extra.\n" +
        "2. No markdown (no **, *, #). Plain text only.\n" +
        "3. Diagnosis / Follow-up / Tests: answer in Hindi.\n" +
        "4. Medications: English only (drug name, dose, frequency). One item per line starting with -.\n" +
        "5. If a section's information is absent, write exactly: उल्लेख नहीं किया गया\n\n" +
        // ---- Real transcription ----
        "Transcription: \"%s\"\n\n";

    private static final String[] SECTION_LABELS = {
        "1. निदान (Diagnosis)",
        "2. दवाइयाँ (Medications)",
        "3. फॉलो-अप (Follow-up)",
        "4. जाँच / टेस्ट (Tests)"
    };

    private static final String[] SECTION_QUESTIONS = {
        // Diagnosis — Hindi output
        "From the transcription above, what disease or condition did the doctor mention?\n" +
        "Answer in Hindi, 1 sentence only. No extra text.\n" +
        "Diagnosis answer (Hindi):",

        // Medications — English list
        "From the transcription above, list ONLY the medicines the doctor mentioned.\n" +
        "Format: - MedicineName Dose Frequency  (e.g. - Paracetamol 500mg twice daily)\n" +
        "English only. One item per line starting with -. If none mentioned write: उल्लेख नहीं किया गया\n" +
        "Medications answer (English):",

        // Follow-up — Hindi output
        "From the transcription above, what follow-up or return visit did the doctor mention?\n" +
        "Answer in Hindi, 1 sentence only. If not mentioned write: उल्लेख नहीं किया गया\n" +
        "Follow-up answer (Hindi):",

        // Tests — Hindi output
        "From the transcription above, what tests or investigations did the doctor mention?\n" +
        "Answer in Hindi, 1 sentence only. If not mentioned write: उल्लेख नहीं किया गया\n" +
        "Tests answer (Hindi):"
    };

    // The suffix of each SECTION_QUESTIONS entry — used to strip echoed prompts.
    private static final String[] ANSWER_MARKERS = {
        "Diagnosis answer (Hindi):",
        "Medications answer (English):",
        "Follow-up answer (Hindi):",
        "Tests answer (Hindi):"
    };

    public interface SummaryCallback {
        void onToken(String token);
        void onSectionStart(int sectionIndex, String label);  // fires before each LLM call
        void onComplete(String fullSummary);
        void onError(String error);
    }

    private final LlmInferenceHelper llm;

    public PrescriptionSummarizer(android.content.Context context, LlmInferenceHelper llm) {
        this.llm = llm;
    }

    /**
     * Runs 4 sequential sync LLM calls on a background thread.
     * Appends each section to summaryText as it completes.
     */
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

                // Notify UI which section is starting
                activity.runOnUiThread(() -> callback.onSectionStart(idx, label));

                // Show section header immediately
                final String header = (i == 0 ? "" : "\n\n") + label + ":\n";
                activity.runOnUiThread(() -> callback.onToken(header));
                fullOutput.append(header);

                try {
                    String raw = llm.generateSync(prompt);
                    String answer = clean(raw, ANSWER_MARKERS[i]);
                    fullOutput.append(answer);
                    activity.runOnUiThread(() -> callback.onToken(answer));
                    Log.d(TAG, "Section " + (i + 1) + " done: " + answer.length() + " chars | raw=" + raw.length() + " chars");
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

    /**
     * Strip echoed prompt prefix (takes text after the last occurrence of answerMarker),
     * remove markdown artifacts, and trim. Falls back to the sentinel if result is empty.
     */
    private static String clean(String s, String answerMarker) {
        if (s == null || s.trim().isEmpty()) return "उल्लेख नहीं किया गया";
        // If the model echoed the prompt, grab only what follows the answer marker.
        String lower = s.toLowerCase();
        String markerLower = answerMarker.toLowerCase();
        int idx = lower.lastIndexOf(markerLower);
        if (idx >= 0) {
            s = s.substring(idx + answerMarker.length());
        }
        // Strip markdown artifacts.
        s = s.replaceAll("\\*{1,3}", "")
             .replaceAll("#{1,6}\\s?", "")
             .replaceAll("_{1,2}", "")
             .trim();
        // First line only if there are multiple lines that look like echoed instructions.
        // (Guard: keep multi-line for medications which intentionally has bullet lines.)
        if (!answerMarker.contains("Medications") && s.contains("\n")) {
            String firstLine = s.split("\n")[0].trim();
            if (!firstLine.isEmpty()) s = firstLine;
        }
        return s.isEmpty() ? "उल्लेख नहीं किया गया" : s;
    }
}
