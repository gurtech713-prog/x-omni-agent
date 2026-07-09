# model-config

Add an OpenAI-compatible provider; set the default model.

- Add an OpenAI-compatible provider at URL xxx; default model xxx.
- Switch the default model to glm-4.6.
- Point the agent at my local llama.cpp server at http://192.168.1.10:8080/v1.

## Behavior
- Persists base_url, api_key, model, temperature, max_tokens to DataStore
- Hot-reloads the LlmClient
- Saves nothing to memory; this is pure config

## Tools
- SettingsRepository.setModelConfig
