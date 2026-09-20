package dev.nitro.aibuild.core.block;

/**
 * Which pass a block is placed in.
 *
 * <p>This ordering is the difference between a contraption that survives being
 * built and one that does not. A redstone torch placed before the block it
 * clings to simply drops as an item.
 */
public enum BlockCategory {
    /** Full blocks and anything that stands on its own. Placed first, bottom up. */
    STRUCTURAL,
    /** Needs a neighbouring block to exist already: dust, torches, repeaters, rails, levers. */
    ATTACHED
}
