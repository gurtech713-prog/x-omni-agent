x-omni-agent — Hermes-paradigm improvements (updated source)
=============================================================

This ZIP contains the 9 Kotlin source files that were changed/added to make the
agent reason and act like Nous Research's Hermes paradigm. The files are stored
in their ORIGINAL directory tree, so you can unzip this archive directly over the
root of your x-omni-agent checkout and the files will land in the right place:

    unzip x-omni-agent-hermes-updated.zip -d /path/to/x-omni-agent

Changed files
-------------
  app/src/main/java/com/omniclaw/app/data/model/Models.kt
      + LlmToolCall (LLM-requested tool call) and ToolSpec (tool declaration).

  app/src/main/java/com/omniclaw/app/data/llm/LlmClient.kt
      + Message gains optional toolCalls/toolCallId; CompletionResult gains
        toolCalls; complete() gains tools/toolChoice; OpenAI tools[] envelope +
        messageToJson(); parseCompletion() extracts tool_calls.

  app/src/main/java/com/omniclaw/app/data/llm/GeminiClient.kt
      + complete() gains tools/toolChoice; emits functionDeclarations + toolConfig;
        parses functionCall parts into LlmToolCall.

  app/src/main/java/com/omniclaw/app/data/llm/UnifiedLlmClient.kt
      + routes tools/toolChoice to OpenAI-compat + Gemini (LiteRT ignores them).

  app/src/main/java/com/omniclaw/app/agent/tools/DeviceToolSchema.kt   (NEW)
      + the device_action JSON-schema tool + a FAIL-CLOSED parse() (returns null
        on missing/invalid coordinates — never tap(0,0)) + toActionLine().

  app/src/main/java/com/omniclaw/app/agent/verifier/SuccessMonitor.kt
      + typed VerifyResult(ok, reason, postFingerprint) + verifyLastDetailed()
        with diagnostic reason codes; isStuck(threshold) (was dead code).

  app/src/main/java/com/omniclaw/app/agent/Planner.kt                  (NEW)
      + Plan/PlanStep + makePlan/replan/renderForPrompt (best-effort plan-then-act).

  app/src/main/java/com/omniclaw/app/data/prefs/SettingsRepository.kt
      + AgentTuning (de-hardcoded maxSteps, timeouts, memory cap, stuck threshold,
        useStructuredTools, enablePlanner) + DataStore keys + Flow + setter.

  app/src/main/java/com/omniclaw/app/agent/AgentLoop.kt
      + injects Planner; reads AgentTuning; structured tool-calling is now the
        PRIMARY path (validated JSON instead of regex); fail-closed parseDeviceAction;
        grounded self-correction; isStuck -> replan; plan tracking + prompt injection;
        de-hardcoded maxSteps/stepTimeoutMs; real usage tokens.

All changes are backward-compatible (defaults + the old regex/text path is kept as
a fallback), so non-tool models and on-device LiteRT still work.

IMPORTANT
---------
* These edits were NOT compiled here (the authoring sandbox is Python-only — no
  Android SDK / Gradle). Build and test in Android Studio before shipping.
* A unified git patch with the identical changes is also available:
  x-omni-agent-hermes-improvements.patch  (apply with: git apply <patch>).
* Documented follow-ups: Compose SettingsScreen toggles for AgentTuning, and
  native Gemini functionResponse multi-turn tool results.
