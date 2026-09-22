package dev.nitro.aibuild.fabric.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.nitro.aibuild.core.BuildPipeline;
import dev.nitro.aibuild.core.config.AiBuildConfig;
import dev.nitro.aibuild.core.config.ProviderSettings;
import dev.nitro.aibuild.core.llm.LlmClient;
import dev.nitro.aibuild.core.llm.LlmClientFactory;
import dev.nitro.aibuild.core.llm.LlmException;
import dev.nitro.aibuild.core.llm.Provider;
import dev.nitro.aibuild.core.plan.Pos;
import dev.nitro.aibuild.core.transform.Cardinal;
import dev.nitro.aibuild.core.transform.CoordinateTransform;
import dev.nitro.aibuild.core.transform.PlanRotation;
import dev.nitro.aibuild.fabric.AiBuildMod;
import dev.nitro.aibuild.fabric.build.ActiveBuild;
import dev.nitro.aibuild.fabric.config.ConfigLoader;
import dev.nitro.aibuild.fabric.undo.BuildSnapshot;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.util.List;

/** Everything {@code /aibuild} can do. */
public final class AiBuildCommand {

    /**
     * A subcommand only the server side registers.
     *
     * <p>Fabric merges client commands into the command tree the server sends, so
     * seeing an {@code aibuild} node on the client proves nothing: it may be the
     * client's own. This child is how the client tells that the server has the mod.
     */
    public static final String SERVER_MARKER = "serverinfo";

    private AiBuildCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("aibuild")
                .requires(AiBuildCommand::allowed)
                .then(Commands.literal(SERVER_MARKER).executes(AiBuildCommand::serverInfo))
                .then(Commands.literal("undo").executes(AiBuildCommand::undo))
                .then(Commands.literal("cancel").executes(AiBuildCommand::cancel))
                .then(Commands.literal("status").executes(AiBuildCommand::status))
                .then(Commands.literal("reload").executes(AiBuildCommand::reload))
                .then(Commands.literal("models").executes(AiBuildCommand::models))
                .then(Commands.literal("provider")
                        .executes(AiBuildCommand::listProviders)
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> setProvider(ctx, StringArgumentType.getString(ctx, "id")))))
                .then(Commands.literal("model")
                        .then(Commands.argument("id", StringArgumentType.greedyString())
                                .executes(ctx -> setModel(ctx, StringArgumentType.getString(ctx, "id")))))
                .then(Commands.argument("prompt", StringArgumentType.greedyString())
                        .executes(ctx -> build(ctx, StringArgumentType.getString(ctx, "prompt"))))
                .executes(AiBuildCommand::usage));
    }

    private static boolean allowed(CommandSourceStack source) {
        if (!AiBuildMod.config().requireOperator) {
            return true;
        }
        return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    // ------------------------------------------------------------------ build

    private static int build(CommandContext<CommandSourceStack> ctx, String prompt)
            throws CommandSyntaxException {

        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        AiBuildConfig config = AiBuildMod.config();

        if (prompt.isBlank()) {
            source.sendFailure(Component.literal("Say what to build, e.g. /aibuild a 2x2 piston door"));
            return 0;
        }
        if (AiBuildMod.scheduler().isBuilding(player.getUUID())) {
            source.sendFailure(Component.literal(
                    "A build is already running. Wait for it, or use /aibuild cancel."));
            return 0;
        }

        long wait = AiBuildMod.cooldownRemaining(player.getUUID());
        if (wait > 0) {
            source.sendFailure(Component.literal("Wait " + wait + " more seconds before building again."));
            return 0;
        }

        // Work the origin out now, on the server thread, so the build lands where
        // the player stood when they asked rather than wherever they wander to.
        ServerLevel level = source.getLevel();
        Cardinal facing = Cardinal.fromYaw(player.getYRot());
        BlockPos base = player.blockPosition();
        Pos origin = offsetFrom(base, facing, config.originOffset);
        PlanRotation rotation = config.rotateToPlayer ? PlanRotation.toFace(facing) : PlanRotation.NONE;
        CoordinateTransform transform = new CoordinateTransform(origin, rotation);

        MinecraftServer server = source.getServer();
        AiBuildMod.markBuilt(player.getUUID());

        source.sendSuccess(() -> Component.literal("Asking " + config.activeProvider().displayName()
                + " for: " + prompt).withStyle(ChatFormatting.GRAY), false);

        AiBuildMod.executor().submit(() -> {
            try {
                LlmClient client = LlmClientFactory.create(config);
                BuildPipeline pipeline = new BuildPipeline(client, AiBuildMod.registry(), config);
                BuildPipeline.Result result = pipeline.run(prompt);

                // Back to the server thread: nothing may touch the world off it.
                server.execute(() -> {
                    ServerPlayer target = server.getPlayerList().getPlayer(player.getUUID());
                    if (target == null) {
                        return;
                    }
                    if (!result.plan().notes().isBlank()) {
                        target.sendSystemMessage(Component.literal(result.plan().notes())
                                .withStyle(ChatFormatting.GRAY));
                    }
                    if (result.repaired()) {
                        target.sendSystemMessage(Component.literal(
                                        "(it had to fix its first attempt)")
                                .withStyle(ChatFormatting.DARK_GRAY));
                    }
                    AiBuildMod.scheduler().start(new ActiveBuild(
                            player.getUUID(), level, result.plan().name(), result.placements(),
                            transform, config.flipRepeaterFacing,
                            new BuildSnapshot(result.plan().name(), level.dimension())));
                });

            } catch (BuildPipeline.BuildFailedException
                     | LlmClientFactory.NotConfiguredException e) {
                sendLater(server, player, e.getMessage());
            } catch (RuntimeException e) {
                AiBuildMod.LOGGER.error("Unexpected failure building '{}'", prompt, e);
                sendLater(server, player, "Something went wrong: " + e);
            }
        });
        return 1;
    }

    private static Pos offsetFrom(BlockPos base, Cardinal facing, int distance) {
        return switch (facing) {
            case NORTH -> new Pos(base.getX(), base.getY(), base.getZ() - distance);
            case SOUTH -> new Pos(base.getX(), base.getY(), base.getZ() + distance);
            case EAST -> new Pos(base.getX() + distance, base.getY(), base.getZ());
            case WEST -> new Pos(base.getX() - distance, base.getY(), base.getZ());
        };
    }

    // ------------------------------------------------------------- housekeeping

    private static int undo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();

        BuildSnapshot restored = AiBuildMod.undoStore().undo(player.getUUID(), source.getLevel());
        if (restored == null) {
            source.sendFailure(Component.literal("Nothing to undo."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Undid " + restored.name()
                + " (" + restored.size() + " blocks).").withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int cancel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        if (!AiBuildMod.scheduler().cancel(player.getUUID())) {
            source.sendFailure(Component.literal("No build is running."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Stopping the build."), false);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        String warning = AiBuildMod.reloadConfig();
        if (warning != null) {
            ctx.getSource().sendFailure(Component.literal(warning));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Reloaded "
                + ConfigLoader.configPath()).withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        AiBuildConfig config = AiBuildMod.config();
        Provider provider = config.activeProvider();
        ProviderSettings settings = config.settingsFor(provider);
        boolean hasKey = !LlmClientFactory.resolveApiKey(provider, settings).isBlank();

        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("Provider: " + provider.displayName())
                .withStyle(ChatFormatting.AQUA), false);
        source.sendSuccess(() -> Component.literal("Model: "
                + settings.modelOrDefault(provider.defaultModel())), false);
        source.sendSuccess(() -> Component.literal("API key: " + (provider.requiresApiKey()
                ? (hasKey ? "set" : "MISSING")
                : "not needed")).withStyle(
                provider.requiresApiKey() && !hasKey ? ChatFormatting.RED : ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("Limits: " + config.maxBlocks + " blocks, radius "
                + config.maxRadius + ", height " + config.maxHeight)
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("Config: " + ConfigLoader.configPath())
                .withStyle(ChatFormatting.DARK_GRAY), false);
        return 1;
    }

    // ---------------------------------------------------------------- provider

    private static int listProviders(CommandContext<CommandSourceStack> ctx) {
        AiBuildConfig config = AiBuildMod.config();
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("Providers (/aibuild provider <id>):")
                .withStyle(ChatFormatting.AQUA), false);

        for (Provider provider : Provider.values()) {
            ProviderSettings settings = config.settingsFor(provider);
            boolean ready = !provider.requiresApiKey()
                    || !LlmClientFactory.resolveApiKey(provider, settings).isBlank();
            boolean selected = provider == config.activeProvider();

            String line = (selected ? " > " : "   ") + provider.id() + "  -  " + provider.displayName()
                    + (provider.requiresApiKey() ? (ready ? "  [key set]" : "  [needs key]") : "  [no key needed]");
            source.sendSuccess(() -> Component.literal(line).withStyle(selected
                    ? ChatFormatting.GREEN
                    : (ready ? ChatFormatting.WHITE : ChatFormatting.GRAY)), false);
        }
        return 1;
    }

    private static int setProvider(CommandContext<CommandSourceStack> ctx, String id) {
        CommandSourceStack source = ctx.getSource();
        Provider provider = Provider.byId(id).orElse(null);
        if (provider == null) {
            source.sendFailure(Component.literal("Unknown provider '" + id + "'. Options: "
                    + String.join(", ", Provider.allIds())));
            return 0;
        }

        AiBuildConfig config = AiBuildMod.config();
        config.provider = provider.id();
        boolean saved = AiBuildMod.saveConfig();

        ProviderSettings settings = config.settingsFor(provider);
        boolean ready = !provider.requiresApiKey()
                || !LlmClientFactory.resolveApiKey(provider, settings).isBlank();

        source.sendSuccess(() -> Component.literal("Provider set to " + provider.displayName())
                .withStyle(ChatFormatting.GREEN), false);
        if (!ready) {
            // Deliberately not a chat command for the key: chat is written to the
            // server log, and a key in a log is a key that has leaked.
            source.sendFailure(Component.literal("No key yet. Put one in providers."
                    + provider.id() + ".apiKey in " + ConfigLoader.configPath()
                    + (provider.apiKeyEnvVar().isBlank() ? "" : ", or set " + provider.apiKeyEnvVar())
                    + ", then run /aibuild reload."));
        }
        if (!saved) {
            source.sendFailure(Component.literal(
                    "Could not write the config, so this lasts until restart."));
        }
        return 1;
    }

    private static int setModel(CommandContext<CommandSourceStack> ctx, String id) {
        AiBuildConfig config = AiBuildMod.config();
        Provider provider = config.activeProvider();
        config.settingsFor(provider).model = id.trim();
        boolean saved = AiBuildMod.saveConfig();

        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("Model for " + provider.displayName()
                + " set to " + id.trim()).withStyle(ChatFormatting.GREEN), false);
        if (!saved) {
            source.sendFailure(Component.literal(
                    "Could not write the config, so this lasts until restart."));
        }
        return 1;
    }

    private static int models(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        AiBuildConfig config = AiBuildMod.config();
        MinecraftServer server = source.getServer();

        source.sendSuccess(() -> Component.literal("Asking "
                + config.activeProvider().displayName() + " what it has...")
                .withStyle(ChatFormatting.GRAY), false);

        AiBuildMod.executor().submit(() -> {
            try {
                LlmClient client = LlmClientFactory.create(config);
                List<String> ids = client.listModels();
                server.execute(() -> {
                    if (ids.isEmpty()) {
                        source.sendSuccess(() -> Component.literal(
                                "This provider does not list its models. Set one with /aibuild model <id>.")
                                .withStyle(ChatFormatting.GRAY), false);
                        return;
                    }
                    source.sendSuccess(() -> Component.literal(ids.size()
                            + " models (/aibuild model <id>):").withStyle(ChatFormatting.AQUA), false);
                    for (String id : ids) {
                        source.sendSuccess(() -> Component.literal("   " + id), false);
                    }
                });
            } catch (LlmException | LlmClientFactory.NotConfiguredException e) {
                server.execute(() -> source.sendFailure(Component.literal(e.getMessage())));
            }
        });
        return 1;
    }

    private static int serverInfo(CommandContext<CommandSourceStack> ctx) {
        String version = net.fabricmc.loader.api.FabricLoader.getInstance()
                .getModContainer(AiBuildMod.MOD_ID)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        ctx.getSource().sendSuccess(() -> Component.literal("This server runs AIBuild " + version
                + ". Provider: " + AiBuildMod.config().activeProvider().displayName())
                .withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private static int usage(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("/aibuild <what to build>")
                .withStyle(ChatFormatting.AQUA), false);
        source.sendSuccess(() -> Component.literal(
                "  undo, cancel, status, models, provider [id], model <id>, reload")
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    /** Hops back onto the server thread to say something went wrong. */
    private static void sendLater(MinecraftServer server, ServerPlayer player, String message) {
        server.execute(() -> {
            ServerPlayer target = server.getPlayerList().getPlayer(player.getUUID());
            if (target != null) {
                target.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.RED));
            }
        });
    }
}
