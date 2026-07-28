# app-search

Launch any app and search inside it. Generic — works with any app that has a search bar.

- Search Reddit for budget travel tips and send me the summary.
- Search YouTube for "edge-native Android agent" and list the top 5 video titles.
- Search Spotify for lo-fi study music.

## Usage
`skill:app-search(<package>:<query>)`

The argument is `<package>:<query>` — the package name of the target app,
a colon, then the search query. If the user gives an app name (e.g.
"Reddit"), map it to the package name first.

## Behavior
1. launch(package) — open the target app
2. tap the search bar (found via accessibility tree)
3. type the query
4. Wait for results to load
5. Read the result list via accessibility tree or screenshot+VLM fallback
6. Summarize the top results in THOUGHT, then ACTION: done

## Loop budget
- Up to 12 steps
- If results page doesn't appear within 5 steps, fall back to vision

## Notes
- Only use this skill when the user explicitly asks to search inside a
  specific app. For general web search, use the browser via launch().
- Common app packages: Reddit=com.reddit.frontpage,
  YouTube=com.google.android.youtube, Spotify=com.spotify.music,
  WhatsApp=com.whatsapp, Instagram=com.instagram.android,
  X/Twitter=com.twitter.android, Telegram=org.telegram.messenger.
