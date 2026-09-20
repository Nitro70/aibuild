package dev.nitro.aibuild.core.validate;

import dev.nitro.aibuild.core.block.BlockRegistry;
import dev.nitro.aibuild.core.plan.BlockSpec;
import dev.nitro.aibuild.core.plan.BuildPlan;
import dev.nitro.aibuild.core.plan.Op;
import dev.nitro.aibuild.core.plan.Pos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanValidatorTest {

    /**
     * A stand in for the game's registry. Being able to write this at all is the
     * reason the registry is an interface rather than a static call into
     * Minecraft: none of these tests need the game.
     */
    private static final BlockRegistry REGISTRY = new BlockRegistry() {
        private final Map<String, Map<String, Set<String>>> blocks = Map.of(
                "minecraft:stone", Map.of(),
                "minecraft:bedrock", Map.of(),
                "minecraft:repeater", Map.of(
                        "facing", Set.of("north", "south", "east", "west"),
                        "delay", Set.of("1", "2", "3", "4")));

        @Override
        public boolean hasBlock(String blockId) {
            return blocks.containsKey(blockId);
        }

        @Override
        public Set<String> propertyNames(String blockId) {
            return blocks.getOrDefault(blockId, Map.of()).keySet();
        }

        @Override
        public Set<String> propertyValues(String blockId, String propertyName) {
            return blocks.getOrDefault(blockId, Map.of()).getOrDefault(propertyName, Set.of());
        }
    };

    private static PlanValidator validator() {
        return new PlanValidator(REGISTRY, Set.of("minecraft:bedrock"), 16, 16);
    }

    private static BuildPlan planOf(String block, int x, int y, int z) {
        return new BuildPlan("t", "", List.of(new Op.Place(new Pos(x, y, z), BlockSpec.parse(block))));
    }

    @Test
    void acceptsAValidPlan() {
        assertTrue(validator().validate(planOf("minecraft:repeater[facing=north,delay=2]", 0, 0, 0)).ok());
    }

    @Test
    void rejectsAnUnknownBlock() {
        ValidationResult result = validator().validate(planOf("minecraft:unobtanium", 0, 0, 0));
        assertFalse(result.ok());
        assertTrue(result.issues().get(0).message().contains("not a real block"));
    }

    @Test
    void rejectsABlacklistedBlock() {
        ValidationResult result = validator().validate(planOf("minecraft:bedrock", 0, 0, 0));
        assertFalse(result.ok());
        assertTrue(result.issues().get(0).message().contains("not allowed"));
    }

    @Test
    void rejectsAnUnknownProperty() {
        ValidationResult result = validator().validate(planOf("minecraft:repeater[locked_tight=true]", 0, 0, 0));
        assertFalse(result.ok());
        // The message lists what is legal, because it is fed back as a repair prompt.
        assertTrue(result.issues().get(0).message().contains("facing"));
    }

    @Test
    void rejectsAnIllegalPropertyValue() {
        ValidationResult result = validator().validate(planOf("minecraft:repeater[delay=9]", 0, 0, 0));
        assertFalse(result.ok());
        assertTrue(result.issues().get(0).message().contains("not a legal value"));
    }

    @Test
    void rejectsPositionsOutsideTheRadius() {
        assertFalse(validator().validate(planOf("minecraft:stone", 40, 0, 0)).ok());
        assertFalse(validator().validate(planOf("minecraft:stone", 0, 0, -40)).ok());
    }

    @Test
    void rejectsPositionsOutsideTheHeight() {
        assertFalse(validator().validate(planOf("minecraft:stone", 0, 40, 0)).ok());
        assertFalse(validator().validate(planOf("minecraft:stone", 0, -40, 0)).ok());
    }

    @Test
    void checksBothCornersOfAFill() {
        BuildPlan plan = new BuildPlan("t", "", List.of(
                new Op.Fill(new Pos(0, 0, 0), new Pos(99, 0, 0), BlockSpec.parse("minecraft:stone"))));
        assertFalse(validator().validate(plan).ok());
    }

    @Test
    void rejectsAnEmptyPlan() {
        ValidationResult result = validator().validate(new BuildPlan("t", "", List.of()));
        assertFalse(result.ok());
        assertEquals(-1, result.issues().get(0).opIndex());
    }

    @Test
    void issuesCarryTheirOpIndex() {
        BuildPlan plan = new BuildPlan("t", "", List.of(
                new Op.Place(new Pos(0, 0, 0), BlockSpec.parse("minecraft:stone")),
                new Op.Place(new Pos(0, 1, 0), BlockSpec.parse("minecraft:nonsense"))));

        ValidationResult result = validator().validate(plan);
        assertFalse(result.ok());
        assertEquals(1, result.issues().get(0).opIndex());
        assertTrue(result.issues().get(0).toString().startsWith("op 1:"));
    }

    @Test
    void feedbackIsCapped() {
        // One bad plan must not be able to flood the repair prompt.
        List<Op> ops = new java.util.ArrayList<>();
        for (int i = 0; i < 50; i++) {
            ops.add(new Op.Place(new Pos(0, i % 10, 0), BlockSpec.parse("minecraft:nonsense")));
        }
        ValidationResult result = validator().validate(new BuildPlan("t", "", ops));
        assertEquals(5, result.asFeedback(5).lines().count());
    }
}
