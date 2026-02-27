package com.example.docscriptai;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;

/**
 * DocScript AI — Main Activity
 *
 * 3-step doctor prescription flow:
 *  1. RECORD  — Vosk offline speech-to-text (from test2application)
 *  2. TRANSCRIPTION — raw text shown in card
 *  3. SUMMARIZE — LiteRT LLM streams a structured prescription summary
 *                  (same model + Gallery Prompt Lab-style summarize button)
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "DocScriptAI";
    private static final int REQ_AUDIO = 1;
    private static final String PREFS = "docscript_prefs";
    private static final String PREF_MODEL_NAME = "saved_model_name";
    private static final String PREF_MODEL_FILE = "saved_model_file";

    // ── UI ──────────────────────────────────────────────────────────────
    private MaterialButton recordFab;
    private Button uploadButton;
    private Button summarizeButton;
    private Button clearButton;
    private Button changeModelButton;
    private TextView statusText;
    private TextView transcriptionText;
    private TextView summaryText;
    private TextView modelChipText;
    private ProgressBar progressBar;
    private MaterialCardView transcriptionCard;
    private MaterialCardView summaryCard;

    // ── Services ─────────────────────────────────────────────────────────
    private VoskTranscriptionService voskService;
    private LlmInferenceHelper llmHelper;
    private PrescriptionSummarizer summarizer;
    private ModelDownloadManager modelDownloadManager; // reused, never re-created

    // ── State ────────────────────────────────────────────────────────────
    private boolean recording = false;
    private boolean summarizing = false; // guard: don't release LLM mid-summarization
    private final StringBuilder fullTranscription = new StringBuilder();
    private AIModel currentModel;

    private ActivityResultLauncher<String> audioPicker;
    private ActivityResultLauncher<String[]> modelFilePicker;
    private ActivityResultLauncher<android.content.Intent> welcomeLauncher;

    // stored so onStart reload reuses the same listener instance
    private VoskTranscriptionService.TranscriptionListener voskListener;

    // ─────────────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setupPickers();

        // modelDownloadManager MUST be created first — used by restoreSavedModel()
        modelDownloadManager = new ModelDownloadManager(this);

        // Show onboarding screen on first launch if no model is imported yet
        welcomeLauncher = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
            result -> { currentModel = restoreSavedModel(); refreshModelChip(); });
        SharedPreferences launchPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!launchPrefs.getBoolean("first_launch_done", false)
                && modelDownloadManager.getDownloadedModels().isEmpty()) {
            welcomeLauncher.launch(new android.content.Intent(this, WelcomeActivity.class));
        }

        // Restore previously selected model from SharedPreferences
        currentModel = restoreSavedModel();
        refreshModelChip();

        // Permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
        }

        // Disable action buttons until Vosk ready
        recordFab.setEnabled(false);
        uploadButton.setEnabled(false);
        summarizeButton.setEnabled(false);

        llmHelper = new LlmInferenceHelper();
        summarizer = new PrescriptionSummarizer(this, llmHelper);

        initVosk();
        setupButtonListeners();
        // LLM is loaded on demand when user taps Summarize — do NOT load at startup.
        // This keeps 500 MB–5 GB of RAM free until it is actually needed.
    }

    private void bindViews() {
        recordFab       = findViewById(R.id.recordFab);
        uploadButton    = findViewById(R.id.uploadButton);
        summarizeButton = findViewById(R.id.summarizeButton);
        clearButton     = findViewById(R.id.clearButton);
        changeModelButton = findViewById(R.id.changeModelButton);
        statusText      = findViewById(R.id.statusText);
        transcriptionText = findViewById(R.id.transcriptionText);
        summaryText     = findViewById(R.id.summaryText);
        modelChipText   = findViewById(R.id.modelChipText);
        progressBar     = findViewById(R.id.progressBar);
        transcriptionCard = findViewById(R.id.transcriptionCard);
        summaryCard     = findViewById(R.id.summaryCard);
    }

    private void setupPickers() {
        audioPicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> { if (uri != null) transcribeFile(uri); });

        // OpenDocument (not GetContent) so the system file manager shows all file types,
        // including .task files which have no registered MIME type.
        modelFilePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> { if (uri != null) importModelFile(uri); });
    }

    private void setupButtonListeners() {
        recordFab.setOnClickListener(v -> toggleRecording());
        uploadButton.setOnClickListener(v -> audioPicker.launch("audio/*"));
        summarizeButton.setOnClickListener(v -> summarizePrescription());
        clearButton.setOnClickListener(v -> clearAll());
        changeModelButton.setOnClickListener(v ->
            new ModelSelectionDialog(this, currentModel, model -> {
                currentModel = model;
                refreshModelChip();
                loadLlmModel(model);
            }).show());
    }

    // ── Vosk init ─────────────────────────────────────────────────────────

    /**
     * Build the listener FIRST, then pass it into the VoskTranscriptionService constructor.
     * This eliminates the race condition where StorageService.unpack() could complete and
     * fire onModelReady() before setTranscriptionListener() was ever called (→ stuck app).
     */
    private void initVosk() {
        setStatus("Loading Vosk model…");
        progressBar.setVisibility(View.VISIBLE);
        recordFab.setEnabled(false);
        uploadButton.setEnabled(false);

        voskListener = new VoskTranscriptionService.TranscriptionListener() {
            @Override public void onPartialResult(String t) {
                runOnUiThread(() -> { if (!t.isEmpty()) transcriptionText.setText(fullTranscription + t); });
            }
            @Override public void onFinalResult(String t) {
                runOnUiThread(() -> { if (!t.isEmpty()) { fullTranscription.append(t).append(" "); transcriptionText.setText(fullTranscription); transcriptionCard.setVisibility(View.VISIBLE); } });
            }
            @Override public void onError(String e) {
                runOnUiThread(() -> { setStatus("❌ Vosk: " + e); progressBar.setVisibility(View.GONE); });
            }
            @Override public void onRecordingComplete(String t) {
                runOnUiThread(() -> {
                    if (!t.isEmpty()) { fullTranscription.append(t); transcriptionText.setText(fullTranscription); }
                    transcriptionCard.setVisibility(View.VISIBLE);
                    summarizeButton.setEnabled(!fullTranscription.toString().trim().isEmpty());
                    setStatus("✅ Transcription ready — tap Summarize with AI");
                    progressBar.setVisibility(View.GONE);
                    uploadButton.setEnabled(true);
                });
            }
            @Override public void onTranscribing() {
                runOnUiThread(() -> setStatus("⏳ Transcribing entire recording…"));
            }
            @Override public void onModelReady() {
                // This is the ONLY place buttons are enabled — never enable them
                // before this fires, or Record/Upload will be called while model==null.
                runOnUiThread(() -> {
                    setStatus("✅ Ready — record or upload doctor audio");
                    progressBar.setVisibility(View.GONE);
                    recordFab.setEnabled(true);
                    uploadButton.setEnabled(true);
                    // Process any file that was queued while model was reloading
                    if (pendingTranscribeUri != null) {
                        Uri uri = pendingTranscribeUri;
                        pendingTranscribeUri = null;
                        setStatus("📂 Processing audio file…");
                        progressBar.setVisibility(View.VISIBLE);
                        recordFab.setEnabled(false);
                        uploadButton.setEnabled(false);
                        transcribeFile(uri);
                    }
                });
            }
        };

        try {
            // Listener injected into constructor: onModelReady() is guaranteed to fire
            voskService = new VoskTranscriptionService(this, voskListener);
        } catch (UnsatisfiedLinkError e) {
            setStatus("❌ Vosk native lib unavailable");
            progressBar.setVisibility(View.GONE);
        } catch (Throwable t) {
            setStatus("❌ Vosk init failed: " + t.getMessage());
            progressBar.setVisibility(View.GONE);
            Log.e(TAG, "Vosk init", t);
        }
    }

    // ── Recording ────────────────────────────────────────────────────────

    private void toggleRecording() {
        if (recording) stopRecording(); else startRecording();
    }

    private void startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        if (voskService == null) { Toast.makeText(this, "Vosk not ready", Toast.LENGTH_SHORT).show(); return; }
        voskService.startRecording();
        recording = true;
        recordFab.setText("⏹  Stop");
        recordFab.setBackgroundTintList(getResources().getColorStateList(R.color.recording_red, null));
        uploadButton.setEnabled(false);
        transcriptionCard.setVisibility(View.VISIBLE);
        setStatus("🎤 Recording doctor's dictation…");
    }

    private void stopRecording() {
        if (voskService != null) voskService.stopRecording();
        recording = false;
        recordFab.setText("🎤  Record");
        recordFab.setBackgroundTintList(getResources().getColorStateList(R.color.primary_blue, null));
        setStatus("⏳ Processing recording…");
        progressBar.setVisibility(View.VISIBLE);
    }

    // ── File transcription ───────────────────────────────────────────────

    private void transcribeFile(Uri uri) {
        if (voskService == null || !voskService.isModelReady()) {
            pendingTranscribeUri = uri;
            setStatus("Reloading Vosk model…");
            progressBar.setVisibility(View.VISIBLE);
            recordFab.setEnabled(false);
            uploadButton.setEnabled(false);
            if (voskService != null) {
                if (voskListener != null) voskService.setTranscriptionListener(voskListener);
                voskService.reloadModel();
            } else {
                initVosk();
            }
            return;
        }
        setStatus("📂 Processing audio file…");
        progressBar.setVisibility(View.VISIBLE);
        recordFab.setEnabled(false);
        uploadButton.setEnabled(false);
        new Thread(() -> {
            try {
                String result = voskService.transcribeAudioFile(uri);
                runOnUiThread(() -> {
                    if (!result.isEmpty()) {
                        fullTranscription.append(result);
                        transcriptionText.setText(fullTranscription);
                        transcriptionCard.setVisibility(View.VISIBLE);
                        summarizeButton.setEnabled(true);
                        setStatus("✅ Transcription ready — tap Summarize with AI");
                    } else {
                        setStatus("⚠️ No speech detected in file");
                    }
                    progressBar.setVisibility(View.GONE);
                    recordFab.setEnabled(true);
                    uploadButton.setEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setStatus("❌ File transcription failed");
                    progressBar.setVisibility(View.GONE);
                    recordFab.setEnabled(true);
                    uploadButton.setEnabled(true);
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    // ── LLM Summarize (Gallery-style) ─────────────────────────────────────

    private void summarizePrescription() {
        String text = fullTranscription.toString().trim();
        if (text.isEmpty()) { Toast.makeText(this, "Nothing to summarize", Toast.LENGTH_SHORT).show(); return; }

        // Check model loaded
        if (!llmHelper.isInitialized()) {
            if (!modelDownloadManager.isModelDownloaded(currentModel)) {
                Toast.makeText(this, "Please import an AI model first via Change Model", Toast.LENGTH_LONG).show();
                return;
            }
            // Auto-load the model
            setStatus("⏳ Loading AI model…");
            progressBar.setVisibility(View.VISIBLE);
            summarizeButton.setEnabled(false);
            loadLlmModel(currentModel);
            // Queue summarize after model loads
            pendingSummarize = true;
            return;
        }

        runSummarize(text);
    }

    private boolean pendingSummarize = false;
    private Uri     pendingTranscribeUri = null; // queued when model wasn't ready at upload time

    private static final int MAX_PROMPT_CHARS = 3000;

    private void runSummarize(String text) {
        // Cap very long transcriptions — giant prompts exhaust RAM and make the phone crawl
        if (text.length() > MAX_PROMPT_CHARS) text = text.substring(0, MAX_PROMPT_CHARS);
        summarizing = true;
        setStatus("✨ AI is generating prescription summary…");
        progressBar.setVisibility(View.VISIBLE);
        summarizeButton.setEnabled(false);
        summaryCard.setVisibility(View.VISIBLE);
        summaryText.setText("");          // clear for typewriter effect

        summarizer.summarize(text, this, new PrescriptionSummarizer.SummaryCallback() {
            @Override public void onSectionStart(int idx, String label) {
                setStatus("✨ AI: " + label + " (" + (idx + 1) + "/4)…");
            }
            @Override public void onToken(String token) {
                // Each token is already cleaned; append directly for progressive reveal
                summaryText.append(token);
            }
            @Override public void onComplete(String full) {
                summarizing = false;
                progressBar.setVisibility(View.GONE);
                summarizeButton.setEnabled(true);
                setStatus("✅ Prescription summary ready");
                llmHelper.cleanup(); // free 500 MB–5 GB immediately after use
            }
            @Override public void onError(String err) {
                summarizing = false;
                progressBar.setVisibility(View.GONE);
                summarizeButton.setEnabled(true);
                setStatus("❌ Summarization failed");
                Toast.makeText(MainActivity.this, err, Toast.LENGTH_LONG).show();
                llmHelper.cleanup();
            }
        });
    }

    // ── Model persistence ──────────────────────────────────────────────────

    private void saveSelectedModel(AIModel model) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(PREF_MODEL_NAME, model.name)
            .putString(PREF_MODEL_FILE, model.fileName)
            .apply();
    }

    private AIModel restoreSavedModel() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        String savedName = p.getString(PREF_MODEL_NAME, null);
        if (savedName == null) {
            List<AIModel> downloaded = modelDownloadManager.getDownloadedModels();
            return downloaded.isEmpty() ? ModelDownloadManager.AVAILABLE_MODELS.get(0) : downloaded.get(0);
        }
        for (AIModel m : ModelDownloadManager.AVAILABLE_MODELS) {
            if (m.name.equals(savedName)) return m;
        }
        for (AIModel m : modelDownloadManager.scanImportedModels()) {
            if (m.name.equals(savedName)) return m;
        }
        List<AIModel> downloaded = modelDownloadManager.getDownloadedModels();
        return downloaded.isEmpty() ? ModelDownloadManager.AVAILABLE_MODELS.get(0) : downloaded.get(0);
    }

    // ── LLM model loading ──────────────────────────────────────────────────

    private void loadLlmModel(AIModel model) {
        saveSelectedModel(model);   // persist immediately so it survives app restarts
        progressBar.setVisibility(View.VISIBLE);
        summarizeButton.setEnabled(false);
        llmHelper.cleanup();
        llmHelper.initialize(this, model, new LlmInferenceHelper.InitCallback() {
            @Override public void onSuccess() {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    setStatus("✅ " + model.displayName + " ready");
                    summarizeButton.setEnabled(!fullTranscription.toString().trim().isEmpty());
                    if (pendingSummarize) {
                        pendingSummarize = false;
                        runSummarize(fullTranscription.toString().trim());
                    }
                });
            }
            @Override public void onError(String err) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    setStatus("⚠️ Model load failed — using rule analysis");
                    summarizeButton.setEnabled(!fullTranscription.toString().trim().isEmpty());
                    pendingSummarize = false;
                    Toast.makeText(MainActivity.this, "Model error: " + err, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ── Model file import ─────────────────────────────────────────────────

    /** Called by ModelSelectionDialog to trigger the file picker. */
    public void openFilePicker() {
        // */* forces the full document picker so .task files are visible
        modelFilePicker.launch(new String[]{"*/*"});
    }

    private void importModelFile(Uri uri) {
        String name = getDisplayName(uri);
        // Fallback: extract filename from the URI path segment
        if (name == null) {
            String seg = uri.getLastPathSegment();
            if (seg != null) name = seg.contains("/") ? seg.substring(seg.lastIndexOf('/') + 1) : seg;
        }
        if (name == null || (!name.endsWith(".task") && !name.endsWith(".litertlm"))) {
            Toast.makeText(this, "Please select a .task or .litertlm model file", Toast.LENGTH_LONG).show();
            return;
        }
        final String finalName = name;
        setStatus("📥 Importing model… this may take a while");
        progressBar.setVisibility(View.VISIBLE);
        new Thread(() -> {
            try {
                File tmp = new File(getCacheDir(), finalName);
                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[65536]; int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                AIModel imported = new AIModel("imported-" + System.currentTimeMillis(),
                    finalName.replace(".litertlm", "").replace(".task", ""), "Imported from storage",
                    "custom", finalName, tmp.length(), "v1.0");
                modelDownloadManager.importModel(imported, tmp, new ModelDownloadManager.ImportListener() {
                    @Override public void onProgress(int p) {}
                    @Override public void onComplete(File f) {
                        runOnUiThread(() -> {
                            tmp.delete();
                            currentModel = imported;
                            refreshModelChip();
                            Toast.makeText(MainActivity.this, "Model imported — loading…", Toast.LENGTH_SHORT).show();
                            // Auto-load the imported model immediately
                            loadLlmModel(imported);
                        });
                    }
                    @Override public void onError(String err) {
                        runOnUiThread(() -> {
                            tmp.delete();
                            progressBar.setVisibility(View.GONE);
                            Toast.makeText(MainActivity.this, "Import failed: " + err, Toast.LENGTH_LONG).show();
                        });
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(this, "Import error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String stripMarkdown(String s) {
        return s.replaceAll("\\*{1,3}", "")   // **, *, ***
                .replaceAll("#{1,6}\\s?", "")  // ## headings
                .replaceAll("_{1,2}", "");      // __ or _
    }

    private void clearAll() {
        fullTranscription.setLength(0);
        transcriptionText.setText("");
        summaryText.setText("");
        transcriptionCard.setVisibility(View.GONE);
        summaryCard.setVisibility(View.GONE);
        summarizeButton.setEnabled(false);
        setStatus("✅ Cleared — ready for new recording");
    }

    private void setStatus(String msg) { statusText.setText(msg); }

    private void refreshModelChip() {
        if (modelChipText != null && currentModel != null) modelChipText.setText(currentModel.displayName);
    }

    private String getDisplayName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignore) {}
        return null;
    }

    // ── Lifecycle / memory management ─────────────────────────────────────

    @Override
    protected void onStop() {
        super.onStop();
        // Free LLM when app goes to background (it reloads on demand).
        if (!summarizing) llmHelper.cleanup();
        // Do NOT release the Vosk model here — the file picker (and other system UIs)
        // cause onStop/onStart cycles: releasing the model here would destroy it right
        // before the upload result comes back, causing "Model not ready" errors.
        // Vosk is released only under real memory pressure in onTrimMemory().
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Reload Vosk only if it was released by onTrimMemory (not by onStop).
        if (voskService != null && !voskService.isModelReady() && !voskService.isTranscribing()) {
            recordFab.setEnabled(false);
            uploadButton.setEnabled(false);
            setStatus("Reloading Vosk model…");
            progressBar.setVisibility(View.VISIBLE);
            if (voskListener != null) voskService.setTranscriptionListener(voskListener);
            voskService.reloadModel();
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // Android signals memory pressure — release everything that can be reloaded.
        if (level >= TRIM_MEMORY_BACKGROUND) {
            if (!summarizing) llmHelper.cleanup();
            if (!recording && voskService != null && !voskService.isTranscribing()) voskService.releaseModel();
        }
    }

    // ── Permissions ───────────────────────────────────────────────────────

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] grants) {
        super.onRequestPermissionsResult(req, perms, grants);
        if (req == REQ_AUDIO && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED)
            Toast.makeText(this, "Microphone permission granted", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (voskService != null) voskService.shutdown();
        if (llmHelper != null) llmHelper.cleanup();
    }
}
