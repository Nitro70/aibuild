package dev.nitro.aibuild.core.transform;

import dev.nitro.aibuild.core.plan.Pos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransformTest {

    /** Minecraft yaw: 0 faces south and increases clockwise. */
    @Test
    void yawMapsToCardinal() {
        assertEquals(Cardinal.SOUTH, Cardinal.fromYaw(0));
        assertEquals(Cardinal.WEST, Cardinal.fromYaw(90));
        assertEquals(Cardinal.NORTH, Cardinal.fromYaw(180));
        assertEquals(Cardinal.EAST, Cardinal.fromYaw(270));
    }

    @Test
    void yawIsNotNormalisedByTheGameSoWeFoldIt() {
        assertEquals(Cardinal.EAST, Cardinal.fromYaw(-90));
        assertEquals(Cardinal.SOUTH, Cardinal.fromYaw(720));
        assertEquals(Cardinal.NORTH, Cardinal.fromYaw(-180));
    }

    /**
     * North is -Z and east is +X, so a clockwise quarter turn viewed from above
     * takes north to east.
     */
    @Test
    void clockwise90TakesNorthToEast() {
        Pos north = new Pos(0, 0, -1);
        assertEquals(new Pos(1, 0, 0), PlanRotation.CLOCKWISE_90.apply(north));
    }

    @Test
    void rotationsAgreeWithTheCompass() {
        Pos north = new Pos(0, 0, -1);
        assertEquals(new Pos(0, 0, -1), PlanRotation.NONE.apply(north));
        assertEquals(new Pos(0, 0, 1), PlanRotation.CLOCKWISE_180.apply(north));
        assertEquals(new Pos(-1, 0, 0), PlanRotation.COUNTERCLOCKWISE_90.apply(north));
    }

    @Test
    void rotationLeavesHeightAlone() {
        assertEquals(7, PlanRotation.CLOCKWISE_90.apply(new Pos(3, 7, 5)).y());
    }

    @Test
    void fourQuarterTurnsReturnToStart() {
        Pos start = new Pos(3, 1, -4);
        Pos turned = start;
        for (int i = 0; i < 4; i++) {
            turned = PlanRotation.CLOCKWISE_90.apply(turned);
        }
        assertEquals(start, turned);
    }

    /** Plans are authored facing north, so toFace turns the front toward the player. */
    @Test
    void toFaceTurnsPlanNorthTowardTheTarget() {
        assertEquals(PlanRotation.NONE, PlanRotation.toFace(Cardinal.NORTH));
        assertEquals(PlanRotation.CLOCKWISE_90, PlanRotation.toFace(Cardinal.EAST));
        assertEquals(PlanRotation.CLOCKWISE_180, PlanRotation.toFace(Cardinal.SOUTH));
        assertEquals(PlanRotation.COUNTERCLOCKWISE_90, PlanRotation.toFace(Cardinal.WEST));

        // The front of the build ends up pointing the way the player looks.
        Pos front = new Pos(0, 0, -1);
        assertEquals(new Pos(1, 0, 0), PlanRotation.toFace(Cardinal.EAST).apply(front));
    }

    @Test
    void transformRotatesThenTranslates() {
        CoordinateTransform transform =
                new CoordinateTransform(new Pos(100, 64, 200), PlanRotation.CLOCKWISE_90);
        // (0,0,-1) rotates to (1,0,0), then shifts by the origin.
        assertEquals(new Pos(101, 64, 200), transform.toWorld(new Pos(0, 0, -1)));
    }

    @Test
    void transformWithoutRotationIsPureTranslation() {
        CoordinateTransform transform = CoordinateTransform.at(new Pos(10, 70, -5));
        assertEquals(new Pos(13, 72, -1), transform.toWorld(new Pos(3, 2, 4)));
    }
}
