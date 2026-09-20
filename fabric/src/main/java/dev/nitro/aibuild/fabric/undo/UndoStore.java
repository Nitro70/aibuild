package dev.nitro.aibuild.fabric.undo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;

/**
 * Per player history of what each build replaced.
 *
 * <p>Held in memory only. Undo is for the iteration loop, where a player tries a
 * prompt, dislikes the result and tries again, so surviving a restart is not
 * worth the cost of writing region data to disk.
 */
public final class UndoStore {

    private final Map<UUID, Deque<BuildSnapshot>> history = new HashMap<>();
    private final int limit;

    public UndoStore(int limit) {
        this.limit = Math.max(0, limit);
    }

    public void push(UUID player, BuildSnapshot snapshot) {
        if (limit == 0 || snapshot.isEmpty()) {
            return;
        }
        Deque<BuildSnapshot> stack = history.computeIfAbsent(player, key -> new ArrayDeque<>());
        stack.push(snapshot);
        while (stack.size() > limit) {
            stack.removeLast();
        }
    }

    public boolean hasUndo(UUID player) {
        Deque<BuildSnapshot> stack = history.get(player);
        return stack != null && !stack.isEmpty();
    }

    /**
     * Restores the most recent build for this player.
     *
     * @return the snapshot that was applied, or null when there was nothing to undo
     */
    public BuildSnapshot undo(UUID player, ServerLevel level) {
        Deque<BuildSnapshot> stack = history.get(player);
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        BuildSnapshot snapshot = stack.pop();

        // Restore without neighbour updates, then update once at the end, for the
        // same reason the build itself does: a half restored contraption firing
        // mid restore can push blocks around and corrupt the result.
        for (Entry<BlockPos, BlockState> entry : snapshot.previousStates().entrySet()) {
            level.setBlock(entry.getKey(), entry.getValue(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        for (Entry<BlockPos, BlockState> entry : snapshot.previousStates().entrySet()) {
            level.updateNeighborsAt(entry.getKey(), entry.getValue().getBlock());
        }
        return snapshot;
    }

    public void forget(UUID player) {
        history.remove(player);
    }

    public void clear() {
        history.clear();
    }
}
