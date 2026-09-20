package dev.nitro.aibuild.fabric.undo;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the world looked like before a build ran.
 *
 * <p>Recorded lazily, one position at a time, as the build actually writes each
 * block. Snapshotting the whole bounding box up front would capture far more than
 * the build touches, and a plan's real footprint is usually sparse.
 */
public final class BuildSnapshot {

    private final String name;
    private final ResourceKey<Level> dimension;
    private final Map<BlockPos, BlockState> previous = new LinkedHashMap<>();

    public BuildSnapshot(String name, ResourceKey<Level> dimension) {
        this.name = name;
        this.dimension = dimension;
    }

    /**
     * Records the state at a position, if it has not been recorded already.
     *
     * <p>The first recording wins. A plan that writes the same position twice must
     * still restore to what was there before the build started, not to the
     * intermediate value.
     */
    public void record(BlockPos pos, BlockState state) {
        previous.putIfAbsent(pos.immutable(), state);
    }

    public Map<BlockPos, BlockState> previousStates() {
        return previous;
    }

    public String name() {
        return name;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public int size() {
        return previous.size();
    }

    public boolean isEmpty() {
        return previous.isEmpty();
    }
}
