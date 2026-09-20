package dev.nitro.aibuild.core.block;

import dev.nitro.aibuild.core.plan.BlockSpec;

import java.util.Set;

/**
 * Decides whether a block can stand on its own or needs support placed first.
 *
 * <p>Minecraft does not expose "does this need support" in a form that is usable
 * before the block exists in a world, so this is a deliberate, data driven list.
 * It is a heuristic, but a testable one, and getting it wrong only costs an
 * ordering pass rather than correctness of the block states themselves.
 */
public final class BlockClassifier {

    private BlockClassifier() {}

    /** Exact path names (namespace stripped) that need support. */
    private static final Set<String> ATTACHED_EXACT = Set.of(
            "redstone_wire", "repeater", "comparator", "redstone_torch", "redstone_wall_torch",
            "torch", "wall_torch", "soul_torch", "soul_wall_torch",
            "lever", "tripwire", "tripwire_hook", "rail", "powered_rail", "detector_rail",
            "activator_rail", "ladder", "vine", "snow", "sugar_cane", "cactus_flower",
            "nether_portal", "end_rod", "lightning_rod", "chorus_flower", "chorus_plant",
            "cake", "flower_pot", "lily_pad", "scaffolding", "sculk_vein", "hanging_roots",
            "big_dripleaf", "small_dripleaf", "bamboo", "kelp", "kelp_plant", "seagrass",
            "cocoa", "sweet_berry_bush", "nether_wart", "wheat", "carrots", "potatoes",
            "beetroots", "melon_stem", "pumpkin_stem", "attached_melon_stem",
            "attached_pumpkin_stem", "redstone_lamp_wire"
    );

    /** Path suffixes that need support. Cheaper than listing every wood type. */
    private static final String[] ATTACHED_SUFFIXES = {
            "_button", "_pressure_plate", "_sign", "_wall_sign", "_hanging_sign",
            "_wall_hanging_sign", "_banner", "_wall_banner", "_carpet", "_door", "_trapdoor",
            "_bed", "_sapling", "_head", "_skull", "_candle", "_torch", "_rail", "_vine",
            "_coral_fan", "_coral_wall_fan", "_amethyst_bud", "_cluster", "_hanging_sign"
    };

    /**
     * Blocks whose names end in an attached suffix but which are genuinely solid.
     * Without this, {@code iron_trapdoor} and friends get ordered as if they were
     * clinging to something.
     */
    private static final Set<String> STRUCTURAL_OVERRIDES = Set.of(
            "redstone_block", "target", "observer", "dispenser", "dropper", "hopper",
            "piston", "sticky_piston", "note_block", "redstone_lamp", "daylight_detector",
            "slime_block", "honey_block", "tnt", "crafting_table"
    );

    public static BlockCategory classify(BlockSpec spec) {
        String path = spec.path();

        if (STRUCTURAL_OVERRIDES.contains(path)) {
            return BlockCategory.STRUCTURAL;
        }
        if (ATTACHED_EXACT.contains(path)) {
            return BlockCategory.ATTACHED;
        }
        for (String suffix : ATTACHED_SUFFIXES) {
            if (path.endsWith(suffix)) {
                return BlockCategory.ATTACHED;
            }
        }
        return BlockCategory.STRUCTURAL;
    }
}
