# Tokenizers

Drop HuggingFace `tokenizer.json` files here, one per model family.

```
tokenizers/
├── gemma/
│   └── tokenizer.json
├── tinyllama/
│   └── tokenizer.json
└── phi/
    └── tokenizer.json
```

The directory name must match the `<family>` prefix used in the model spec
(e.g. `local-gemma:...` → `tokenizers/gemma/tokenizer.json`).

These are loaded by `LocalLlmClient.registerTokenizer()` at app startup
(via `OmniApplication.onCreate`). The tokenizer implements the chat
template, encode/decode, and EOS/pad token ids for its model family.
