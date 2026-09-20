package dev.nitro.aibuild.core.llm;

/**
 * How a provider's wire protocol works.
 *
 * <p>Most hosted providers copy OpenAI's chat completions API, so one client
 * covers the majority of this list. Gemini and Anthropic each have their own
 * shape, and the Claude CLI is a subprocess rather than an HTTP call at all.
 */
public enum ProviderStyle {
    /** Google's generateContent endpoint, with a native responseSchema. */
    GEMINI,
    /** OpenAI chat completions, which almost everyone else implements. */
    OPENAI,
    /** Anthropic messages API, where forced tool use is how JSON is guaranteed. */
    ANTHROPIC,
    /** The local `claude` executable in print mode. No API key involved. */
    CLAUDE_CLI
}
