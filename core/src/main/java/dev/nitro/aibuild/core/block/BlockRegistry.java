package dev.nitro.aibuild.core.block;

import java.util.Set;

/**
 * The subset of Minecraft's block registry that validation needs.
 *
 * <p>Implemented by the Fabric module against the live registry. Kept as an
 * interface so that {@code core} stays free of Minecraft imports and its tests
 * can supply a fake registry.
 */
public interface BlockRegistry {

    /** Whether a block with this exact namespaced id exists. */
    boolean hasBlock(String blockId);

    /** The state property names this block defines, e.g. {@code facing}, {@code delay}. */
    Set<String> propertyNames(String blockId);

    /** The legal values for one of this block's state properties. */
    Set<String> propertyValues(String blockId, String propertyName);
}
