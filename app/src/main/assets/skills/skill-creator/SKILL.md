# skill-creator

Create a new skill from a natural-language description. Uses the LLM to
draft a SKILL.md file following the standard format.

- Create a skill that opens Reddit and saves the third post.
- Make a skill that opens WhatsApp and sends "Good morning" to my mom.

## Usage
`skill:skill-creator(<description>)`

The argument is a natural-language description of what the skill should
do. The LLM drafts the SKILL.md content from this description.

## Behavior
1. Send the description to the LLM with a system prompt explaining the
   SKILL.md format
2. Generate a skill ID from the description (slugified, max 24 chars)
3. Write the drafted SKILL.md to filesDir/skills/<id>/SKILL.md
4. Reload the SkillRepository so the new skill appears immediately
5. Return the skill ID so the user can reference it

## Notes
- The created skill is stored at runtime (filesDir), not bundled in the APK
- The skill appears in the Skills list after creation
- The skill can be toggled on/off like any built-in skill
