package dev.nitro.aibuild.core.plan;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the model's JSON into a {@link BuildPlan}.
 *
 * <p>Structured output makes the shape reliable but not the contents, so every
 * field is still checked. A malformed op is rejected with a message naming its
 * index, because that message is fed back to the model as a repair prompt.
 */
public final class PlanParser {

    private PlanParser() {}

    /** Raised when the model's JSON cannot be read as a plan at all. */
    public static final class MalformedPlanException extends Exception {
        public MalformedPlanException(String message) {
            super(message);
        }

        public MalformedPlanException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static BuildPlan parse(String json) throws MalformedPlanException {
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                throw new MalformedPlanException("the response was not a JSON object");
            }
            root = parsed.getAsJsonObject();
        } catch (MalformedPlanException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new MalformedPlanException("the response was not valid JSON: " + e.getMessage(), e);
        }

        String name = optionalString(root, "name", "build");
        String notes = optionalString(root, "notes", "");

        JsonArray ops = root.getAsJsonArray("ops");
        if (ops == null) {
            throw new MalformedPlanException("the response has no 'ops' array");
        }

        List<Op> parsedOps = new ArrayList<>(ops.size());
        for (int i = 0; i < ops.size(); i++) {
            JsonElement element = ops.get(i);
            if (!element.isJsonObject()) {
                throw new MalformedPlanException("op " + i + " is not an object");
            }
            parsedOps.add(parseOp(element.getAsJsonObject(), i));
        }

        return new BuildPlan(name, notes, parsedOps);
    }

    private static Op parseOp(JsonObject op, int index) throws MalformedPlanException {
        String kind = requireString(op, "op", index).toLowerCase(java.util.Locale.ROOT);
        String rawBlock = requireString(op, "block", index);

        BlockSpec block;
        try {
            block = BlockSpec.parse(rawBlock);
        } catch (IllegalArgumentException e) {
            throw new MalformedPlanException("op " + index + ": " + e.getMessage());
        }

        Pos from = new Pos(
                requireInt(op, "x", index),
                requireInt(op, "y", index),
                requireInt(op, "z", index));

        return switch (kind) {
            case "place" -> new Op.Place(from, block);
            case "fill" -> {
                // A fill missing its second corner is a single block, which is a
                // kinder reading than rejecting an otherwise usable plan.
                Pos to = new Pos(
                        optionalInt(op, "x2", from.x()),
                        optionalInt(op, "y2", from.y()),
                        optionalInt(op, "z2", from.z()));
                yield new Op.Fill(from, to, block);
            }
            default -> throw new MalformedPlanException(
                    "op " + index + ": '" + kind + "' is not a known op, expected 'place' or 'fill'");
        };
    }

    private static String optionalString(JsonObject object, String field, String fallback) {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        return element.getAsString();
    }

    private static String requireString(JsonObject object, String field, int index)
            throws MalformedPlanException {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            throw new MalformedPlanException("op " + index + ": missing '" + field + "'");
        }
        String value = element.getAsString();
        if (value.isBlank()) {
            throw new MalformedPlanException("op " + index + ": '" + field + "' is empty");
        }
        return value;
    }

    private static int requireInt(JsonObject object, String field, int index)
            throws MalformedPlanException {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            throw new MalformedPlanException("op " + index + ": missing coordinate '" + field + "'");
        }
        try {
            return asInt(element);
        } catch (NumberFormatException e) {
            throw new MalformedPlanException(
                    "op " + index + ": coordinate '" + field + "' is not a whole number");
        }
    }

    private static int optionalInt(JsonObject object, String field, int fallback) {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) {
            return fallback;
        }
        try {
            return asInt(element);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Reads a coordinate. Models sometimes emit {@code 3.0} or {@code "3"} even
     * under an integer schema, and both mean the same thing here.
     */
    private static int asInt(JsonElement element) {
        double value = Double.parseDouble(element.getAsString());
        long rounded = Math.round(value);
        if (rounded > Integer.MAX_VALUE || rounded < Integer.MIN_VALUE) {
            throw new NumberFormatException("coordinate out of range");
        }
        return (int) rounded;
    }
}
