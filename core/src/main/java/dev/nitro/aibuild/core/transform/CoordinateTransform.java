package dev.nitro.aibuild.core.transform;

import dev.nitro.aibuild.core.plan.Pos;

/**
 * Maps a plan's local coordinates into the world.
 *
 * <p>The model only ever sees local coordinates with the origin at 0,0,0. Keeping
 * world coordinates out of the prompt means a plan is reusable anywhere, the model
 * has less to track, and rotation costs nothing.
 */
public record CoordinateTransform(Pos origin, PlanRotation rotation) {

    public static CoordinateTransform at(Pos origin) {
        return new CoordinateTransform(origin, PlanRotation.NONE);
    }

    public Pos toWorld(Pos local) {
        return rotation.apply(local).plus(origin);
    }
}
