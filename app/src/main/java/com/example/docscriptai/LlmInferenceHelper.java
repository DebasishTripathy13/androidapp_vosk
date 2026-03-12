package com.example.docscriptai;

import android.content.Context;
import android.util.Log;

import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Content;
import com.google.ai.edge.litertlm.Contents;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.MessageCallback;
import com.google.ai.edge.litertlm.SamplerConfig;
import com.google.mediapipe.tasks.genai.llminference.LlmInference;
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions;

import java.io.File;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Inference wrapper that supports two backends, selected automatically by file extension:
 *   • .task      → MediaPipe GenAI  (Gemma 3, Qwen, etc.)
 *   • .litertlm  → LiteRT-LM        (Gemma 3n E2B/E4B, etc.)
 */
public class LlmInferenceHelper {

    private static final String TAG = "LlmInferenceHelper";

    // MediaPipe GenAI backend (for .task files)
    private LlmInference llmInference;

    // LiteRT-LM backend (for .litertlm files)
    private Engine litertEngine;
    private boolean useLiteRtLm = false;

    private boolean initialized = false;

    public interface InitCallback {
        void onSuccess();
        void onError(String error);
    }

    /** Called with each streamed token; done=true signals completion. */
    public interface StreamCallback {
        void onToken(String token);
        void onDone(String fullResponse);
        void onError(String error);
    }

    public void initialize(Context context, AIModel model, InitCallback cb) {
        new Thread(() -> {
            try {
                File f = new File(context.getFilesDir(), model.fileName);
                if (!f.exists()) {
                    ModelDownloadManager dm = new ModelDownloadManager(context);
                    f = dm.getModelFile(model);
                }
                if (!f.exists()) { cb.onError("Model file not found: " + model.fileName); return; }

                if (model.fileName.endsWith(".litertlm")) {
                    // ---- LiteRT-LM path ----
                    EngineConfig cfg = new EngineConfig(
                            f.getAbsolutePath(),
                            Backend.CPU,   // CPU works on all devices; GPU can be tried separately
                            null,          // visionBackend — text-only app
                            null,          // audioBackend  — Vosk handles ASR
                            512,           // maxNumTokens
                            null           // cacheDir
                    );
                    Engine engine = new Engine(cfg);
                    engine.initialize();
                    litertEngine = engine;
                    useLiteRtLm = true;
                } else {
                    // ---- MediaPipe GenAI path (.task files) ----
                    LlmInferenceOptions opts = LlmInferenceOptions.builder()
                            .setModelPath(f.getAbsolutePath())
                            .setMaxTopK(40)
                            .setMaxTokens(2048)
                            .build();
                    llmInference = LlmInference.createFromOptions(context, opts);
                    useLiteRtLm = false;
                }

                initialized = true;
                Log.d(TAG, "LLM initialized: " + model.displayName + "  backend=" + (useLiteRtLm ? "LiteRT-LM" : "MediaPipe"));
                cb.onSuccess();
            } catch (Throwable t) {
                Log.e(TAG, "LLM init failed", t);
                initialized = false;
                cb.onError(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            }
        }).start();
    }

    public boolean isInitialized() { return initialized; }

    /** Route to correct streaming backend. */
    public void generateStreaming(String prompt, StreamCallback cb) {
        if (!initialized) { cb.onError("LLM not initialized"); return; }
        if (useLiteRtLm) {
            litertGenerateStreaming(prompt, cb);
        } else {
            mediapipeGenerateStreaming(prompt, cb);
        }
    }

    /** Synchronous generation — call from a background thread. */
    public String generateSync(String prompt) throws Exception {
        if (!initialized) throw new IllegalStateException("LLM not initialized");
        return useLiteRtLm ? litertGenerateSync(prompt) : llmInference.generateResponse(prompt);
    }

    // =====================================================================
    // LiteRT-LM backend
    // =====================================================================

    /** Wraps LiteRT-LM's async callback API into a blocking call via CountDownLatch. */
    private String litertGenerateSync(String prompt) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        StringBuilder result = new StringBuilder();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        Conversation conv = null;
        try {
            // Fresh conversation per call → stateless, prevents cross-prompt contamination.
            SamplerConfig sampler = new SamplerConfig(40, 1.0, 0.8, 0);
            // ConversationConfig(systemInstruction, initialMessages, tools, samplerConfig, systemMessage)
            ConversationConfig convCfg = new ConversationConfig(null, Collections.emptyList(), Collections.emptyList(), sampler, null);
            conv = litertEngine.createConversation(convCfg);

            conv.sendMessageAsync(
                    Contents.Companion.of(prompt),
                    new MessageCallback() {
                        @Override public void onMessage(Message message) {
                            result.append(message.toString());
                        }
                        @Override public void onDone() { latch.countDown(); }
                        @Override public void onError(Throwable t) {
                            errorRef.set(t);
                            latch.countDown();
                        }
                    });

            if (!latch.await(120, TimeUnit.SECONDS)) throw new Exception("LiteRT-LM inference timed out after 120 s");
            if (errorRef.get() != null) throw new Exception(errorRef.get().getMessage(), errorRef.get());
            return result.toString();
        } catch (Exception e) {
            throw e;
        } catch (Throwable t) {
            throw new Exception("LiteRT-LM error: " + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()), t);
        } finally {
            if (conv != null) { try { conv.close(); } catch (Throwable ignore) {} }
        }
    }

    private void litertGenerateStreaming(String prompt, StreamCallback cb) {
        new Thread(() -> {
            Conversation conv = null;
            try {
                StringBuilder full = new StringBuilder();
                CountDownLatch latch = new CountDownLatch(1);
                AtomicReference<Throwable> errorRef = new AtomicReference<>();

                SamplerConfig sampler = new SamplerConfig(40, 1.0, 0.8, 0);
                // ConversationConfig(systemInstruction, initialMessages, tools, samplerConfig, systemMessage)
                ConversationConfig convCfg = new ConversationConfig(null, Collections.emptyList(), Collections.emptyList(), sampler, null);
                conv = litertEngine.createConversation(convCfg);

                conv.sendMessageAsync(
                        Contents.Companion.of(prompt),
                        new MessageCallback() {
                            @Override public void onMessage(Message message) {
                                String tok = message.toString();
                                full.append(tok);
                                cb.onToken(tok);
                            }
                            @Override public void onDone() { latch.countDown(); }
                            @Override public void onError(Throwable t) {
                                errorRef.set(t);
                                latch.countDown();
                            }
                        });
                latch.await(120, TimeUnit.SECONDS);
                if (errorRef.get() != null) cb.onError(errorRef.get().getMessage());
                else cb.onDone(full.toString());
            } catch (Throwable t) {
                Log.e(TAG, "LiteRT-LM streaming error", t);
                cb.onError(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            } finally {
                if (conv != null) { try { conv.close(); } catch (Throwable ignore) {} }
            }
        }).start();
    }

    // =====================================================================
    // MediaPipe GenAI backend
    // =====================================================================

    private void mediapipeGenerateStreaming(String prompt, StreamCallback cb) {
        new Thread(() -> {
            try {
                StringBuilder full = new StringBuilder();
                // Sliding-window repetition detector: stop if a 30-char window repeats 3+ times.
                final int WIN = 30;
                final int MAX_REPS = 3;
                final boolean[] stopped = {false};

                llmInference.generateResponseAsync(prompt, (partial, done) -> {
                    if (stopped[0]) return;
                    if (partial != null && !partial.isEmpty()) {
                        full.append(partial);
                        String s = full.toString();
                        if (s.length() >= WIN * MAX_REPS) {
                            String tail = s.substring(s.length() - WIN);
                            String before = s.substring(0, s.length() - WIN);
                            int count = 0, idx = 0;
                            while ((idx = before.indexOf(tail, idx)) != -1) { count++; idx++; }
                            if (count >= MAX_REPS) {
                                stopped[0] = true;
                                int firstRepeat = s.indexOf(tail);
                                String clean = firstRepeat > 0 ? s.substring(0, firstRepeat + WIN) : s;
                                cb.onDone(clean);
                                return;
                            }
                        }
                        cb.onToken(partial);
                    }
                    if (done && !stopped[0]) cb.onDone(full.toString());
                });
            } catch (Throwable t) {
                Log.e(TAG, "MediaPipe streaming error", t);
                cb.onError(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
            }
        }).start();
    }

    public void cleanup() {
        if (llmInference != null) {
            try { if (llmInference instanceof AutoCloseable) ((AutoCloseable) llmInference).close(); }
            catch (Throwable ignore) {}
            llmInference = null;
        }
        if (litertEngine != null) {
            try { litertEngine.close(); } catch (Throwable ignore) {}
            litertEngine = null;
        }
        initialized = false;
        useLiteRtLm = false;
    }
}

