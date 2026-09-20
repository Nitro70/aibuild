package dev.nitro.aibuild.core.transform;

import dev.nitro.aibuild.core.plan.Pos;

/**
 * Rotation about the Y axis, clockwise when viewed from above, matching
 * Minecraft's own {@code Rotation} enum so the two can be mapped one to one.
 */
public enum PlanRotation {
    NONE,
    CLOCKWISE_90,
    CLOCKWISE_180,
    COUNTERCLOCKWISE_90;

    /**
     * The rotation that turns plan north into {@code facing}.
     *
     * <p>Plans are authored as though the build's front faces north, so applying
     * this makes the front face whichever way the player is looking.
     */
    public static PlanRotation toFace(Cardinal facing) {
        return switch (facing) {
            case NORTH -> NONE;
            case EAST -> CLOCKWISE_90;
            case SOUTH -> CLOCKWISE_180;
            case WEST -> COUNTERCLOCKWISE_90;
        };
    }

    /** Rotates a local offset. Y is untouched. */
    public Pos apply(Pos p) {
        return switch (this) {
            case NONE -> p;
            case CLOCKWISE_90 -> new Pos(-p.z(), p.y(), p.x());
            case CLOCKWISE_180 -> new Pos(-p.x(), p.y(), -p.z());
            case COUNTERCLOCKWISE_90 -> new Pos(p.z(), p.y(), -p.x());
        };
    }
}
