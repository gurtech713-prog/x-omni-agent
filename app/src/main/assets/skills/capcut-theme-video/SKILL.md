# capcut-theme-video

One-tap video using CapCut's themed template flow.

- Find parrot-themed photos and make a one-tap video.
- Find beach photos from last summer and make a one-tap video.

## Behavior
1. Build a searchable memory index (uses gallery-memory)
2. Filter by theme keyword
3. Stage picks into a temp album: A_latest
4. launch("com.lemon.lv")  // CapCut
5. Tap "One-tap video" / "Templates"
6. Batch-select from A_latest
7. Export & share

## Tools
- gallery-memory (dependency)
- launch / tap / swipe
- screenshot — verify export succeeded
