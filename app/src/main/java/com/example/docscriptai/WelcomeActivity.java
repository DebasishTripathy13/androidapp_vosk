package com.example.docscriptai;

import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * One-time onboarding screen shown on first launch when no AI model is imported.
 * Guides the user to:
 *  1. Download Gemma3 Task (.task) from Google Drive  — opens browser
 *  2. Download Gemma3n E4B (.litertlm) from HuggingFace — opens browser
 *  3. Import the downloaded file into the app via the file picker
 *
 * Both model formats are supported; the user can import one or both and switch
 * between them via "Change Model" in the main screen.
 */
public class WelcomeActivity extends AppCompatActivity {

    private static final String PREFS = "docscript_prefs";
    private static final String KEY_FIRST_LAUNCH = "first_launch_done";

    // Permanent page links (not temporary signed URLs)
    private static final String URL_GDRIVE_TASK =
        "https://drive.google.com/file/d/1U37HIU2Csu-9aADLH0oevnL6qGmsjmTV/view";
    private static final String URL_HF_LITERTLM =
        "https://huggingface.co/google/gemma-3n-E4B-it-litert-lm";

    private ProgressBar importProgress;
    private TextView importStatusText;
    private MaterialButton continueButton;

    private ModelDownloadManager modelDownloadManager;
    private ActivityResultLauncher<String[]> filePicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_welcome);

        modelDownloadManager = new ModelDownloadManager(this);

        importProgress   = findViewById(R.id.importProgress);
        importStatusText = findViewById(R.id.importStatusText);
        continueButton   = findViewById(R.id.continueButton);

        filePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(),
            uri -> { if (uri != null) importModelFile(uri); });

        findViewById(R.id.btnOpenDrive).setOnClickListener(v ->
            openUrl(URL_GDRIVE_TASK));

        findViewById(R.id.btnOpenHuggingFace).setOnClickListener(v ->
            openUrl(URL_HF_LITERTLM));

        findViewById(R.id.btnSelectFile).setOnClickListener(v ->
            filePicker.launch(new String[]{"*/*"}));

        continueButton.setOnClickListener(v -> finishOnboarding());

        findViewById(R.id.btnSkip).setOnClickListener(v -> finishOnboarding());

        // If a model was imported in a previous (incomplete) session, enable Continue
        refreshContinueState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-check after returning from the browser (user may have come back from a download)
        refreshContinueState();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private void refreshContinueState() {
        if (!modelDownloadManager.getDownloadedModels().isEmpty()) {
            importStatusText.setText("✅ Model already imported — tap Continue");
            importStatusText.setTextColor(getColor(R.color.success_green));
            importStatusText.setVisibility(View.VISIBLE);
            continueButton.setEnabled(true);
        }
    }

    private void openUrl(String url) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    private void importModelFile(Uri uri) {
        String name = getDisplayName(uri);
        if (name == null) {
            String seg = uri.getLastPathSegment();
            if (seg != null) name = seg.contains("/") ? seg.substring(seg.lastIndexOf('/') + 1) : seg;
        }
        if (name == null || (!name.endsWith(".task") && !name.endsWith(".litertlm"))) {
            Toast.makeText(this, "Please select a .task or .litertlm model file", Toast.LENGTH_LONG).show();
            return;
        }

        final String finalName = name;
        importStatusText.setText("📥 Importing " + finalName + "…");
        importStatusText.setTextColor(getColor(R.color.secondary_text));
        importStatusText.setVisibility(View.VISIBLE);
        importProgress.setProgress(0);
        importProgress.setVisibility(View.VISIBLE);
        continueButton.setEnabled(false);
        findViewById(R.id.btnSelectFile).setEnabled(false);

        new Thread(() -> {
            try {
                // Stream SAF URI → cache temp file
                File tmp = new File(getCacheDir(), finalName);
                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }

                AIModel model = new AIModel(
                    "onboard-" + System.currentTimeMillis(),
                    finalName.replace(".litertlm", "").replace(".task", ""),
                    "Imported during setup",
                    "custom", finalName, tmp.length(), "v1.0");

                modelDownloadManager.importModel(model, tmp, new ModelDownloadManager.ImportListener() {
                    @Override public void onProgress(int p) {
                        runOnUiThread(() -> importProgress.setProgress(p));
                    }
                    @Override public void onComplete(File f) {
                        runOnUiThread(() -> {
                            tmp.delete();
                            importProgress.setVisibility(View.GONE);
                            importStatusText.setText("✅ " + finalName + " imported — tap Continue!");
                            importStatusText.setTextColor(getColor(R.color.success_green));
                            continueButton.setEnabled(true);
                            // Persist as the selected model so MainActivity uses it right away
                            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putString("saved_model_name", model.name)
                                .putString("saved_model_file", model.fileName)
                                .apply();
                        });
                    }
                    @Override public void onError(String err) {
                        runOnUiThread(() -> {
                            tmp.delete();
                            importProgress.setVisibility(View.GONE);
                            importStatusText.setText("❌ Import failed: " + err);
                            importStatusText.setTextColor(getColor(R.color.error_red));
                            findViewById(R.id.btnSelectFile).setEnabled(true);
                        });
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    importProgress.setVisibility(View.GONE);
                    importStatusText.setText("❌ Error: " + e.getMessage());
                    importStatusText.setTextColor(getColor(R.color.error_red));
                    findViewById(R.id.btnSelectFile).setEnabled(true);
                });
            }
        }).start();
    }

    private String getDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {}
        return null;
    }

    private void finishOnboarding() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_FIRST_LAUNCH, true)
            .apply();
        setResult(RESULT_OK);
        finish();
    }
}
