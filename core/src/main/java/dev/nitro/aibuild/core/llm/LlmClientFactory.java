package dev.nitro.aibuild.core.llm;

import dev.nitro.aibuild.core.config.AiBuildConfig;
import dev.nitro.aibuild.core.config.ProviderSettings;

import java.time.Duration;

/**
 * Builds the right client for whichever provider is selected.
 *
 * <p>Key resolution order is environment variable first, then the config file.
 * That way a server can keep its key out of the world directory entirely, and a
 * shared config file never has to hold one.
 */
public final class LlmClientFactory {

    private LlmClientFactory() {}

    /** Raised when the selected provider is not usable yet, with wording aimed at the player. */
    public static final class NotConfiguredException extends Exception {
        public NotConfiguredException(String message) {
            super(message);
        }
    }

    public static LlmClient create(AiBuildConfig config) throws NotConfiguredException {
        Provider provider = config.activeProvider();
        ProviderSettings settings = config.settingsFor(provider);
        Duration timeout = Duration.ofSeconds(config.requestTimeoutSeconds);

        String model = settings.modelOrDefault(provider.defaultModel());
        String baseUrl = settings.baseUrlOrDefault(provider.defaultBaseUrl());
        String apiKey = resolveApiKey(provider, settings);

        if (provider.requiresApiKey() && apiKey.isBlank()) {
            throw new NotConfiguredException(missingKeyMessage(provider));
        }
        if (provider.style() != ProviderStyle.CLAUDE_CLI && baseUrl.isBlank()) {
            throw new NotConfiguredException("Provider '" + provider.id()
                    + "' has no base URL. Set providers." + provider.id()
                    + ".baseUrl in the config.");
        }
        if (provider.style() != ProviderStyle.CLAUDE_CLI && model.isBlank()) {
            throw new NotConfiguredException("Provider '" + provider.id()
                    + "' has no model set. Set providers." + provider.id()
                    + ".model in the config, or run /aibuild models to see the options.");
        }

        return switch (provider.style()) {
            case GEMINI -> new GeminiClient(apiKey, baseUrl, model, timeout,
                    config.temperature, config.maxOutputTokens);
            case ANTHROPIC -> new AnthropicClient(apiKey, baseUrl, model, timeout,
                    config.temperature, config.maxOutputTokens);
            case CLAUDE_CLI -> new ClaudeCliClient(config.claudeCliPath, settings.modelOrDefault(""),
                    timeout, config.claudeCliExtraArgs);
            case OPENAI -> new OpenAiCompatibleClient(provider.displayName(), apiKey, baseUrl, model,
                    timeout, config.temperature, config.maxOutputTokens, settings.jsonMode);
        };
    }

    /** Environment variable first, then the config file. */
    public static String resolveApiKey(Provider provider, ProviderSettings settings) {
        String envVar = provider.apiKeyEnvVar();
        if (!envVar.isBlank()) {
            String fromEnv = System.getenv(envVar);
            if (fromEnv != null && !fromEnv.isBlank()) {
                return fromEnv.trim();
            }
        }
        return settings.apiKeyOrEmpty();
    }

    private static String missingKeyMessage(Provider provider) {
        StringBuilder message = new StringBuilder()
                .append("No API key set for ").append(provider.displayName()).append(". ")
                .append("Put one in providers.").append(provider.id())
                .append(".apiKey in config/aibuild.json");
        if (!provider.apiKeyEnvVar().isBlank()) {
            message.append(", or set the ").append(provider.apiKeyEnvVar())
                    .append(" environment variable");
        }
        message.append('.');
        if (provider == Provider.ANTHROPIC) {
            message.append(" To use a Claude Code subscription instead of a key, run ")
                    .append("/aibuild provider claude-cli.");
        }
        return message.toString();
    }

    static String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
