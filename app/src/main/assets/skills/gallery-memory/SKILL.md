# gallery-memory

Build / refresh a searchable memory index of your gallery.

- Sync gallery memory and refresh my profile—scan the latest 20 photos.
- Refresh the parrot-themed memory cluster.

## Behavior
1. Scan the latest N photos (default 20)
2. For each: extract tags (objects, scene, location, time-of-day)
3. Add to long-term memory as EPISODE entries
4. Update working memory: "last gallery sync: <timestamp>"

## Tools
- READ_MEDIA_IMAGES
- snapshot / screenshot — for visual fallback
