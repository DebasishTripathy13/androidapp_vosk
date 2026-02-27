# DocScript AI 🩺

**Doctor Prescription Flow App** — records a doctor's dictation via offline speech-to-text (Vosk),
then summarises it into a structured prescription using an on-device LLM (Google AI Edge / LiteRT).

> Combines the Vosk transcription layer from **test2application** with the Gallery-style
> **Summarize** feature from **Google AI Edge Gallery**, so everything runs 100 % offline.

---

## ✨ Features

| Step | What happens |
|------|-------------|
| 🎤 **Record** | Tap the FAB to start recording the doctor's dictation via Vosk (Hindi + English, offline) |
| 📂 **Upload** | Or pick an existing audio file (WAV recommended) |
| 📝 **Transcription** | Raw Vosk transcription shown live as the doctor speaks |
| ✨ **Summarize with AI** | One tap sends the transcription through the on-device LLM and streams a structured prescription card — identical to Gallery's Prompt Lab Summarize button |
| 🤖 **Model picker** | Same model selection dialog as Gallery — import any LiteRT `.task` model |

### Prescription output format (LLM structured prompt)
```
DIAGNOSIS: <condition>
MEDICATIONS:
- Drug, dose, frequency
INSTRUCTIONS:
- Patient advice
```

---

## 🛠 Tech Stack

| Component | Source |
|-----------|--------|
| Vosk offline ASR | `alphacephei:vosk-android:0.3.32` — ported from **test2application** |
| LiteRT LLM inference | `com.google.mediapipe:tasks-genai:0.10.27` — same as **Gallery** |
| Model format | `.task` (LiteRT) — supports Gemma 3, Qwen 2.5, etc. |
| Language | Java, minSdk 24 (Android 7+) |
| UI | Material Design 3 |

---

## 🚀 Setup

### 1. Clone and open in Android Studio

```bash
git clone <this-repo>
```

Open the `docscriptai/` folder in Android Studio.

### 2. Add the Vosk Hindi model

Download `vosk-model-small-hi-0.22` from https://alphacephei.com/vosk/models,
rename to `model-hi`, and place at:

```
app/src/main/assets/model-hi/
├── am/
├── conf/
├── graph/
├── ivector/
└── README
```

### 3. Get a LiteRT LLM model

Download a `.task` file from https://huggingface.co/litert-community, e.g.:

- `Gemma3-1B-IT_multi-prefill-seq_q4_ekv2048.task` (~529 MB)
- `Qwen2.5-1.5B-Instruct_multi-prefill-seq_q4_ekv2048.task` (~1.6 GB)

Transfer to the device, then use **AI Model → Import from Storage** in the app.

### 4. Build & Run

```bash
./gradlew assembleDebug
```

or click **Run** in Android Studio.

---

## 📁 Project Structure

```
docscriptai/
├── app/src/main/
│   ├── java/com/example/docscriptai/
│   │   ├── VoskTranscriptionService.java   # Vosk ASR (from test2application)
│   │   ├── LlmInferenceHelper.java         # MediaPipe LLM wrapper (streaming)
│   │   ├── PrescriptionSummarizer.java     # Gallery-style summarize feature ← NEW
│   │   ├── AIModel.java                    # Model metadata
│   │   ├── ModelDownloadManager.java       # Import/check model files
│   │   ├── ModelSelectionDialog.java       # Gallery-style model picker
│   │   └── MainActivity.java              # 3-step flow: Record→Transcribe→Summarize
│   ├── res/layout/
│   │   ├── activity_main.xml
│   │   └── dialog_model_selection.xml
│   └── assets/model-hi/                   # Vosk model (not included, see setup)
```

---

## ⚠️ Disclaimer

This app is for demonstration purposes only.  
AI-generated prescriptions **must not** replace a qualified medical professional.

---

## License

Apache 2.0 — see LICENSE.
