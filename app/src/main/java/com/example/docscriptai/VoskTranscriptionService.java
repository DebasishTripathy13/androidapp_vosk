package com.example.docscriptai;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.StorageService;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Vosk-based offline speech recognition service.
 * Optimised for 16 kHz PCM mono — the only format Vosk accepts.
 *
 * Recording strategy (changed from live streaming):
 *  - AudioRecord captures raw PCM at 16 kHz / mono / 16-bit to a temp WAV file.
 *  - On stopRecording() the WAV header is finalised, then the ENTIRE file is
 *    transcribed in one Vosk pass for much better accuracy vs. fragmented
 *    live-streaming partial results.
 *  - onRecordingComplete(text) delivers the full transcription when done.
 *
 * Key optimisations:
 *  - setMaxAlternatives(0) → no N-best CPU overhead
 *  - setWords(false) on live path  → no word-timestamp overhead
 *  - 8 KB read buffer  → fewer JNI acceptWaveForm() calls
 *  - WAV header parsed for sample-rate validation on uploaded files
 */
public class VoskTranscriptionService implements RecognitionListener {

    private static final String TAG = "VoskTranscription";
    private static final int    SAMPLE_RATE_INT = 16000;
    private static final float  SAMPLE_RATE     = 16000.0f;
    private static final String MODEL_PATH      = "model-hi";

    // AudioRecord constants
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT   = AudioFormat.ENCODING_PCM_16BIT;

    private final Context context;
    private Model model;
    private boolean isModelReady = false;

    // Recording-to-file state
    private AudioRecord audioRecord;
    private volatile boolean isCapturing    = false;
    private volatile boolean isTranscribing = false; // true while captureThread is processing WAV
    private Thread captureThread;
    private File   tempWavFile;

    private TranscriptionListener listener;

    public interface TranscriptionListener {
        void onPartialResult(String text);   // not used in record-to-file mode
        void onFinalResult(String text);     // not used in record-to-file mode
        void onError(String error);
        void onRecordingComplete(String transcription);
        void onModelReady();
        default void onTranscribing() {}     // fired while batch transcription runs
    }

    public VoskTranscriptionService(Context context, TranscriptionListener listener) {
        this.context  = context;
        this.listener = listener; // set BEFORE initModel() so onModelReady() is never lost
        initModel();
    }

    // ── Model init ────────────────────────────────────────────────────────

    private void initModel() {
        StorageService.unpack(context, MODEL_PATH, MODEL_PATH,
            m -> {
                this.model = m;
                this.isModelReady = true;
                Log.d(TAG, "Vosk model loaded via StorageService");
                if (listener != null) listener.onModelReady();
            },
            ex -> {
                Log.w(TAG, "StorageService failed, trying fallback", ex);
                loadModelFromAssets();
            });
    }

    private void loadModelFromAssets() {
        new Thread(() -> {
            try {
                AssetManager am = context.getAssets();
                String[] assets = am.list(MODEL_PATH);
                if (assets == null || assets.length == 0) {
                    String msg = "Model not found in assets.\nDownload vosk-model-small-hi-0.22 from alphacephei.com/vosk/models\nand place as assets/model-hi/";
                    Log.e(TAG, msg);
                    if (listener != null) listener.onError(msg);
                    return;
                }
                File modelDir = new File(context.getFilesDir(), MODEL_PATH);
                if (!modelDir.exists()) copyAssetFolder(am, MODEL_PATH, modelDir.getAbsolutePath());
                model = new Model(modelDir.getAbsolutePath());
                isModelReady = true;
                Log.d(TAG, "Vosk model loaded from assets fallback");
                if (listener != null) listener.onModelReady();
            } catch (Exception e) {
                Log.e(TAG, "Failed to load model from assets", e);
                if (listener != null) listener.onError("Failed to load Vosk model: " + e.getMessage());
            }
        }).start();
    }

    private void copyAssetFolder(AssetManager am, String src, String dst) throws IOException {
        String[] children = am.list(src);
        if (children == null || children.length == 0) {
            try (java.io.InputStream in = am.open(src);
                 java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
        } else {
            new File(dst).mkdirs();
            for (String child : children) copyAssetFolder(am, src + "/" + child, dst + "/" + child);
        }
    }

    /** Replace listener at runtime (e.g. after Activity recreate). */
    public void setTranscriptionListener(TranscriptionListener l) { this.listener = l; }
    public boolean isModelReady()    { return isModelReady && model != null; }
    public boolean isRecording()     { return isCapturing; }
    public boolean isTranscribing()  { return isTranscribing; }

    // ── Record to WAV, then batch-transcribe ─────────────────────────────

    /**
     * Start recording microphone audio to a temporary WAV file at 16 kHz.
     * No live transcription happens here — call stopRecording() to get the result.
     */
    public void startRecording() {
        if (!isModelReady || model == null) {
            if (listener != null) listener.onError("Model not ready. Please wait...");
            return;
        }
        if (isCapturing) return;

        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE_INT, CHANNEL_CONFIG, AUDIO_FORMAT);
        int bufSize = Math.max(minBuf, 8192); // at least 8 KB

        audioRecord = new AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE_INT, CHANNEL_CONFIG, AUDIO_FORMAT, bufSize * 4);

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            if (listener != null) listener.onError("AudioRecord failed to initialise");
            return;
        }

        try {
            tempWavFile = new File(context.getCacheDir(),
                "rec_" + System.currentTimeMillis() + ".wav");
            final RandomAccessFile raf = new RandomAccessFile(tempWavFile, "rw");
            writeWavHeader(raf, 0); // placeholder sizes

            audioRecord.startRecording();
            isCapturing = true;
            Log.d(TAG, "Recording started → " + tempWavFile.getName());

            final int captureSize = bufSize;
            captureThread = new Thread(() -> {
                byte[] buf = new byte[captureSize];
                long totalBytes = 0;
                try {
                    while (isCapturing) {
                        int read = audioRecord.read(buf, 0, captureSize);
                        if (read > 0) {
                            raf.write(buf, 0, read);
                            totalBytes += read;
                        }
                    }
                    // Finalise WAV sizes
                    raf.seek(4);  writeInt32LE(raf, (int)(totalBytes + 36));
                    raf.seek(40); writeInt32LE(raf, (int) totalBytes);
                    raf.close();
                    Log.d(TAG, "WAV finalised: " + totalBytes + " bytes ("
                        + (totalBytes / (SAMPLE_RATE_INT * 2)) + "s)");
                    // Batch transcribe the whole recording
                    transcribeRecordedFile();
                } catch (IOException e) {
                    Log.e(TAG, "Capture error", e);
                    if (listener != null) listener.onError("Recording error: " + e.getMessage());
                }
            });
            captureThread.start();

        } catch (IOException e) {
            Log.e(TAG, "Cannot create WAV file", e);
            if (listener != null) listener.onError("Cannot create recording file: " + e.getMessage());
        }
    }

    /**
     * Stop recording. The capture thread will finalise the WAV then
     * transcribe the entire file — result arrives via onRecordingComplete().
     */
    public void stopRecording() {
        isCapturing = false;
        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }
        // captureThread finalises and calls transcribeRecordedFile()
    }

    private void transcribeRecordedFile() {
        isTranscribing = true;
        if (listener != null) listener.onTranscribing();
        try {
            String result = transcribeFile(tempWavFile);
            if (listener != null) listener.onRecordingComplete(result);
        } catch (IOException e) {
            Log.e(TAG, "Transcription error", e);
            if (listener != null) listener.onError("Transcription failed: " + e.getMessage());
        } finally {
            isTranscribing = false;
            if (tempWavFile != null) { tempWavFile.delete(); tempWavFile = null; }
        }
    }

    // ── WAV helpers ───────────────────────────────────────────────────────

    private void writeWavHeader(RandomAccessFile f, int dataSize) throws IOException {
        int byteRate = SAMPLE_RATE_INT * 2; // 1 channel * 16-bit
        f.writeBytes("RIFF"); writeInt32LE(f, dataSize + 36);
        f.writeBytes("WAVE");
        f.writeBytes("fmt "); writeInt32LE(f, 16);
        writeInt16LE(f, 1);           // PCM
        writeInt16LE(f, 1);           // mono
        writeInt32LE(f, SAMPLE_RATE_INT);
        writeInt32LE(f, byteRate);
        writeInt16LE(f, 2);           // block align
        writeInt16LE(f, 16);          // bits/sample
        f.writeBytes("data"); writeInt32LE(f, dataSize);
    }

    private void writeInt32LE(RandomAccessFile f, int v) throws IOException {
        f.write(v & 0xFF); f.write((v >> 8) & 0xFF);
        f.write((v >> 16) & 0xFF); f.write((v >> 24) & 0xFF);
    }

    private void writeInt16LE(RandomAccessFile f, int v) throws IOException {
        f.write(v & 0xFF); f.write((v >> 8) & 0xFF);
    }

    // ── Transcribe a File directly (used internally + exposed for uploads) ─

    /** Transcribe a File (must be 16 kHz / mono / 16-bit WAV). */
    public String transcribeFile(File file) throws IOException {
        return transcribeAudioFile(file);
    }

    /**
     * Transcribe an audio file given a content URI.
     * Validates WAV sample rate / bit-depth and rejects non-16kHz files early.
     */
    public String transcribeAudioFile(Uri uri) throws IOException {
        if (!isModelReady || model == null) throw new IOException("Model not ready");
        java.io.InputStream is = context.getContentResolver().openInputStream(uri);
        if (is == null) throw new IOException("Cannot open audio file");
        return transcribeStream(is, true);
    }

    private String transcribeAudioFile(File file) throws IOException {
        if (!isModelReady || model == null) throw new IOException("Model not ready");
        java.io.InputStream is = new java.io.FileInputStream(file);
        return transcribeStream(is, true);
    }

    private String transcribeStream(java.io.InputStream is, boolean isWavExpected) throws IOException {
        StringBuilder sb = new StringBuilder();
        Recognizer rec = null;
        try {
            // Read 44-byte header
            byte[] header = new byte[44];
            int hr = is.read(header);
            boolean isWav = hr >= 44
                && header[0] == 'R' && header[1] == 'I'
                && header[2] == 'F' && header[3] == 'F';

            if (isWav) {
                int fileSampleRate = ByteBuffer.wrap(header, 24, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).getInt();
                int bitsPerSample  = ByteBuffer.wrap(header, 34, 2)
                    .order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
                if (fileSampleRate != SAMPLE_RATE_INT)
                    throw new IOException("WAV is " + fileSampleRate + " Hz but Vosk needs "
                        + SAMPLE_RATE_INT + " Hz.\nConvert: ffmpeg -i in.wav -ar 16000 -ac 1 out.wav");
                if (bitsPerSample != 16)
                    throw new IOException("WAV must be 16-bit PCM (found " + bitsPerSample + "-bit)");
                Log.d(TAG, "WAV ok: " + fileSampleRate + " Hz / " + bitsPerSample + "-bit");
            } else {
                // Not a WAV — reopen from start
                is.close();
                // Caller must re-provide stream; for URI case reopen is handled by callers
                Log.w(TAG, "Non-WAV file — feeding raw. Convert to 16kHz WAV for best accuracy.");
                // Just continue from where we are (header bytes are lost but it's best-effort)
            }

            rec = new Recognizer(model, SAMPLE_RATE);
            rec.setMaxAlternatives(0);
            rec.setWords(false);

            byte[] buf = new byte[8192];
            short[] audio = new short[buf.length / 2]; // pre-allocated — reused every chunk, no GC pressure
            int n;
            while ((n = is.read(buf)) != -1) {
                int shorts = n / 2;
                ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN)
                    .asShortBuffer().get(audio, 0, shorts);
                if (rec.acceptWaveForm(audio, shorts)) {
                    String t = extractText(rec.getResult());
                    if (!t.isEmpty()) { if (sb.length() > 0) sb.append(" "); sb.append(t); }
                }
            }
            String t = extractText(rec.getFinalResult());
            if (!t.isEmpty()) { if (sb.length() > 0) sb.append(" "); sb.append(t); }
            is.close();
        } finally {
            if (rec != null) rec.close();
        }
        return sb.toString().trim();
    }

    private String extractText(String json) {
        if (json == null || json.isEmpty()) return "";
        try { return new JSONObject(json).optString("text", "").trim(); }
        catch (JSONException e) { return ""; }
    }

    // ── RecognitionListener (unused — kept to satisfy interface) ──────────
    @Override public void onPartialResult(String h) {}
    @Override public void onResult(String h) {}
    @Override public void onFinalResult(String h) {}
    @Override public void onError(Exception e) {
        if (listener != null) listener.onError("Recognition error: " + e.getMessage());
    }
    @Override public void onTimeout() {}

    // ── Cleanup ───────────────────────────────────────────────────────────

    /** Release only the Vosk model from RAM (~40 MB). Service stays alive; call reloadModel() before next recording. */
    public void releaseModel() {
        if (model != null) {
            try { model.close(); } catch (Throwable ignore) {}
            model = null;
        }
        isModelReady = false;
    }

    /** Reload the model after a releaseModel() call. Result fires onModelReady() on the listener. */
    public void reloadModel() {
        if (isModelReady || model != null) return;
        initModel();
    }

    public void shutdown() {
        isCapturing = false;
        if (audioRecord != null) { audioRecord.stop(); audioRecord.release(); audioRecord = null; }
        releaseModel();
    }
}
