package dev.nitro.aibuild.core.plan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A block identifier plus its block state properties, as the model writes them:
 * {@code minecraft:repeater[facing=north,delay=2]}.
 *
 * <p>The namespace is optional in the model's output and is normalised to
 * {@code minecraft:} here, because the model omits it more often than not.
 */
public record BlockSpec(String id, Map<String, String> properties) {

    public BlockSpec {
        properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    public static BlockSpec of(String id) {
        return new BlockSpec(id, Map.of());
    }

    /**
     * Parses the textual form. Whitespace around names and values is tolerated,
     * since the model is not consistent about it.
     *
     * @throws IllegalArgumentException if the text is not a well formed block spec
     */
    public static BlockSpec parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("block is null");
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("block is empty");
        }

        String idPart = text;
        Map<String, String> props = new LinkedHashMap<>();

        int open = text.indexOf('[');
        if (open >= 0) {
            if (!text.endsWith("]")) {
                throw new IllegalArgumentException("block state is missing its closing bracket: " + raw);
            }
            idPart = text.substring(0, open).trim();
            String body = text.substring(open + 1, text.length() - 1).trim();
            if (!body.isEmpty()) {
                for (String pair : body.split(",")) {
                    int eq = pair.indexOf('=');
                    if (eq < 0) {
                        throw new IllegalArgumentException(
                                "block state property '" + pair.trim() + "' is missing '=' in: " + raw);
                    }
                    String name = pair.substring(0, eq).trim();
                    String value = pair.substring(eq + 1).trim();
                    if (name.isEmpty() || value.isEmpty()) {
                        throw new IllegalArgumentException("empty block state property in: " + raw);
                    }
                    props.put(name, value);
                }
            }
        }

        return new BlockSpec(normaliseId(idPart, raw), props);
    }

    private static String normaliseId(String idPart, String raw) {
        String id = idPart.trim().toLowerCase(java.util.Locale.ROOT);
        if (id.isEmpty()) {
            throw new IllegalArgumentException("block id is empty in: " + raw);
        }
        if (id.indexOf(':') < 0) {
            return "minecraft:" + id;
        }
        return id;
    }

    /** The part of the id after the namespace, e.g. {@code repeater}. */
    public String path() {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    @Override
    public String toString() {
        if (properties.isEmpty()) {
            return id;
        }
        StringBuilder sb = new StringBuilder(id).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : properties.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.append(']').toString();
    }
}
