# Vosk Model Setup

DocScript AI uses the **Vosk** offline speech recognition engine to transcribe
doctor dictations in Hindi and English.

## Download the Hindi model

1. Go to: https://alphacephei.com/vosk/models
2. Download `vosk-model-small-hi-0.22` (~42 MB) for most devices, or
   `vosk-model-hi-0.22` (~1.5 GB) for higher accuracy.
3. Extract the archive.
4. **Rename** the extracted folder to exactly `model-hi`.
5. Copy the `model-hi` folder here:

```
docscriptai/app/src/main/assets/model-hi/
├── am/
├── conf/
├── graph/
├── ivector/
└── README
```

## English model (optional)

Download `vosk-model-small-en-us-0.15` and place as `assets/model-en/`,
then update `MODEL_PATH` constant in `VoskTranscriptionService.java`.

## LiteRT LLM model

Download a `.task` model file from https://huggingface.co/litert-community
(e.g. Gemma 3 1B or Qwen 2.5 1.5B) and import it via the **AI Model** button
inside the app.
