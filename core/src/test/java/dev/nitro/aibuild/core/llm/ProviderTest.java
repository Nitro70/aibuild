package dev.nitro.aibuild.core.llm;

import dev.nitro.aibuild.core.config.AiBuildConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderTest {

    @Test
    void lookupIsForgivingAboutFormatting() {
        assertEquals(Provider.CLAUDE_CLI, Provider.byId("claude-cli").orElseThrow());
        assertEquals(Provider.CLAUDE_CLI, Provider.byId("CLAUDE_CLI").orElseThrow());
        assertEquals(Provider.GEMINI, Provider.byId("  Gemini  ").orElseThrow());
        assertTrue(Provider.byId("nope").isEmpty());
        assertTrue(Provider.byId(null).isEmpty());
    }

    /** Every HTTP provider must know its own endpoint, or setup needs more than a key. */
    @Test
    void httpProvidersShipABaseUrl() {
        for (Provider provider : Provider.values()) {
            if (provider == Provider.CLAUDE_CLI || provider == Provider.CUSTOM) {
                continue;
            }
            assertFalse(provider.defaultBaseUrl().isBlank(),
                    provider.id() + " has no base URL");
            assertFalse(provider.defaultModel().isBlank(),
                    provider.id() + " has no default model");
        }
    }

    @Test
    void localAndCliProvidersNeedNoKey() {
        assertFalse(Provider.CLAUDE_CLI.requiresApiKey());
        assertFalse(Provider.OLLAMA.requiresApiKey());
        assertFalse(Provider.LMSTUDIO.requiresApiKey());
        assertTrue(Provider.GEMINI.requiresApiKey());
        assertTrue(Provider.ANTHROPIC.requiresApiKey());
    }

    /** No key may ever be baked into the source. This is the test that enforces it. */
    @Test
    void noProviderShipsACredential() {
        for (Provider provider : Provider.values()) {
            assertTrue(provider.defaultBaseUrl().isBlank()
                            || provider.defaultBaseUrl().startsWith("http"),
                    provider.id() + " has a suspicious base URL");
            assertFalse(provider.defaultBaseUrl().contains("key="),
                    provider.id() + " has a key in its URL");
        }

        AiBuildConfig config = new AiBuildConfig().normalise();
        for (Provider provider : Provider.values()) {
            assertTrue(config.settingsFor(provider).apiKeyOrEmpty().isEmpty(),
                    provider.id() + " ships a default API key");
        }
    }

    @Test
    void configKnowsEveryProviderAfterNormalising() {
        AiBuildConfig config = new AiBuildConfig();
        config.providers.clear();
        config.normalise();
        // An older config file must not lose providers added by a later version.
        for (Provider provider : Provider.values()) {
            assertTrue(config.providers.containsKey(provider.id()), "missing " + provider.id());
        }
    }

    @Test
    void unknownProviderFallsBackRatherThanFailing() {
        AiBuildConfig config = new AiBuildConfig();
        config.provider = "wishful-thinking";
        config.normalise();
        assertEquals(Provider.GEMINI, config.activeProvider());
    }

    @Test
    void nonsensicalSettingsAreClamped() {
        AiBuildConfig config = new AiBuildConfig();
        config.temperature = 99;
        config.maxRadius = -5;
        config.blocksPerTick = -1;
        config.normalise();

        assertEquals(2.0, config.temperature);
        assertEquals(1, config.maxRadius);
        assertEquals(0, config.blocksPerTick);
    }

    @Test
    void missingKeyIsReportedRatherThanGuessed() {
        AiBuildConfig config = new AiBuildConfig().normalise();
        config.provider = Provider.OPENAI.id();
        // Only meaningful when the environment is not supplying one.
        if (System.getenv(Provider.OPENAI.apiKeyEnvVar()) == null) {
            LlmClientFactory.NotConfiguredException error = assertThrows(
                    LlmClientFactory.NotConfiguredException.class, () -> LlmClientFactory.create(config));
            assertTrue(error.getMessage().contains("OPENAI_API_KEY"));
        }
    }

    @Test
    void claudeCliNeedsNoKeyToBuild() throws Exception {
        AiBuildConfig config = new AiBuildConfig().normalise();
        config.provider = Provider.CLAUDE_CLI.id();
        // Constructing it must not require a key. Whether the executable exists is
        // only discovered when it is actually run.
        LlmClient client = LlmClientFactory.create(config);
        assertTrue(client.describe().contains("Claude Code"));
        assertTrue(client.listModels().isEmpty());
    }

    // ----------------------------------------------------- lenient JSON recovery

    @Test
    void plainJsonPassesThrough() throws Exception {
        assertEquals("{\"a\":1}", HttpSupport.extractJsonObject("{\"a\":1}"));
    }

    @Test
    void codeFencesAreStripped() throws Exception {
        String text = "```json\n{\"a\":1}\n```";
        assertEquals("{\"a\":1}", HttpSupport.extractJsonObject(text));
    }

    @Test
    void preambleIsDiscarded() throws Exception {
        String text = "Sure! Here is your build:\n{\"a\":1}";
        assertEquals("{\"a\":1}", HttpSupport.extractJsonObject(text));
    }

    @Test
    void textWithNoJsonAtAllIsAnError() {
        assertThrows(LlmException.class, () -> HttpSupport.extractJsonObject("I cannot do that."));
    }
}
