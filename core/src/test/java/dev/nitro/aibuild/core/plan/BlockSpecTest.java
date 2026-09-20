package dev.nitro.aibuild.core.plan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockSpecTest {

    @Test
    void parsesBareId() {
        BlockSpec spec = BlockSpec.parse("minecraft:stone");
        assertEquals("minecraft:stone", spec.id());
        assertTrue(spec.properties().isEmpty());
    }

    @Test
    void addsMissingNamespace() {
        // The model omits the namespace more often than not.
        assertEquals("minecraft:repeater", BlockSpec.parse("repeater").id());
    }

    @Test
    void keepsNonMinecraftNamespace() {
        assertEquals("create:cogwheel", BlockSpec.parse("create:cogwheel").id());
    }

    @Test
    void parsesProperties() {
        BlockSpec spec = BlockSpec.parse("minecraft:repeater[facing=north,delay=2]");
        assertEquals("minecraft:repeater", spec.id());
        assertEquals("north", spec.properties().get("facing"));
        assertEquals("2", spec.properties().get("delay"));
    }

    @Test
    void toleratesWhitespace() {
        BlockSpec spec = BlockSpec.parse("  minecraft:repeater [ facing = north , delay = 2 ] ");
        assertEquals("minecraft:repeater", spec.id());
        assertEquals("north", spec.properties().get("facing"));
        assertEquals("2", spec.properties().get("delay"));
    }

    @Test
    void lowercasesId() {
        assertEquals("minecraft:stone", BlockSpec.parse("Minecraft:STONE").id());
    }

    @Test
    void handlesEmptyStateBrackets() {
        BlockSpec spec = BlockSpec.parse("minecraft:stone[]");
        assertEquals("minecraft:stone", spec.id());
        assertTrue(spec.properties().isEmpty());
    }

    @Test
    void roundTripsThroughToString() {
        String text = "minecraft:repeater[facing=north,delay=2]";
        assertEquals(text, BlockSpec.parse(text).toString());
    }

    @Test
    void pathStripsNamespace() {
        assertEquals("redstone_wire", BlockSpec.parse("minecraft:redstone_wire").path());
    }

    @Test
    void rejectsUnclosedBracket() {
        assertThrows(IllegalArgumentException.class,
                () -> BlockSpec.parse("minecraft:repeater[facing=north"));
    }

    @Test
    void rejectsPropertyWithoutEquals() {
        assertThrows(IllegalArgumentException.class,
                () -> BlockSpec.parse("minecraft:repeater[facing]"));
    }

    @Test
    void rejectsEmptyInput() {
        assertThrows(IllegalArgumentException.class, () -> BlockSpec.parse("   "));
        assertThrows(IllegalArgumentException.class, () -> BlockSpec.parse(null));
    }

    @Test
    void propertiesAreImmutable() {
        BlockSpec spec = BlockSpec.parse("minecraft:repeater[facing=north]");
        assertThrows(UnsupportedOperationException.class, () -> spec.properties().put("delay", "2"));
    }
}
