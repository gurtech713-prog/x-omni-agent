# gallery-qa

Answer questions about your local photo gallery.

- What photos did I take today? Briefly in time order.
- Show me the parrot photos I took last month.
- How many screenshots did I take this week?

## Tools
- READ_MEDIA_IMAGES permission required
- snapshot — returns gallery list state when gallery app is open
- launch("com.android.gallery") — open system gallery

## Notes
- Default to time-sorted descending
- Limit responses to <= 10 items unless asked otherwise
- Always include date + approximate count
