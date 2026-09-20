package dev.nitro.aibuild.core.llm;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Every provider the mod knows how to talk to.
 *
 * <p>Each one carries its own base URL and default model, so setting up a
 * provider only takes an API key. Nothing here is a secret, which is what lets
 * this file live in the repository.
 *
 * <p>Default models are a starting point and they go stale: model ids churn far
 * faster than this mod will. Use {@code /aibuild models} to see what a key can
 * actually reach and set the model from there.
 */
public enum Provider {

    GEMINI("gemini", "Google Gemini",
            "https://generativelanguage.googleapis.com/v1beta",
            ProviderStyle.GEMINI, "GEMINI_API_KEY", "gemini-2.5-pro", true),

    OPENAI("openai", "OpenAI",
            "https://api.openai.com/v1",
            ProviderStyle.OPENAI, "OPENAI_API_KEY", "gpt-5", true),

    ANTHROPIC("anthropic", "Anthropic (API key)",
            "https://api.anthropic.com/v1",
            ProviderStyle.ANTHROPIC, "ANTHROPIC_API_KEY", "claude-sonnet-5", true),

    /**
     * Anthropic through the Claude Code CLI instead of an API key. If you are
     * already signed in to Claude Code, this uses that subscription and needs no
     * key at all. The only provider here with that option.
     */
    CLAUDE_CLI("claude-cli", "Claude Code CLI (uses your signed in subscription)",
            "", ProviderStyle.CLAUDE_CLI, "", "", false),

    DEEPSEEK("deepseek", "DeepSeek",
            "https://api.deepseek.com/v1",
            ProviderStyle.OPENAI, "DEEPSEEK_API_KEY", "deepseek-chat", true),

    GROQ("groq", "Groq",
            "https://api.groq.com/openai/v1",
            ProviderStyle.OPENAI, "GROQ_API_KEY", "llama-3.3-70b-versatile", true),

    XAI("xai", "xAI Grok",
            "https://api.x.ai/v1",
            ProviderStyle.OPENAI, "XAI_API_KEY", "grok-4", true),

    MISTRAL("mistral", "Mistral",
            "https://api.mistral.ai/v1",
            ProviderStyle.OPENAI, "MISTRAL_API_KEY", "mistral-large-latest", true),

    OPENROUTER("openrouter", "OpenRouter (many models, one key)",
            "https://openrouter.ai/api/v1",
            ProviderStyle.OPENAI, "OPENROUTER_API_KEY", "google/gemini-2.5-pro", true),

    TOGETHER("together", "Together AI",
            "https://api.together.xyz/v1",
            ProviderStyle.OPENAI, "TOGETHER_API_KEY", "meta-llama/Llama-3.3-70B-Instruct-Turbo", true),

    /** Local, no key, no cost. Needs Ollama running on this machine. */
    OLLAMA("ollama", "Ollama (local)",
            "http://localhost:11434/v1",
            ProviderStyle.OPENAI, "", "qwen2.5-coder:14b", false),

    /** Local, no key. Needs LM Studio's server running. */
    LMSTUDIO("lmstudio", "LM Studio (local)",
            "http://localhost:1234/v1",
            ProviderStyle.OPENAI, "", "local-model", false),

    /**
     * Anything else that speaks the OpenAI chat completions API. Set baseUrl
     * yourself in the config.
     */
    CUSTOM("custom", "Custom OpenAI compatible endpoint",
            "", ProviderStyle.OPENAI, "CUSTOM_API_KEY", "", false);

    private final String id;
    private final String displayName;
    private final String defaultBaseUrl;
    private final ProviderStyle style;
    private final String apiKeyEnvVar;
    private final String defaultModel;
    private final boolean requiresApiKey;

    Provider(String id, String displayName, String defaultBaseUrl, ProviderStyle style,
             String apiKeyEnvVar, String defaultModel, boolean requiresApiKey) {
        this.id = id;
        this.displayName = displayName;
        this.defaultBaseUrl = defaultBaseUrl;
        this.style = style;
        this.apiKeyEnvVar = apiKeyEnvVar;
        this.defaultModel = defaultModel;
        this.requiresApiKey = requiresApiKey;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String defaultBaseUrl() {
        return defaultBaseUrl;
    }

    public ProviderStyle style() {
        return style;
    }

    /** Environment variable checked before the config file. Empty when there is none. */
    public String apiKeyEnvVar() {
        return apiKeyEnvVar;
    }

    public String defaultModel() {
        return defaultModel;
    }

    public boolean requiresApiKey() {
        return requiresApiKey;
    }

    public static Optional<Provider> byId(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String needle = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return Arrays.stream(values()).filter(p -> p.id.equals(needle)).findFirst();
    }

    public static List<String> allIds() {
        return Arrays.stream(values()).map(Provider::id).toList();
    }
}
