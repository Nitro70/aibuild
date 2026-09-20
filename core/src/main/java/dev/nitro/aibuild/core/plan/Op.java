package dev.nitro.aibuild.core.plan;

/**
 * One instruction in a build plan. Deliberately only two shapes: a single block
 * and a filled cuboid. Anything more expressive gives the model more ways to be
 * wrong without meaningfully shortening its output.
 */
public sealed interface Op permits Op.Place, Op.Fill {

    BlockSpec block();

    /** Places one block at {@code pos}. */
    record Place(Pos pos, BlockSpec block) implements Op {}

    /** Fills the inclusive cuboid between {@code from} and {@code to}. */
    record Fill(Pos from, Pos to, BlockSpec block) implements Op {

        /** Number of blocks this fill covers. */
        public long volume() {
            long dx = Math.abs((long) to.x() - from.x()) + 1;
            long dy = Math.abs((long) to.y() - from.y()) + 1;
            long dz = Math.abs((long) to.z() - from.z()) + 1;
            return dx * dy * dz;
        }
    }
}
