package dev.nitro.aibuild.core.config;

/**
 * Per provider settings, one block of these per provider in the config file.
 *
 * <p>Keeping a separate entry for every provider means switching between them is
 * one word in the config and does not lose the key or model you had set for the
 * other one.
 */
public final class ProviderSettings {

    /**
     * Paste your key here, or leave it empty and set the provider's environment
     * variable instead. The environment variable wins when both are set.
     *
     * <p>This file is in .gitignore. Never commit a key.
     */
    public String apiKey = "";

    /** Model id. Empty means the provider's built in default. Use /aibuild models. */
    public String model = "";

    /** Override the endpoint. Empty means the provider's built in URL. */
    public String baseUrl = "";

    /**
     * Ask for JSON mode on OpenAI compatible providers. Turn this off if a
     * provider rejects response_format, which some local servers do.
     */
    public boolean jsonMode = true;

    public ProviderSettings() {}

    public ProviderSettings(String model) {
        this.model = model;
    }

    public String apiKeyOrEmpty() {
        return apiKey == null ? "" : apiKey.trim();
    }

    public String modelOrDefault(String fallback) {
        return model == null || model.isBlank() ? fallback : model.trim();
    }

    public String baseUrlOrDefault(String fallback) {
        return baseUrl == null || baseUrl.isBlank() ? fallback : baseUrl.trim();
    }
}
