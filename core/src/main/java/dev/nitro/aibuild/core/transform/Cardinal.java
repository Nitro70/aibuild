package dev.nitro.aibuild.core.transform;

/** A horizontal compass direction, in Minecraft's own axis convention. */
public enum Cardinal {
    /** -Z */
    NORTH,
    /** +X */
    EAST,
    /** +Z */
    SOUTH,
    /** -X */
    WEST;

    /**
     * The cardinal a yaw points at. Minecraft yaw is degrees where 0 faces south
     * and the value increases clockwise, and it is not normalised, so this folds
     * it into range first.
     */
    public static Cardinal fromYaw(float yaw) {
        int quadrant = Math.floorMod(Math.round(yaw / 90.0f), 4);
        return switch (quadrant) {
            case 0 -> SOUTH;
            case 1 -> WEST;
            case 2 -> NORTH;
            default -> EAST;
        };
    }
}
