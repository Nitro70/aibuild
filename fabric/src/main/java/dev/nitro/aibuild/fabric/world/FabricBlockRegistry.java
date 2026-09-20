package dev.nitro.aibuild.fabric.world;

import dev.nitro.aibuild.core.block.BlockRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The live block registry, exposed through the narrow interface that validation
 * needs.
 *
 * <p>Everything here reads from the running game, so the validator is always
 * checking against the blocks this exact version actually has rather than a list
 * baked in at build time.
 */
public final class FabricBlockRegistry implements BlockRegistry {

    @Override
    public boolean hasBlock(String blockId) {
        Identifier id = Identifier.tryParse(blockId);
        // The block registry is defaulted, so an unknown id silently resolves to
        // air. containsKey is the only honest way to ask.
        return id != null && BuiltInRegistries.BLOCK.containsKey(id);
    }

    @Override
    public Set<String> propertyNames(String blockId) {
        Block block = lookup(blockId);
        if (block == null) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        for (Property<?> property : block.getStateDefinition().getProperties()) {
            names.add(property.getName());
        }
        return names;
    }

    @Override
    public Set<String> propertyValues(String blockId, String propertyName) {
        Block block = lookup(blockId);
        if (block == null) {
            return Set.of();
        }
        Property<?> property = block.getStateDefinition().getProperty(propertyName);
        if (property == null) {
            return Set.of();
        }
        return namesOf(property);
    }

    private static Block lookup(String blockId) {
        Identifier id = Identifier.tryParse(blockId);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            return null;
        }
        return BuiltInRegistries.BLOCK.getValue(id);
    }

    /** Separate method so the wildcard on Property can be captured into a type variable. */
    private static <T extends Comparable<T>> Set<String> namesOf(Property<T> property) {
        Set<String> values = new LinkedHashSet<>();
        for (T value : property.getPossibleValues()) {
            values.add(property.getName(value));
        }
        return values;
    }
}
