package com.example.docscriptai;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages AI model files (import from storage, check if downloaded, delete).
 * Mirrors the Gallery / test2application approach: models stored in
 * getExternalFilesDir(DOWNLOADS)/models/<modelName>/<fileName>
 */
public class ModelDownloadManager {
    private static final String TAG = "ModelDownloadManager";

    private final Context context;

    /** Built-in list of supported LiteRT models.  User must import them manually. */
    public static final List<AIModel> AVAILABLE_MODELS = new ArrayList<>();

    static {
        AVAILABLE_MODELS.add(new AIModel(
            "import-custom",
            "📁 Import from Storage",
            "Select a downloaded .task model file from your device",
            "custom", "custom.task", 0L, "v1.0"));

        AVAILABLE_MODELS.add(new AIModel(
            "Gemma3-1B-IT-q4",
            "Gemma 3 1B (529 MB)",
            "huggingface.co/litert-community/Gemma3-1B-IT\nFile: Gemma3-1B-IT_multi-prefill-seq_q4_ekv2048.task",
            "litert-community/Gemma3-1B-IT",
            "Gemma3-1B-IT_multi-prefill-seq_q4_ekv2048.task",
            554661246L, "20250514"));

        AVAILABLE_MODELS.add(new AIModel(
            "Qwen2.5-1.5B-q4",
            "Qwen 2.5 1.5B (1.6 GB)",
            "huggingface.co/litert-community/Qwen2.5-1.5B-Instruct\nFile: Qwen2.5-1.5B-Instruct_multi-prefill-seq_q4_ekv2048.task",
            "litert-community/Qwen2.5-1.5B-Instruct",
            "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q4_ekv2048.task",
            1681915906L, "20250514"));

        AVAILABLE_MODELS.add(new AIModel(
            "Gemma3n-e2b-q4",
            "Gemma 3n E2B (3.1 GB)",
            "huggingface.co/litert-community/Gemma3n-e2b-it\nFile: Gemma3n-e2b-it_multi-prefill-seq_q4_ekv2048.task",
            "litert-community/Gemma3n-e2b-it",
            "Gemma3n-e2b-it_multi-prefill-seq_q4_ekv2048.task",
            3307974654L, "20250514"));

        // LiteRT-LM model — uses .litertlm format, requires LiteRT-LM runtime
        AVAILABLE_MODELS.add(new AIModel(
            "Gemma3n-E4B-it-int4",
            "Gemma 3n E4B IT (4.9 GB) — LiteRT-LM",
            "huggingface.co/google/gemma-3n-E4B-it-litert-lm\nFile: gemma-3n-E4B-it-int4.litertlm\nNeeds 6+ GB free RAM",
            "google/gemma-3n-E4B-it-litert-lm",
            "gemma-3n-E4B-it-int4.litertlm",
            5278000000L, "latest"));
    }

    public interface ImportListener {
        void onProgress(int percent);
        void onComplete(File modelFile);
        void onError(String error);
    }

    public ModelDownloadManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Import a model file from temporary storage into app's model directory. */
    public void importModel(AIModel model, File sourceFile, ImportListener listener) {
        if (sourceFile == null || !sourceFile.exists()) {
            if (listener != null) listener.onError("Source file not found");
            return;
        }
        new Thread(() -> {
            try {
                File dir = getModelDir(model);
                dir.mkdirs();
                File dest = new File(dir, model.fileName);
                long total = sourceFile.length();
                long copied = 0;
                try (FileInputStream in = new FileInputStream(sourceFile);
                     FileOutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        copied += n;
                        if (listener != null && total > 0) listener.onProgress((int) (copied * 100 / total));
                    }
                }
                Log.d(TAG, "Imported: " + dest.getAbsolutePath());
                if (listener != null) listener.onComplete(dest);
            } catch (Exception e) {
                Log.e(TAG, "Import failed", e);
                if (listener != null) listener.onError("Import failed: " + e.getMessage());
            }
        }).start();
    }

    public boolean isModelDownloaded(AIModel model) {
        File f = getModelFile(model);
        return f.exists() && f.length() > 0;
    }

    public File getModelFile(AIModel model) {
        return new File(getModelDir(model), model.fileName);
    }

    private File getModelDir(AIModel model) {
        return new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "models/" + model.name);
    }

    public boolean deleteModel(AIModel model) {
        File f = getModelFile(model);
        return f.exists() && f.delete();
    }

    public List<AIModel> getDownloadedModels() {
        List<AIModel> list = new ArrayList<>();
        for (AIModel m : AVAILABLE_MODELS) {
            if (isModelDownloaded(m)) list.add(m);
        }
        return list;
    }

    /**
     * Scan public Downloads and app-private directories for .task and .litertlm model files
     * that have not yet been imported.  Returns them as AIModel objects with sourceFile set.
     */
    public List<AIModel> scanDeviceForTaskFiles() {
        List<AIModel> result = new ArrayList<>();
        File modelsRoot = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "models");
        List<File> modelFiles = new ArrayList<>();
        // Public Downloads
        scanDirForModels(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), modelFiles);
        // Root of external storage
        scanDirForModels(Environment.getExternalStorageDirectory(), modelFiles);
        // App-private external dir
        scanDirForModels(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), modelFiles);

        for (File f : modelFiles) {
            if (f.getAbsolutePath().startsWith(modelsRoot.getAbsolutePath())) continue;
            String name = f.getName();
            String baseName = name.endsWith(".litertlm") ? name.replace(".litertlm", "") : name.replace(".task", "");
            String suffix = name.endsWith(".litertlm") ? " (LiteRT-LM)" : "";
            String id = "device-" + f.getAbsolutePath().hashCode();
            AIModel m = new AIModel(id, baseName + suffix,
                "Found at " + f.getParentFile().getName() + " (" + formatSize(f.length()) + ")",
                "device", f.getName(), f.length(), "v1.0");
            m.sourceFile = f;
            result.add(m);
        }
        return result;
    }

    private void scanDirForModels(File dir, List<File> out) {
        if (dir == null || !dir.exists() || !dir.canRead()) return;
        try {
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                if (f.isFile() && isModelFile(f)) out.add(f);
                else if (f.isDirectory() && !f.getName().startsWith(".")) {
                    File[] sub = f.listFiles();
                    if (sub != null) for (File s : sub)
                        if (s.isFile() && isModelFile(s)) out.add(s);
                }
            }
        } catch (SecurityException ignore) {}
    }

    private static boolean isModelFile(File f) {
        return f.length() > 0 && (f.getName().endsWith(".task") || f.getName().endsWith(".litertlm"));
    }

    /**
     * Scan the models directory for any .task files that were imported dynamically
     * (i.e. not in AVAILABLE_MODELS).  Returns them as usable AIModel objects.
     */
    public List<AIModel> scanImportedModels() {
        List<AIModel> result = new ArrayList<>();
        File modelsRoot = new File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "models");
        if (!modelsRoot.exists()) return result;
        File[] subdirs = modelsRoot.listFiles();
        if (subdirs == null) return result;

        for (File dir : subdirs) {
            if (!dir.isDirectory()) continue;
            // Skip dirs that belong to a known model
            boolean known = false;
            for (AIModel m : AVAILABLE_MODELS) {
                if (dir.getName().equals(m.name)) { known = true; break; }
            }
            if (known) continue;
            // Find the first .task file inside
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (isModelFile(f)) {
                    String fn = f.getName();
                    String displayName = fn.endsWith(".litertlm") ? fn.replace(".litertlm", "") + " (LiteRT-LM)" : fn.replace(".task", "");
                    result.add(new AIModel(
                        dir.getName(), displayName,
                        "Imported model  (" + formatSize(f.length()) + ")",
                        "custom", fn, f.length(), "v1.0"));
                    break;
                }
            }
        }
        return result;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
