package com.example.docscriptai;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.card.MaterialCardView;

import java.util.List;

/**
 * Dialog for selecting an AI model (same architecture as Gallery's model picker).
 * Shows available LiteRT models with import / instructions flow.
 */
public class ModelSelectionDialog extends Dialog {

    public interface OnModelSelectedListener {
        void onModelSelected(AIModel model);
    }

    private RadioGroup radioGroup;
    private Button selectButton;
    private Button cancelButton;
    private Button actionButton;

    private final OnModelSelectedListener listener;
    private final AIModel currentModel;
    private AIModel selectedModel;
    private final ModelDownloadManager downloadManager;
    private final MainActivity activity;  // stored explicitly — getContext() returns ContextThemeWrapper, not Activity

    public ModelSelectionDialog(Context context, AIModel currentModel, OnModelSelectedListener listener) {
        super(context);
        this.listener = listener;
        this.currentModel = currentModel;
        this.selectedModel = currentModel;
        this.downloadManager = new ModelDownloadManager(context);
        this.activity = (context instanceof MainActivity) ? (MainActivity) context : null;

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_model_selection);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        radioGroup = findViewById(R.id.modelRadioGroup);
        selectButton = findViewById(R.id.selectButton);
        cancelButton = findViewById(R.id.cancelButton);
        actionButton = findViewById(R.id.downloadButton);

        buildModelList();
        setupButtons();
    }

    private void buildModelList() {
        radioGroup.removeAllViews();

        // 1. Static list (known cloud models + import-custom placeholder)
        for (AIModel model : ModelDownloadManager.AVAILABLE_MODELS)
            addModelRow(model, downloadManager.isModelDownloaded(model));

        // 2. Models already imported into app storage
        List<AIModel> imported = downloadManager.scanImportedModels();
        for (AIModel model : imported) addModelRow(model, true);

        // 3. .task files found on device storage – not yet imported
        List<AIModel> onDevice = downloadManager.scanDeviceForTaskFiles();
        if (!onDevice.isEmpty()) {
            android.widget.TextView header = new android.widget.TextView(getContext());
            header.setText("Found on device — tap \"Import & Use\":");
            header.setTextSize(11);
            header.setAlpha(0.55f);
            header.setPadding(48, 20, 8, 4);
            radioGroup.addView(header);
        }
        for (AIModel model : onDevice) addModelRow(model, false);
    }

    private void addModelRow(AIModel model, boolean downloaded) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(8, 12, 8, 12);

        RadioButton rb = new RadioButton(getContext());
        rb.setText(model.displayName);
        rb.setTextSize(15);
        rb.setTag(model);
        if (currentModel != null && model.name.equals(currentModel.name)) rb.setChecked(true);
        rb.setOnCheckedChangeListener((btn, chk) -> { if (chk) { selectedModel = model; refreshActionButton(); } });

        TextView desc = new TextView(getContext());
        desc.setText(model.description);
        desc.setTextSize(11);
        desc.setAlpha(0.6f);
        desc.setPadding(40, 2, 0, 0);
        desc.setMaxLines(2);

        String statusLabel = model.sourceFile != null
            ? "  \uD83D\uDCF1 On device"
            : (downloaded ? "  \u2713 Ready" : "");
        TextView size = new TextView(getContext());
        size.setText(model.getFormattedSize() + statusLabel);
        size.setTextSize(11);
        size.setAlpha(0.5f);
        size.setPadding(40, 2, 0, 0);

        row.addView(rb);
        row.addView(desc);
        row.addView(size);
        radioGroup.addView(row);
    }

    private void setupButtons() {
        selectButton.setOnClickListener(v -> {
            if (selectedModel == null) return;
            if (selectedModel.sourceFile != null) {
                // Device-found file — import then immediately load
                importDeviceFileAndUse(selectedModel);
            } else if (downloadManager.isModelDownloaded(selectedModel)) {
                if (listener != null) listener.onModelSelected(selectedModel);
                dismiss();
            } else {
                Toast.makeText(getContext(), "Import the model first via \"SELECT FILE\"", Toast.LENGTH_SHORT).show();
            }
        });

        actionButton.setOnClickListener(v -> {
            if (selectedModel == null) return;
            if (selectedModel.name.equals("import-custom")) {
                // Dismiss FIRST so the activity window is fully in focus before the SAF intent fires
                dismiss();
                if (activity != null) activity.openFilePicker();
            } else if (downloadManager.isModelDownloaded(selectedModel)) {
                Toast.makeText(getContext(), "Already imported", Toast.LENGTH_SHORT).show();
            } else {
                String lines = selectedModel.description.replace("\n", "\n\n");
                new android.app.AlertDialog.Builder(getContext())
                    .setTitle("Manual Import Required")
                    .setMessage("1. Download the model file from:\n\n" + lines +
                        "\n\n2. Transfer the .task file to your device.\n\n" +
                        "3. Use \"Import from Storage\" option to load it.")
                    .setPositiveButton("OK", null)
                    .show();
            }
        });

        cancelButton.setOnClickListener(v -> dismiss());
        refreshActionButton();
    }

    private void refreshActionButton() {
        if (selectedModel == null) return;
        if (selectedModel.sourceFile != null) {
            // File exists on device — let user import and use it directly
            actionButton.setText("\uD83D\uDCF1 ON DEVICE");
            actionButton.setEnabled(false);
            selectButton.setText("IMPORT & USE");
            selectButton.setEnabled(true);
        } else if (selectedModel.name.equals("import-custom")) {
            actionButton.setText("SELECT FILE");
            actionButton.setEnabled(true);
            selectButton.setText("USE THIS");
            selectButton.setEnabled(false);
        } else if (downloadManager.isModelDownloaded(selectedModel)) {
            actionButton.setText("IMPORTED \u2713");
            actionButton.setEnabled(false);
            selectButton.setText("USE THIS");
            selectButton.setEnabled(true);
        } else {
            actionButton.setText("HOW TO IMPORT");
            actionButton.setEnabled(true);
            selectButton.setText("USE THIS");
            selectButton.setEnabled(false);
        }
    }

    /** Import a device-found .task file into app storage, then fire the selection callback. */
    private void importDeviceFileAndUse(AIModel model) {
        selectButton.setEnabled(false);
        selectButton.setText("Importing\u2026");
        downloadManager.importModel(model, model.sourceFile, new ModelDownloadManager.ImportListener() {
            @Override public void onProgress(int p) {}
            @Override public void onComplete(java.io.File f) {
                if (listener != null) listener.onModelSelected(model);
                dismiss();
            }
            @Override public void onError(String err) {
                selectButton.post(() -> {
                    selectButton.setText("IMPORT & USE");
                    selectButton.setEnabled(true);
                    Toast.makeText(getContext(), "Import failed: " + err, Toast.LENGTH_LONG).show();
                });
            }
        });
    }
}
