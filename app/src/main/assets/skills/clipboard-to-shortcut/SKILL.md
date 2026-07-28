# clipboard-to-shortcut

Save the clipboard URL as a named bookmark for quick launch later.

- Turn the clipboard URL into a shortcut named Amazon quick link.
- Convert the clipboard into a shortcut called Daily news portal.

## Usage
`skill:clipboard-to-shortcut(<name>)`

The argument is the bookmark name. Pass the name only — no URL (the URL
is read from the clipboard).

## Behavior
1. Read the clipboard contents
2. Validate it's a URL (http:// or https://)
3. Save as a bookmark with the given name
4. Future invocations: "Open <name>" launches the bookmarked URL

## Notes
- If the clipboard doesn't contain a URL, returns an error
- The bookmark name is used as the launch phrase: "open <name>"
