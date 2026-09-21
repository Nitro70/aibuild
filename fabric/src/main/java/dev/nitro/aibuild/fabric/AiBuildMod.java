package dev.nitro.aibuild.fabric;

import dev.nitro.aibuild.core.block.BlockRegistry;
import dev.nitro.aibuild.core.config.AiBuildConfig;
import dev.nitro.aibuild.core.prompt.MinecraftVersion;
import dev.nitro.aibuild.fabric.build.BuildScheduler;
import dev.nitro.aibuild.fabric.command.AiBuildCommand;
import dev.nitro.aibuild.fabric.config.ConfigLoader;
import dev.nitro.aibuild.fabric.control.ControlServer;
import dev.nitro.aibuild.fabric.undo.UndoStore;
import dev.nitro.aibuild.fabric.world.FabricBlockRegistry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.SharedConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Mod entry point.
 *
 * <p>Server side only in effect: every message it sends travels over the vanilla
 * protocol, so players joining a server running this need nothing installed. The
 * same jar therefore works in singleplayer, where the integrated server loads it.
 */
public final class AiBuildMod implements ModInitializer {

    public static final String MOD_ID = "aibuild";
    public static final Logger LOGGER = LoggerFactory.getLogger("AIBuild");

    private static AiBuildConfig config = new AiBuildConfig().normalise();
    private static final BlockRegistry REGISTRY = new FabricBlockRegistry();
    private static UndoStore undoStore = new UndoStore(config.undoHistory);
    private static BuildScheduler scheduler = new BuildScheduler(undoStore, config.blocksPerTick);

    /**
     * Model calls block for tens of seconds, so they get their own threads.
     * Daemon threads, so a pending request cannot hold the game open on exit.
     */
    private static ExecutorService executor = newExecutor();

    /** Last build time per player, for the cooldown. */
    private static final Map<UUID, Long> LAST_BUILD = new HashMap<>();

    /** Optional TCP port for driving the mod from outside the game. Off by default. */
    private static final ControlServer CONTROL = new ControlServer();

    @Override
    public void onInitialize() {
        reloadConfig();

        String running = SharedConstants.getCurrentVersion().name();
        if (!MinecraftVersion.TARGET.equals(running)) {
            // The prompt names a version, so a mismatch means the model is being
            // told about the wrong game. Worth saying out loud.
            LOGGER.warn("AIBuild targets Minecraft {} but this server is {}. "
                            + "Block names in prompts may be wrong for this version.",
                    MinecraftVersion.TARGET, running);
        }

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> AiBuildCommand.register(dispatcher));

        ServerTickEvents.END_SERVER_TICK.register(server -> scheduler.tick(server));

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            CONTROL.stop();
            scheduler.clear();
            undoStore.clear();
            executor.shutdownNow();
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (executor.isShutdown()) {
                executor = newExecutor();
            }
            // Started here rather than at init, because it needs a running server
            // to hand world work to.
            String note = CONTROL.start(server, config);
            if (note != null) {
                LOGGER.info(note);
            }
        });

        LOGGER.info("AIBuild ready. Provider: {}. Config: {}",
                config.activeProvider().id(), ConfigLoader.configPath());
    }

    private static ExecutorService newExecutor() {
        return Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "aibuild-request");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Rereads the config from disk. Returns a warning to show, or null. */
    public static String reloadConfig() {
        ConfigLoader.Result result = ConfigLoader.load();
        config = result.config();
        undoStore = new UndoStore(config.undoHistory);
        scheduler.clear();
        scheduler = new BuildScheduler(undoStore, config.blocksPerTick);
        if (result.warning() != null) {
            LOGGER.warn(result.warning());
        }
        return result.warning();
    }

    /** Writes the current config back to disk, after a provider or model change. */
    public static boolean saveConfig() {
        return ConfigLoader.save(config);
    }

    public static AiBuildConfig config() {
        return config;
    }

    public static BlockRegistry registry() {
        return REGISTRY;
    }

    public static BuildScheduler scheduler() {
        return scheduler;
    }

    public static UndoStore undoStore() {
        return undoStore;
    }

    public static ExecutorService executor() {
        return executor;
    }

    public static ControlServer control() {
        return CONTROL;
    }

    /**
     * Seconds still to wait before this player may build again.
     *
     * @return 0 when they may build now
     */
    public static long cooldownRemaining(UUID player) {
        if (config.cooldownSeconds <= 0) {
            return 0;
        }
        Long last = LAST_BUILD.get(player);
        if (last == null) {
            return 0;
        }
        long elapsed = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - last);
        return Math.max(0, config.cooldownSeconds - elapsed);
    }

    public static void markBuilt(UUID player) {
        LAST_BUILD.put(player, System.nanoTime());
    }
}
