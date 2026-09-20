package dev.nitro.aibuild.core.prompt;

/**
 * The Minecraft version this build targets.
 *
 * <p>Held here rather than read from the game so that {@code core} stays free of
 * Minecraft imports and its tests produce a stable prompt. The Fabric module
 * asserts at startup that this matches the running game, so a version bump that
 * misses this constant fails loudly rather than quietly prompting for the wrong
 * version's blocks.
 */
public final class MinecraftVersion {

    /** Keep in step with {@code minecraft_version} in gradle.properties. */
    public static final String TARGET = "26.3";

    private MinecraftVersion() {}
}
