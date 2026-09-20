package dev.nitro.aibuild.core.order;

import dev.nitro.aibuild.core.block.BlockCategory;
import dev.nitro.aibuild.core.block.BlockClassifier;
import dev.nitro.aibuild.core.plan.BlockSpec;
import dev.nitro.aibuild.core.plan.BuildPlan;
import dev.nitro.aibuild.core.plan.Op;
import dev.nitro.aibuild.core.plan.Placement;
import dev.nitro.aibuild.core.plan.Pos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderingTest {

    private static Op.Place place(int x, int y, int z, String block) {
        return new Op.Place(new Pos(x, y, z), BlockSpec.parse(block));
    }

    private static BuildPlan plan(Op... ops) {
        return new BuildPlan("test", "", List.of(ops));
    }

    // --------------------------------------------------------------- classifier

    @Test
    void solidBlocksAreStructural() {
        assertEquals(BlockCategory.STRUCTURAL, BlockClassifier.classify(BlockSpec.parse("stone")));
        assertEquals(BlockCategory.STRUCTURAL, BlockClassifier.classify(BlockSpec.parse("oak_planks")));
    }

    @Test
    void redstonePartsNeedSupport() {
        assertEquals(BlockCategory.ATTACHED, BlockClassifier.classify(BlockSpec.parse("redstone_wire")));
        assertEquals(BlockCategory.ATTACHED, BlockClassifier.classify(BlockSpec.parse("repeater")));
        assertEquals(BlockCategory.ATTACHED, BlockClassifier.classify(BlockSpec.parse("comparator")));
        assertEquals(BlockCategory.ATTACHED, BlockClassifier.classify(BlockSpec.parse("redstone_torch")));
        assertEquals(BlockCategory.ATTACHED, BlockClassifier.classify(BlockSpec.parse("lever")));
        assertEquals(BlockCategory.ATTACHED, BlockClassifier.classify(BlockSpec.parse("stone_button")));
        assertEquals(BlockCategory.ATTACHED, BlockClassifier.classify(BlockSpec.parse("powered_rail")));
    }

    /** Names ending in an attached suffix that are nonetheless solid blocks. */
    @Test
    void overridesBeatSuffixMatching() {
        assertEquals(BlockCategory.STRUCTURAL, BlockClassifier.classify(BlockSpec.parse("redstone_block")));
        assertEquals(BlockCategory.STRUCTURAL, BlockClassifier.classify(BlockSpec.parse("observer")));
        assertEquals(BlockCategory.STRUCTURAL, BlockClassifier.classify(BlockSpec.parse("sticky_piston")));
        assertEquals(BlockCategory.STRUCTURAL, BlockClassifier.classify(BlockSpec.parse("slime_block")));
    }

    // ----------------------------------------------------------------- expander

    @Test
    void fillExpandsToEveryBlockInclusive() {
        List<Placement> out = PlanExpander.expand(
                plan(new Op.Fill(new Pos(0, 0, 0), new Pos(1, 1, 1), BlockSpec.parse("stone"))), 100);
        assertEquals(8, out.size());
    }

    @Test
    void fillCornersMayBeGivenInAnyOrder() {
        List<Placement> ascending = PlanExpander.expand(
                plan(new Op.Fill(new Pos(0, 0, 0), new Pos(2, 0, 0), BlockSpec.parse("stone"))), 100);
        List<Placement> descending = PlanExpander.expand(
                plan(new Op.Fill(new Pos(2, 0, 0), new Pos(0, 0, 0), BlockSpec.parse("stone"))), 100);
        assertEquals(ascending.size(), descending.size());
        assertEquals(3, ascending.size());
    }

    /** Later ops overwrite earlier ones, which is how a hole gets carved. */
    @Test
    void lastWriteToAPositionWins() {
        List<Placement> out = PlanExpander.expand(plan(
                new Op.Fill(new Pos(0, 0, 0), new Pos(2, 0, 0), BlockSpec.parse("stone")),
                place(1, 0, 0, "air")), 100);

        assertEquals(3, out.size());
        Placement middle = out.stream().filter(p -> p.pos().equals(new Pos(1, 0, 0)))
                .findFirst().orElseThrow();
        assertEquals("minecraft:air", middle.block().id());
    }

    @Test
    void oversizedPlanIsRejected() {
        PlanExpander.TooLargeException error = assertThrows(PlanExpander.TooLargeException.class,
                () -> PlanExpander.expand(
                        plan(new Op.Fill(new Pos(0, 0, 0), new Pos(49, 49, 49), BlockSpec.parse("stone"))),
                        100));
        assertEquals(100, error.limit());
    }

    @Test
    void rawBlockCountCoversFills() {
        assertEquals(27, plan(new Op.Fill(
                new Pos(0, 0, 0), new Pos(2, 2, 2), BlockSpec.parse("stone"))).rawBlockCount());
    }

    // ------------------------------------------------------------------ orderer

    /**
     * The whole point of the ordering pass: a torch placed before the block it
     * clings to simply drops as an item.
     */
    @Test
    void supportGoesDownBeforeTheThingsThatClingToIt() {
        List<Placement> ordered = PlacementOrderer.order(PlanExpander.expand(plan(
                place(0, 1, 0, "redstone_torch"),
                place(0, 0, 0, "stone")), 100));

        assertEquals("minecraft:stone", ordered.get(0).block().id());
        assertEquals("minecraft:redstone_torch", ordered.get(1).block().id());
    }

    @Test
    void structuralBlocksGoBottomUp() {
        List<Placement> ordered = PlacementOrderer.order(PlanExpander.expand(plan(
                place(0, 3, 0, "stone"),
                place(0, 1, 0, "stone"),
                place(0, 2, 0, "stone")), 100));

        assertEquals(1, ordered.get(0).pos().y());
        assertEquals(2, ordered.get(1).pos().y());
        assertEquals(3, ordered.get(2).pos().y());
    }

    @Test
    void orderingKeepsEveryPlacement() {
        List<Placement> input = PlanExpander.expand(plan(
                place(0, 0, 0, "stone"),
                place(0, 1, 0, "redstone_wire"),
                place(1, 0, 0, "stone"),
                place(1, 1, 0, "repeater")), 100);

        List<Placement> ordered = PlacementOrderer.order(input);
        assertEquals(input.size(), ordered.size());
        assertTrue(ordered.containsAll(input));
    }
}
