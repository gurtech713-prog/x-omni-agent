# channel-config

Configure an external messaging channel (Feishu, DingTalk, etc.).

- Configure Feishu channel: app id xxx, secret xxx.
- Set Feishu webhook to https://open.feishu.cn/open-apis/bot/v2/hook/xxx.

## Behavior
- Persists feishu_app_id, feishu_app_secret, feishu_webhook to DataStore
- Validates the webhook with a test ping
- Saves a LONG_TERM memory entry: "Feishu channel configured"

## Tools
- SettingsRepository.setChannelConfig
- okhttp POST to webhook
