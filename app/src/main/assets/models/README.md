# LiteRT Models

Drop `.tflite` model flatbuffers here for on-device inference via LiteRT.

## Naming convention

```
models/
├── gemma-2b.tflite          # Gemma 2B (Q4 quantized, ~1.4GB)
├── gemma-2b-it.tflite       # instruction-tuned variant
├── tinyllama-1.1b.tflite    # TinyLlama 1.1B (Q4, ~700MB)
└── phi-2-mini.tflite        # Phi-2 mini
```

## Model spec format

In Settings → Model, set the model spec to:

```
local-<family>:<path>
```

Where:
- `<family>` = tokenizer family (`gemma`, `tinyllama`, `phi`)
- `<path>`   = relative path under `assets/` (e.g. `models/gemma-2b.tflite`)

Example: `local-gemma:models/gemma-2b.tflite`

## Tokenizers

Tokenizer files (HuggingFace `tokenizer.json` format) go in
`assets/tokenizers/<family>/tokenizer.json`. The `LocalLlmClient` loads
them at app startup based on the model family prefix.

## Where to get models

- Gemma: https://ai.google.dev/gemma/docs/tf_lite (conversion instructions)
- TinyLlama: https://huggingface.co/TinyLlama/TinyLlama-1.1B-Chat-v1.0 (use `convert_to_tflite.py`)
- AI Edge Torch: https://github.com/google-ai-edge/ai-edge-torch (PyTorch → LiteRT)

## Notes

- Models are extracted from the APK to `filesDir/litert_models/` on first load
  (LiteRT requires a seekable File; AssetManager fds break mmap on some devices).
- NNAPI delegate is used by default (Android 8.1+); GPU delegate is opt-in
  via `RuntimeOptions.useGpu` but disabled by default because some int8
  quantized models fail GPU compilation.
- Inference is serialized per-model via a Mutex — LiteRT interpreters are
  not thread-safe.
