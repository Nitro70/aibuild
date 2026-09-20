package dev.nitro.aibuild.fabric.world;

import dev.nitro.aibuild.core.plan.BlockSpec;
import dev.nitro.aibuild.core.transform.PlanRotation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Map;
import java.util.Set;

/**
 * Turns a validated {@link BlockSpec} into a concrete {@link BlockState}.
 *
 * <p>The state is built up from the block's own default rather than parsed from
 * text, so every property goes through the registry's definition of what is
 * legal. Anything unknown has already been rejected by the validator, so by the
 * time a spec reaches here it should apply cleanly.
 */
public final class BlockResolver {

    /** Blocks whose facing the flip option applies to. */
    private static final Set<String> FLIPPABLE = Set.of("minecraft:repeater", "minecraft:comparator");

    private BlockResolver() {}

    /**
     * @param rotation  applied after the state is built, so directional properties
     *                  turn with the build
     * @param flipDiodes rotate repeaters and comparators a further 180 degrees
     */
    public static BlockState resolve(BlockSpec spec, PlanRotation rotation, boolean flipDiodes) {
        Identifier id = Identifier.tryParse(spec.id());
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            return null;
        }

        Block block = BuiltInRegistries.BLOCK.getValue(id);
        BlockState state = block.defaultBlockState();

        for (Map.Entry<String, String> entry : spec.properties().entrySet()) {
            Property<?> property = block.getStateDefinition().getProperty(entry.getKey());
            if (property != null) {
                state = applyValue(state, property, entry.getValue());
            }
        }

        Rotation effective = toMinecraft(rotation);
        if (flipDiodes && FLIPPABLE.contains(spec.id())) {
            effective = compose(effective, Rotation.CLOCKWISE_180);
        }
        // Rotation.NONE still allocates a lookup, so skip it in the common case.
        return effective == Rotation.NONE ? state : state.rotate(effective);
    }

    public static Rotation toMinecraft(PlanRotation rotation) {
        return switch (rotation) {
            case NONE -> Rotation.NONE;
            case CLOCKWISE_90 -> Rotation.CLOCKWISE_90;
            case CLOCKWISE_180 -> Rotation.CLOCKWISE_180;
            case COUNTERCLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
        };
    }

    private static Rotation compose(Rotation first, Rotation second) {
        int quarters = (quarterTurns(first) + quarterTurns(second)) % 4;
        return switch (quarters) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    private static int quarterTurns(Rotation rotation) {
        return switch (rotation) {
            case NONE -> 0;
            case CLOCKWISE_90 -> 1;
            case CLOCKWISE_180 -> 2;
            case COUNTERCLOCKWISE_90 -> 3;
        };
    }

    /**
     * Separate generic method so the wildcard on Property can be captured. Matching
     * on the property's own string name is what keeps this honest: it is the same
     * name the game writes in a block state, so it round trips.
     */
    private static <T extends Comparable<T>> BlockState applyValue(
            BlockState state, Property<T> property, String raw) {
        for (T candidate : property.getPossibleValues()) {
            if (property.getName(candidate).equals(raw)) {
                return state.setValue(property, candidate);
            }
        }
        return state;
    }
}
