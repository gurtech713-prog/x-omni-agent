# skill-creator

Create a new skill from a description.

- Create a skill that opens Reddit and saves the third post.
- Make a skill that opens WhatsApp and sends "Good morning" to my mom.

## Behavior
1. Parse the user's description into: trigger phrase + step list
2. Generate SKILL.md content following the standard format
3. Write to: assets/skills/<id>/SKILL.md
4. Reload SkillRepository
5. Confirm to user with: id, name, example utterances

## Tools
- LLM: to draft the SKILL.md content
- SkillRepository.reload()
