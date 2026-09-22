package dev.nitro.aibuild.fabric.world;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/** Renders live block states back into the {@code id[key=value]} text form. */
public final class BlockStates {

    private BlockStates() {}

    /**
     * The shortest string {@code /setblock} and {@code /fill} will read back as
     * this exact state.
     *
     * <p>Only properties that differ from the block's default are written, since
     * the command fills the rest with defaults anyway. That keeps each command
     * short, and keeps two identical blocks producing identical text so runs of
     * them can be merged into one fill.
     */
    public static String toCommandString(BlockState state) {
        BlockState defaults = state.getBlock().defaultBlockState();
        StringBuilder props = new StringBuilder();
        for (Property<?> property : state.getBlock().getStateDefinition().getProperties()) {
            String value = valueOf(state, property);
            if (value.equals(valueOf(defaults, property))) {
                continue;
            }
            if (!props.isEmpty()) {
                props.append(',');
            }
            props.append(property.getName()).append('=').append(value);
        }
        String id = idOf(state);
        return props.isEmpty() ? id : id + "[" + props + "]";
    }

    /** Every property, defaults included. For inspecting what is really in the world. */
    public static String describe(BlockState state) {
        StringBuilder text = new StringBuilder(idOf(state));
        var properties = state.getBlock().getStateDefinition().getProperties();
        if (properties.isEmpty()) {
            return text.toString();
        }
        text.append('[');
        boolean first = true;
        for (Property<?> property : properties) {
            if (!first) {
                text.append(',');
            }
            text.append(property.getName()).append('=').append(valueOf(state, property));
            first = false;
        }
        return text.append(']').toString();
    }

    private static String idOf(BlockState state) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id == null ? "minecraft:air" : id.toString();
    }

    /** Separate so the wildcard on Property can be captured into a type variable. */
    private static <T extends Comparable<T>> String valueOf(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
