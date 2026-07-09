# app-search

Search inside a target app and return a summary.

Used when the user asks to find information inside a specific app
(Reddit, Amazon, YouTube, WhatsApp, etc.) and summarize the results.

- Search Reddit for budget travel tips and send me the summary.
- Search YouTube for "edge-native Android agent" and list the top 5 video titles.

## Tools
- launch(package) — open the target app
- tap(x,y) — tap the search bar
- type("...") — enter the query
- screenshot — capture results for VLM fallback
- snapshot — read the result list

## Loop budget
- Up to 12 steps
- If results page doesn't appear within 5 steps, fall back to vision
