package dev.nitro.aibuild.core.prompt;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * The build plan schema, in the two dialects the providers want.
 *
 * <p>Gemini's {@code responseSchema} takes an OpenAPI subset with upper case type
 * names. OpenAI and Anthropic take ordinary JSON Schema with lower case ones.
 * Same shape either way, so it is built once and rendered twice.
 */
public final class PlanSchema {

    public static final String NAME = "build_plan";

    private PlanSchema() {}

    /** Gemini's responseSchema dialect. */
    public static JsonObject gemini() {
        return build(true);
    }

    /** Standard JSON Schema, for OpenAI response_format and Anthropic tool input. */
    public static JsonObject jsonSchema() {
        return build(false);
    }

    private static JsonObject build(boolean upperCaseTypes) {
        String object = upperCaseTypes ? "OBJECT" : "object";
        String array = upperCaseTypes ? "ARRAY" : "array";
        String string = upperCaseTypes ? "STRING" : "string";
        String integer = upperCaseTypes ? "INTEGER" : "integer";

        JsonObject opProps = new JsonObject();
        opProps.add("op", enumField(string,
                "Either 'place' for a single block or 'fill' for a cuboid.", "place", "fill"));
        opProps.add("block", field(string,
                "Block id with optional state, e.g. 'minecraft:repeater[facing=north,delay=2]'."));
        opProps.add("x", field(integer, "X of the block, or of the first corner for a fill."));
        opProps.add("y", field(integer, "Y of the block, or of the first corner for a fill."));
        opProps.add("z", field(integer, "Z of the block, or of the first corner for a fill."));
        opProps.add("x2", field(integer, "X of the opposite corner. Only for 'fill'."));
        opProps.add("y2", field(integer, "Y of the opposite corner. Only for 'fill'."));
        opProps.add("z2", field(integer, "Z of the opposite corner. Only for 'fill'."));

        JsonObject op = new JsonObject();
        op.addProperty("type", object);
        op.add("properties", opProps);
        op.add("required", strings("op", "block", "x", "y", "z"));
        if (upperCaseTypes) {
            op.add("propertyOrdering", strings("op", "block", "x", "y", "z", "x2", "y2", "z2"));
        }

        JsonObject ops = new JsonObject();
        ops.addProperty("type", array);
        ops.addProperty("description", "Every block to place, in any order.");
        ops.add("items", op);

        JsonObject rootProps = new JsonObject();
        rootProps.add("name", field(string, "Short name for the build, at most five words."));
        rootProps.add("notes", field(string,
                "One or two sentences on how it works and how to trigger it. Plain text."));
        rootProps.add("ops", ops);

        JsonObject root = new JsonObject();
        root.addProperty("type", object);
        root.add("properties", rootProps);
        root.add("required", strings("name", "notes", "ops"));
        if (upperCaseTypes) {
            root.add("propertyOrdering", strings("name", "notes", "ops"));
        }
        return root;
    }

    private static JsonObject field(String type, String description) {
        JsonObject field = new JsonObject();
        field.addProperty("type", type);
        field.addProperty("description", description);
        return field;
    }

    private static JsonObject enumField(String type, String description, String... values) {
        JsonObject field = field(type, description);
        field.add("enum", strings(values));
        return field;
    }

    private static JsonArray strings(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }
}
