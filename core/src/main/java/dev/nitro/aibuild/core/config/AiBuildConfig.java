package dev.nitro.aibuild.core.config;

import dev.nitro.aibuild.core.llm.Provider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything tunable, in one place.
 *
 * <p>Mutable plain fields rather than a record, because Gson populates this
 * straight from the config file and anything the file leaves out has to keep its
 * default.
 *
 * <p>No secret has a value here. Keys are supplied by whoever installs the mod,
 * either in their own config file or through an environment variable, which is
 * what lets this source file live in a public repository.
 */
public final class AiBuildConfig {

    // ---------------------------------------------------------------- provider

    /**
     * Which provider to use. One of the ids from {@link Provider}: gemini, openai,
     * anthropic, claude-cli, deepseek, groq, xai, mistral, openrouter, together,
     * ollama, lmstudio, custom.
     */
    public String provider = Provider.GEMINI.id();

    /** Settings per provider, so switching does not lose the other one's key. */
    public Map<String, ProviderSettings> providers = defaultProviders();

    /** The Claude Code executable, for the claude-cli provider. */
    public String claudeCliPath = "claude";

    /** Extra flags for the Claude Code CLI. Normally left empty. */
    public List<String> claudeCliExtraArgs = new ArrayList<>();

    // ------------------------------------------------------------------- model

    /** Lower is more literal, higher is more inventive. */
    public double temperature = 0.4;

    /** Output token ceiling. Large builds need a large budget. */
    public int maxOutputTokens = 32768;

    public int requestTimeoutSeconds = 180;

    // ------------------------------------------------------------------ builds

    /** Hard cap on blocks in one build. */
    public long maxBlocks = 20000;

    /** Largest distance from the origin on X and Z. */
    public int maxRadius = 48;

    /** Largest distance from the origin on Y, in both directions. */
    public int maxHeight = 48;

    /** Blocks placed per tick while the build plays out. 0 places it all at once. */
    public int blocksPerTick = 12;

    /** How far in front of the player the origin sits. */
    public int originOffset = 3;

    /** Rotate the build so its front faces the player. */
    public boolean rotateToPlayer = true;

    /**
     * Flip repeater and comparator facing by 180 degrees.
     *
     * <p>An escape hatch, off by default. If contraptions come out with their
     * repeaters consistently backwards, turn this on rather than fighting the
     * prompt. See the README.
     */
    public boolean flipRepeaterFacing = false;

    /** Ask the model to fix its own plan once when validation fails. */
    public boolean repairOnInvalidPlan = true;

    // ------------------------------------------------------------ permissions

    /** Require operator permission to run /aibuild. */
    public boolean requireOperator = false;

    /** Seconds a player must wait between builds. 0 disables the cooldown. */
    public int cooldownSeconds = 0;

    /** How many builds per player can be undone. */
    public int undoHistory = 10;

    /** Blocks the server refuses to place, whatever the model asks for. */
    public Set<String> blacklist = new LinkedHashSet<>(Set.of(
            "minecraft:bedrock",
            "minecraft:command_block",
            "minecraft:chain_command_block",
            "minecraft:repeating_command_block",
            "minecraft:structure_block",
            "minecraft:jigsaw",
            "minecraft:barrier",
            "minecraft:light",
            "minecraft:end_portal",
            "minecraft:end_portal_frame",
            "minecraft:nether_portal"));

    private static Map<String, ProviderSettings> defaultProviders() {
        Map<String, ProviderSettings> map = new LinkedHashMap<>();
        for (Provider provider : Provider.values()) {
            map.put(provider.id(), new ProviderSettings(provider.defaultModel()));
        }
        return map;
    }

    /** The selected provider, falling back to Gemini if the id is not recognised. */
    public Provider activeProvider() {
        return Provider.byId(provider).orElse(Provider.GEMINI);
    }

    /** Settings for one provider, created on demand so a trimmed config still works. */
    public ProviderSettings settingsFor(Provider target) {
        if (providers == null) {
            providers = defaultProviders();
        }
        return providers.computeIfAbsent(target.id(),
                id -> new ProviderSettings(target.defaultModel()));
    }

    /**
     * Fills in anything a hand edited config left out or set nonsensically.
     *
     * @return the same instance, for chaining
     */
    public AiBuildConfig normalise() {
        if (Provider.byId(provider).isEmpty()) {
            provider = Provider.GEMINI.id();
        }
        if (providers == null) {
            providers = defaultProviders();
        } else {
            // Add entries for providers this config predates, so upgrading the mod
            // does not require deleting the file.
            for (Provider known : Provider.values()) {
                providers.computeIfAbsent(known.id(),
                        id -> new ProviderSettings(known.defaultModel()));
            }
        }
        if (claudeCliPath == null || claudeCliPath.isBlank()) {
            claudeCliPath = "claude";
        }
        if (claudeCliExtraArgs == null) {
            claudeCliExtraArgs = new ArrayList<>();
        }
        if (blacklist == null) {
            blacklist = new LinkedHashSet<>();
        }

        temperature = clamp(temperature, 0.0, 2.0);
        maxOutputTokens = (int) clamp(maxOutputTokens, 1024, 1_000_000);
        requestTimeoutSeconds = (int) clamp(requestTimeoutSeconds, 10, 900);
        maxBlocks = (long) clamp(maxBlocks, 1, 5_000_000);
        maxRadius = (int) clamp(maxRadius, 1, 512);
        maxHeight = (int) clamp(maxHeight, 1, 384);
        blocksPerTick = (int) clamp(blocksPerTick, 0, 100_000);
        originOffset = (int) clamp(originOffset, 0, 64);
        cooldownSeconds = (int) clamp(cooldownSeconds, 0, 3600);
        undoHistory = (int) clamp(undoHistory, 0, 100);
        return this;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
