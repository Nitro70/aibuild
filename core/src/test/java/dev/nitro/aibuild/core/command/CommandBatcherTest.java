package dev.nitro.aibuild.core.command;

import dev.nitro.aibuild.core.command.CommandBatcher.WorldBlock;
import dev.nitro.aibuild.core.plan.Pos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandBatcherTest {

    private static WorldBlock at(int x, int y, int z, String state) {
        return new WorldBlock(new Pos(x, y, z), state);
    }

    @Test
    void singleBlockIsASetblock() {
        List<String> out = CommandBatcher.toCommands(List.of(at(1, 64, 2, "minecraft:stone")), null);
        assertEquals(List.of("setblock 1 64 2 minecraft:stone"), out);
    }

    @Test
    void straightRunOfOneBlockBecomesOneFill() {
        List<String> out = CommandBatcher.toCommands(List.of(
                at(0, 64, 0, "minecraft:stone"),
                at(0, 64, 1, "minecraft:stone"),
                at(0, 64, 2, "minecraft:stone")), null);
        assertEquals(List.of("fill 0 64 0 0 64 2 minecraft:stone"), out);
    }

    @Test
    void runsCanGoTheNegativeWay() {
        List<String> out = CommandBatcher.toCommands(List.of(
                at(5, 64, 0, "minecraft:stone"),
                at(4, 64, 0, "minecraft:stone"),
                at(3, 64, 0, "minecraft:stone")), null);
        assertEquals(List.of("fill 5 64 0 3 64 0 minecraft:stone"), out);
    }

    @Test
    void aDifferentStateEndsTheRun() {
        List<String> out = CommandBatcher.toCommands(List.of(
                at(0, 64, 0, "minecraft:stone"),
                at(0, 64, 1, "minecraft:stone"),
                at(0, 64, 2, "minecraft:dirt")), null);
        assertEquals(List.of(
                "fill 0 64 0 0 64 1 minecraft:stone",
                "setblock 0 64 2 minecraft:dirt"), out);
    }

    /** Stairs facing different ways must not be merged, since state includes facing. */
    @Test
    void facingIsPartOfTheState() {
        List<String> out = CommandBatcher.toCommands(List.of(
                at(0, 64, 0, "minecraft:oak_stairs[facing=east]"),
                at(0, 64, 1, "minecraft:oak_stairs[facing=west]")), null);
        assertEquals(2, out.size());
    }

    @Test
    void aGapEndsTheRun() {
        List<String> out = CommandBatcher.toCommands(List.of(
                at(0, 64, 0, "minecraft:stone"),
                at(0, 64, 2, "minecraft:stone")), null);
        assertEquals(2, out.size());
        assertTrue(out.get(0).startsWith("setblock"));
    }

    @Test
    void turningACornerEndsTheRun() {
        List<String> out = CommandBatcher.toCommands(List.of(
                at(0, 64, 0, "minecraft:stone"),
                at(0, 64, 1, "minecraft:stone"),
                at(1, 64, 1, "minecraft:stone")), null);
        assertEquals(List.of(
                "fill 0 64 0 0 64 1 minecraft:stone",
                "setblock 1 64 1 minecraft:stone"), out);
    }

    /** Nothing may move: the placement order is what keeps torches on their blocks. */
    @Test
    void orderIsPreserved() {
        List<String> out = CommandBatcher.toCommands(List.of(
                at(0, 64, 0, "minecraft:stone"),
                at(0, 65, 0, "minecraft:redstone_torch"),
                at(0, 64, 1, "minecraft:stone")), null);
        assertEquals(3, out.size());
        assertTrue(out.get(1).contains("redstone_torch"));
    }

    @Test
    void runsAreCapped() {
        List<WorldBlock> blocks = new java.util.ArrayList<>();
        for (int z = 0; z < 10; z++) {
            blocks.add(at(0, 64, z, "minecraft:stone"));
        }
        List<String> out = CommandBatcher.toCommands(blocks, null, 4);
        assertEquals(List.of(
                "fill 0 64 0 0 64 3 minecraft:stone",
                "fill 0 64 4 0 64 7 minecraft:stone",
                "fill 0 64 8 0 64 9 minecraft:stone"), out);
    }

    /** Pinned to a dimension, so switching dimension mid build cannot move it. */
    @Test
    void dimensionIsPinned() {
        List<String> out = CommandBatcher.toCommands(
                List.of(at(1, 2, 3, "minecraft:stone")), "minecraft:the_nether");
        assertEquals(List.of("execute in minecraft:the_nether run setblock 1 2 3 minecraft:stone"), out);
    }

    @Test
    void neverUsesStrictMode() {
        List<String> out = CommandBatcher.toCommands(List.of(
                at(0, 64, 0, "minecraft:glass_pane"),
                at(0, 64, 1, "minecraft:glass_pane"),
                at(5, 64, 5, "minecraft:oak_fence")), "minecraft:overworld");
        for (String command : out) {
            assertFalse(command.contains("strict"), command);
        }
    }

    @Test
    void emptyInputGivesNoCommands() {
        assertTrue(CommandBatcher.toCommands(List.of(), null).isEmpty());
    }
}
