# clipboard-to-shortcut

Turn the clipboard URL into a named skill.

- Turn the clipboard URL into a skill named Amazon quick link.
- Convert the clipboard into a skill called "Daily news portal".

## Behavior
1. READ_CLIPBOARD — get URL
2. Open Settings → Skills → Create from URL
3. Persist as: assets/skills/<name>/SKILL.md
4. Future invocations: "Open <name>" -> launch(url)

## Tools
- READ_CLIPBOARD
- launch — to verify the URL works
