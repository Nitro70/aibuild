package dev.nitro.aibuild.fabric.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import dev.nitro.aibuild.core.config.AiBuildConfig;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Reads {@code config/aibuild.json}, writing a commented default on first run.
 *
 * <p>Defaults come from {@code aibuild-defaults.json} inside the jar when it is
 * present, which is how the singleplayer and server jars ship different starting
 * settings from identical code.
 */
public final class ConfigLoader {

    public static final String FILE_NAME = "aibuild.json";
    private static final String BUNDLED_DEFAULTS = "/aibuild-defaults.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ConfigLoader() {}

    public static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /**
     * Loads the config, creating it if missing.
     *
     * <p>A broken config file is never overwritten. The defaults are used for that
     * session and the problem is reported, because silently replacing a file the
     * user hand edited is a good way to lose an API key.
     */
    public static Result load() {
        AiBuildConfig defaults = bundledDefaults();
        Path path = configPath();

        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, GSON.toJson(defaults), StandardCharsets.UTF_8);
                return new Result(defaults.normalise(), "Wrote a default config to " + path);
            } catch (IOException e) {
                return new Result(defaults.normalise(),
                        "Could not write a default config to " + path + ": " + e.getMessage());
            }
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            AiBuildConfig loaded = GSON.fromJson(reader, AiBuildConfig.class);
            if (loaded == null) {
                return new Result(defaults.normalise(), "Config at " + path + " was empty, using defaults.");
            }
            return new Result(loaded.normalise(), null);
        } catch (JsonSyntaxException e) {
            return new Result(defaults.normalise(),
                    "Config at " + path + " is not valid JSON, so defaults are in use for this session. "
                            + "Fix the file and run /aibuild reload. Details: " + e.getMessage());
        } catch (IOException e) {
            return new Result(defaults.normalise(),
                    "Could not read " + path + ": " + e.getMessage());
        }
    }

    /**
     * Writes the config back out, after a provider or model change made in game.
     *
     * @return true when the file was written
     */
    public static boolean save(AiBuildConfig config) {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(config), StandardCharsets.UTF_8);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static AiBuildConfig bundledDefaults() {
        try (InputStream in = ConfigLoader.class.getResourceAsStream(BUNDLED_DEFAULTS)) {
            if (in == null) {
                return new AiBuildConfig();
            }
            AiBuildConfig parsed = GSON.fromJson(
                    new InputStreamReader(in, StandardCharsets.UTF_8), AiBuildConfig.class);
            return Objects.requireNonNullElseGet(parsed, AiBuildConfig::new);
        } catch (IOException | JsonSyntaxException e) {
            // A malformed bundled default is a packaging bug, not a user problem.
            return new AiBuildConfig();
        }
    }

    /**
     * @param config  always usable, whatever went wrong
     * @param warning a message worth surfacing to operators, or null when all is well
     */
    public record Result(AiBuildConfig config, String warning) {}
}
