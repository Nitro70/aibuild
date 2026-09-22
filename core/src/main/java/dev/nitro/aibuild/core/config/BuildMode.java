package dev.nitro.aibuild.core.config;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * How a build reaches the world.
 *
 * <p>The mod can write blocks itself, which needs it installed on the server, or
 * it can send ordinary {@code /setblock} and {@code /fill} commands from the
 * client, which needs only the client mod and operator permission.
 */
public enum BuildMode {

    /**
     * Direct when the server has the mod, commands when it does not. Singleplayer
     * always counts as having it, since the integrated server loads the same jar.
     */
    AUTO("auto", "Auto"),

    /**
     * The server side mod writes the blocks. Best results: redstone stays quiet
     * until the whole build is down. Needs the mod on the server.
     */
    DIRECT("direct", "Direct (server mod)"),

    /**
     * The client sends /setblock and /fill. Works on a server with no mod at all,
     * as long as you are an operator. Redstone may react while it builds.
     */
    COMMANDS("commands", "Commands (/setblock)");

    private final String id;
    private final String displayName;

    BuildMode(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public static Optional<BuildMode> byId(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String needle = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(mode -> mode.id.equals(needle)).findFirst();
    }
}
