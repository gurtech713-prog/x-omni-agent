# gallery-qa

Answer questions about the local photo gallery. Scans recent photos into
memory so the agent can answer questions about them.

- What photos did I take today? Briefly in time order.
- Show me the parrot photos I took last month.
- How many screenshots did I take this week?
- Scan my latest 20 photos into memory.

## Usage
`skill:gallery-qa(<count>)`

The argument is the number of recent photos to scan (default 20). Pass
a number only — no quotes, no text. If the user asks a question without
specifying a count, use the default (20).

## Behavior
1. Scan the latest N photos from the device gallery
2. For each photo, extract metadata (date, type, approximate content)
3. Add to long-term memory
4. Answer the user's question based on the scanned photos

## Notes
- Requires READ_MEDIA_IMAGES permission
- Default to time-sorted descending
- Limit responses to <= 10 items unless asked otherwise
- Always include date + approximate count in the answer
