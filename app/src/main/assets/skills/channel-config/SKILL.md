# channel-config

Configure an external messaging channel (Discord).

- Configure Discord notifications to webhook https://discord.com/api/webhooks/xxx/xxx.

## Behavior
- Persists discord_webhook to DataStore
- Validates the webhook with a test ping
- Saves a LONG_TERM memory entry: "Discord channel configured"

## Tools
- SettingsRepository.setChannelConfig
- okhttp POST to webhook
