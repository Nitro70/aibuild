package dev.nitro.aibuild.core.plan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanParserTest {

    @Test
    void parsesAPlaceOp() throws Exception {
        BuildPlan plan = PlanParser.parse("""
                {"name":"torch","notes":"a torch","ops":[
                  {"op":"place","block":"minecraft:torch","x":1,"y":2,"z":3}
                ]}""");

        assertEquals("torch", plan.name());
        assertEquals("a torch", plan.notes());
        assertEquals(1, plan.ops().size());

        Op.Place place = assertInstanceOf(Op.Place.class, plan.ops().get(0));
        assertEquals(new Pos(1, 2, 3), place.pos());
        assertEquals("minecraft:torch", place.block().id());
    }

    @Test
    void parsesAFillOp() throws Exception {
        BuildPlan plan = PlanParser.parse("""
                {"name":"floor","ops":[
                  {"op":"fill","block":"stone","x":0,"y":0,"z":0,"x2":3,"y2":0,"z2":3}
                ]}""");

        Op.Fill fill = assertInstanceOf(Op.Fill.class, plan.ops().get(0));
        assertEquals(new Pos(0, 0, 0), fill.from());
        assertEquals(new Pos(3, 0, 3), fill.to());
        assertEquals(16, fill.volume());
    }

    /** A fill missing its far corner is a kinder read as a single block than a hard error. */
    @Test
    void fillWithoutSecondCornerIsOneBlock() throws Exception {
        BuildPlan plan = PlanParser.parse("""
                {"name":"x","ops":[{"op":"fill","block":"stone","x":5,"y":6,"z":7}]}""");

        Op.Fill fill = assertInstanceOf(Op.Fill.class, plan.ops().get(0));
        assertEquals(fill.from(), fill.to());
        assertEquals(1, fill.volume());
    }

    /** Models emit 3.0 or "3" even under an integer schema. Both mean three. */
    @Test
    void toleratesNonIntegerCoordinateEncodings() throws Exception {
        BuildPlan plan = PlanParser.parse("""
                {"name":"x","ops":[{"op":"place","block":"stone","x":3.0,"y":"4","z":-2.0}]}""");

        Op.Place place = assertInstanceOf(Op.Place.class, plan.ops().get(0));
        assertEquals(new Pos(3, 4, -2), place.pos());
    }

    @Test
    void opNameIsCaseInsensitive() throws Exception {
        BuildPlan plan = PlanParser.parse("""
                {"name":"x","ops":[{"op":"PLACE","block":"stone","x":0,"y":0,"z":0}]}""");
        assertInstanceOf(Op.Place.class, plan.ops().get(0));
    }

    @Test
    void missingNameFallsBack() throws Exception {
        BuildPlan plan = PlanParser.parse("""
                {"ops":[{"op":"place","block":"stone","x":0,"y":0,"z":0}]}""");
        assertEquals("build", plan.name());
        assertTrue(plan.notes().isEmpty());
    }

    @Test
    void rejectsNonJson() {
        assertThrows(PlanParser.MalformedPlanException.class,
                () -> PlanParser.parse("I would be happy to build that for you!"));
    }

    @Test
    void rejectsMissingOpsArray() {
        assertThrows(PlanParser.MalformedPlanException.class,
                () -> PlanParser.parse("{\"name\":\"x\"}"));
    }

    @Test
    void rejectsUnknownOpKind() {
        PlanParser.MalformedPlanException error = assertThrows(PlanParser.MalformedPlanException.class,
                () -> PlanParser.parse("""
                        {"name":"x","ops":[{"op":"sculpt","block":"stone","x":0,"y":0,"z":0}]}"""));
        // The index is in the message because it is fed back to the model verbatim.
        assertTrue(error.getMessage().contains("op 0"));
    }

    @Test
    void rejectsMissingCoordinate() {
        assertThrows(PlanParser.MalformedPlanException.class,
                () -> PlanParser.parse("""
                        {"name":"x","ops":[{"op":"place","block":"stone","x":0,"y":0}]}"""));
    }

    @Test
    void rejectsMalformedBlockState() {
        assertThrows(PlanParser.MalformedPlanException.class,
                () -> PlanParser.parse("""
                        {"name":"x","ops":[{"op":"place","block":"repeater[facing","x":0,"y":0,"z":0}]}"""));
    }
}
