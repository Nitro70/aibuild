package dev.nitro.aibuild.fabric.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.nitro.aibuild.core.BuildPipeline;
import dev.nitro.aibuild.core.config.AiBuildConfig;
import dev.nitro.aibuild.core.config.BuildMode;
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
import dev.nitro.aibuild.fabric.client.ServerCapabilities.Route;
import dev.nitro.aibuild.fabric.client.screen.AiBuildConfigScreen;
import dev.nitro.aibuild.fabric.config.ConfigLoader;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;

import java.util.List;

/**
 * {@code /aibuild} as a client command, so it exists and autocompletes on any
 * server, including one that has never heard of this mod.
 *
 * <p>Every invocation is routed one of two ways. If the server has the mod (and
 * singleplayer always does), the command is handed to it unchanged, and the
 * server side does the building. Otherwise the client builds with /setblock and
 * /fill, which needs only operator permission.
 *
 * <p>Either way, where the build goes is fixed at the moment the command runs.
 * Walking off while the model thinks does not move it.
 */
public final class ClientBuildCommand {

    private ClientBuildCommand() {}

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        LiteralArgumentBuilder<FabricClientCommandSource> provider = ClientCommands.literal("provider")
                .executes(ClientBuildCommand::provider);
        for (Provider candidate : Provider.values()) {
            provider.then(ClientCommands.literal(candidate.id())
                    .executes(ctx -> setProvider(ctx, candidate)));
        }

        LiteralArgumentBuilder<FabricClientCommandSource> mode = ClientCommands.literal("mode")
                .executes(ClientBuildCommand::showMode);
        for (BuildMode candidate : BuildMode.values()) {
            mode.then(ClientCommands.literal(candidate.id())
                    .executes(ctx -> setMode(ctx, candidate)));
        }

        dispatcher.register(ClientCommands.literal("aibuild")
                .then(ClientCommands.literal("undo").executes(ClientBuildCommand::undo))
                .then(ClientCommands.literal("cancel").executes(ClientBuildCommand::cancel))
                .then(ClientCommands.literal("status").executes(ClientBuildCommand::status))
                .then(ClientCommands.literal("config").executes(ctx -> openSettings()))
                .then(ClientCommands.literal("reload").executes(ClientBuildCommand::reload))
                .then(ClientCommands.literal("models").executes(ClientBuildCommand::models))
                .then(mode)
                .then(provider)
                .then(ClientCommands.literal("model")
                        .then(ClientCommands.argument("id", StringArgumentType.greedyString())
                                .executes(ctx -> setModel(ctx, StringArgumentType.getString(ctx, "id")))))
                .then(ClientCommands.argument("prompt", StringArgumentType.greedyString())
                        .executes(ctx -> build(ctx, StringArgumentType.getString(ctx, "prompt"))))
                .executes(ClientBuildCommand::usage));
    }

    // ----------------------------------------------------------------- routing

    private static ServerCapabilities capabilities() {
        return ServerCapabilities.read(Minecraft.getInstance());
    }

    private static Route route(ServerCapabilities caps) {
        return caps.route(AiBuildMod.config().activeBuildMode());
    }

    /**
     * Passes the command, exactly as typed, to the server side mod.
     *
     * <p>Sent as the raw packet. Going through the normal send path would hand it
     * back to the client command dispatcher, which would route it here again.
     */
    private static int forward(CommandContext<FabricClientCommandSource> ctx) {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null) {
            ctx.getSource().sendError(Component.literal("Not connected to a server."));
            return 0;
        }
        String input = ctx.getInput();
        if (input.startsWith("/")) {
            input = input.substring(1);
        }
        client.getConnection().send(new ServerboundChatCommandPacket(input));
        return 1;
    }

    /** Explains why nothing can build, in terms of what to do about it. */
    private static int unavailable(CommandContext<FabricClientCommandSource> ctx, ServerCapabilities caps) {
        if (!caps.connected()) {
            ctx.getSource().sendError(Component.literal("Join a world first."));
        } else {
            ctx.getSource().sendError(Component.literal(
                    "Direct mode needs AIBuild on the server, and this one does not have it. "
                            + "Use /aibuild mode commands to build with /setblock instead (needs OP)."));
        }
        return 0;
    }

    // ------------------------------------------------------------------- build

    private static int build(CommandContext<FabricClientCommandSource> ctx, String prompt) {
        ServerCapabilities caps = capabilities();

        // The server's marker subcommand is deliberately not registered here (that
        // is what makes it a marker), so without this it would parse as a prompt and
        // the model would be asked to build something called "serverinfo".
        if (prompt.trim().equalsIgnoreCase(dev.nitro.aibuild.fabric.command.AiBuildCommand.SERVER_MARKER)) {
            if (caps.serverHasMod()) {
                return forward(ctx);
            }
            ctx.getSource().sendError(Component.literal("This server does not run AIBuild."));
            return 0;
        }

        return switch (route(caps)) {
            case SERVER -> forward(ctx);
            case NONE -> unavailable(ctx, caps);
            case COMMANDS -> buildWithCommands(ctx, prompt, caps);
        };
    }

    private static int buildWithCommands(CommandContext<FabricClientCommandSource> ctx, String prompt,
                                         ServerCapabilities caps) {
        FabricClientCommandSource source = ctx.getSource();
        AiBuildConfig config = AiBuildMod.config();

        if (prompt.isBlank()) {
            source.sendError(Component.literal("Say what to build, e.g. /aibuild a small stone hut"));
            return 0;
        }

        // Every check that could fail happens before the model is asked, so a
        // build that could never be placed does not cost anything.
        if (!caps.canUseCommands()) {
            source.sendError(Component.literal("Commands mode needs permission to run "
                    + caps.missingForCommands() + ", which on a server means being an operator."
                    + (caps.serverHasMod() ? " This server has AIBuild, so /aibuild mode auto would work." : "")));
            return 0;
        }
        if (CommandBuildRunner.isBusy()) {
            source.sendError(Component.literal("A build is already running. Wait for it, or /aibuild cancel."));
            return 0;
        }

        LlmClient llm;
        try {
            llm = LlmClientFactory.create(config);
        } catch (LlmClientFactory.NotConfiguredException e) {
            source.sendError(Component.literal(e.getMessage() + " Or open /aibuild config."));
            return 0;
        }

        // Pinned now, on the client thread, so the build lands where the player
        // stood when they asked, in the dimension they were in.
        LocalPlayer player = source.getPlayer();
        Cardinal facing = Cardinal.fromYaw(player.getYRot());
        BlockPos base = player.blockPosition();
        Pos origin = offsetFrom(base, facing, config.originOffset);
        PlanRotation rotation = config.rotateToPlayer ? PlanRotation.toFace(facing) : PlanRotation.NONE;
        CoordinateTransform transform = new CoordinateTransform(origin, rotation);
        String dimension = source.getLevel().dimension().identifier().toString();
        boolean flip = config.flipRepeaterFacing;

        source.sendFeedback(Component.literal("Asking " + config.activeProvider().displayName()
                + " for: " + prompt).withStyle(ChatFormatting.GRAY));

        Minecraft client = Minecraft.getInstance();
        AiBuildMod.executor().submit(() -> {
            try {
                BuildPipeline.Result result = new BuildPipeline(llm, AiBuildMod.registry(), config).run(prompt);
                client.execute(() -> {
                    if (!result.plan().notes().isBlank()) {
                        CommandBuildRunner.say(Component.literal(result.plan().notes())
                                .withStyle(ChatFormatting.GRAY));
                    }
                    if (CommandBuildRunner.isBusy()) {
                        CommandBuildRunner.say(Component.literal(
                                "Another build started while this one was being planned, so it was dropped.")
                                .withStyle(ChatFormatting.YELLOW));
                        return;
                    }
                    CommandBuildRunner.startBuild(
                            result.plan().name(), result.placements(), transform, dimension, flip);
                });
            } catch (BuildPipeline.BuildFailedException e) {
                client.execute(() -> CommandBuildRunner.say(
                        Component.literal(e.getMessage()).withStyle(ChatFormatting.RED)));
            } catch (RuntimeException e) {
                AiBuildMod.LOGGER.error("Unexpected failure building '{}'", prompt, e);
                client.execute(() -> CommandBuildRunner.say(
                        Component.literal("Something went wrong: " + e).withStyle(ChatFormatting.RED)));
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

    // ---------------------------------------------------------- undo / cancel

    /**
     * Undo follows the current route, so it undoes builds made the same way. A
     * build made by the server is undone by the server; one made with commands is
     * undone here, with commands.
     */
    private static int undo(CommandContext<FabricClientCommandSource> ctx) {
        ServerCapabilities caps = capabilities();
        Route route = route(caps);
        if (route == Route.SERVER) {
            return forward(ctx);
        }
        if (route == Route.NONE) {
            return unavailable(ctx, caps);
        }
        if (CommandBuildRunner.isBusy()) {
            ctx.getSource().sendError(Component.literal("Wait for the current build to finish, or /aibuild cancel."));
            return 0;
        }
        if (!caps.canUseCommands()) {
            ctx.getSource().sendError(Component.literal("Undo needs " + caps.missingForCommands() + " too."));
            return 0;
        }
        if (!CommandBuildRunner.startUndo()) {
            ctx.getSource().sendError(Component.literal("Nothing to undo."));
            return 0;
        }
        return 1;
    }

    private static int cancel(CommandContext<FabricClientCommandSource> ctx) {
        if (CommandBuildRunner.cancel()) {
            ctx.getSource().sendFeedback(Component.literal("Stopping the build."));
            return 1;
        }
        ServerCapabilities caps = capabilities();
        if (route(caps) == Route.SERVER) {
            return forward(ctx);
        }
        ctx.getSource().sendError(Component.literal("No build is running."));
        return 0;
    }

    // ------------------------------------------------------------------ status

    private static int status(CommandContext<FabricClientCommandSource> ctx) {
        FabricClientCommandSource source = ctx.getSource();
        AiBuildConfig config = AiBuildMod.config();
        ServerCapabilities caps = capabilities();
        Route route = route(caps);

        source.sendFeedback(Component.literal("Mode: " + config.activeBuildMode().displayName()
                + "  ->  " + describe(route)).withStyle(ChatFormatting.AQUA));
        source.sendFeedback(Component.literal("Server has AIBuild: " + yesNo(caps.serverHasMod()))
                .withStyle(ChatFormatting.GRAY));
        source.sendFeedback(Component.literal("Can use /setblock " + tick(caps.canSetblock())
                + "  /fill " + tick(caps.canFill()) + "  /execute " + tick(caps.canExecute()))
                .withStyle(caps.canUseCommands() ? ChatFormatting.GRAY : ChatFormatting.YELLOW));

        if (route == Route.SERVER) {
            source.sendFeedback(Component.literal(
                    "Builds run on the server, so the server's provider and key are used. "
                            + "/aibuild serverinfo shows them.").withStyle(ChatFormatting.DARK_GRAY));
            return 1;
        }

        Provider provider = config.activeProvider();
        ProviderSettings settings = config.settingsFor(provider);
        boolean hasKey = !LlmClientFactory.resolveApiKey(provider, settings).isBlank();
        source.sendFeedback(Component.literal("Provider: " + provider.displayName()
                + "  Model: " + settings.modelOrDefault(provider.defaultModel())));
        source.sendFeedback(Component.literal("API key: " + (provider.requiresApiKey()
                ? (hasKey ? "set" : "MISSING") : "not needed"))
                .withStyle(provider.requiresApiKey() && !hasKey ? ChatFormatting.RED : ChatFormatting.GRAY));
        source.sendFeedback(Component.literal("Sending " + config.commandsPerTick + " commands per tick")
                .withStyle(ChatFormatting.DARK_GRAY));
        return 1;
    }

    private static String describe(Route route) {
        return switch (route) {
            case SERVER -> "built by the server";
            case COMMANDS -> "built with /setblock and /fill";
            case NONE -> "nothing can build here";
        };
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    private static String tick(boolean value) {
        return value ? "yes" : "NO";
    }

    // -------------------------------------------------------------------- mode

    private static int showMode(CommandContext<FabricClientCommandSource> ctx) {
        BuildMode current = AiBuildMod.config().activeBuildMode();
        ctx.getSource().sendFeedback(Component.literal("Build mode: " + current.displayName()
                + ". Options: auto, direct, commands.").withStyle(ChatFormatting.AQUA));
        return 1;
    }

    private static int setMode(CommandContext<FabricClientCommandSource> ctx, BuildMode mode) {
        AiBuildMod.config().buildMode = mode.id();
        boolean saved = AiBuildMod.saveConfig();
        ctx.getSource().sendFeedback(Component.literal("Build mode set to " + mode.displayName() + ".")
                .withStyle(ChatFormatting.GREEN));
        if (!saved) {
            ctx.getSource().sendError(Component.literal("Could not write the config, so this lasts until restart."));
        }
        return 1;
    }

    // ------------------------------------------------- provider, model, reload

    /**
     * These change whichever config will actually be used. With the server
     * building, that is the server's, so they are passed on. With commands, it is
     * this machine's.
     */
    private static boolean serverOwnsConfig() {
        return route(capabilities()) == Route.SERVER;
    }

    private static int provider(CommandContext<FabricClientCommandSource> ctx) {
        if (serverOwnsConfig()) {
            return forward(ctx);
        }
        AiBuildConfig config = AiBuildMod.config();
        ctx.getSource().sendFeedback(Component.literal("Providers (/aibuild provider <id>):")
                .withStyle(ChatFormatting.AQUA));
        for (Provider candidate : Provider.values()) {
            boolean ready = !candidate.requiresApiKey()
                    || !LlmClientFactory.resolveApiKey(candidate, config.settingsFor(candidate)).isBlank();
            boolean selected = candidate == config.activeProvider();
            ctx.getSource().sendFeedback(Component.literal((selected ? " > " : "   ") + candidate.id()
                    + "  " + candidate.displayName()
                    + (candidate.requiresApiKey() ? (ready ? "  [key set]" : "  [needs key]") : "  [no key]"))
                    .withStyle(selected ? ChatFormatting.GREEN : ready ? ChatFormatting.WHITE : ChatFormatting.GRAY));
        }
        return 1;
    }

    private static int setProvider(CommandContext<FabricClientCommandSource> ctx, Provider provider) {
        if (serverOwnsConfig()) {
            return forward(ctx);
        }
        AiBuildConfig config = AiBuildMod.config();
        config.provider = provider.id();
        AiBuildMod.saveConfig();
        ctx.getSource().sendFeedback(Component.literal("Provider set to " + provider.displayName() + ".")
                .withStyle(ChatFormatting.GREEN));
        if (provider.requiresApiKey()
                && LlmClientFactory.resolveApiKey(provider, config.settingsFor(provider)).isBlank()) {
            ctx.getSource().sendError(Component.literal("It needs a key. Paste one in /aibuild config."));
        }
        return 1;
    }

    private static int setModel(CommandContext<FabricClientCommandSource> ctx, String id) {
        if (serverOwnsConfig()) {
            return forward(ctx);
        }
        AiBuildConfig config = AiBuildMod.config();
        config.settingsFor(config.activeProvider()).model = id.trim();
        AiBuildMod.saveConfig();
        ctx.getSource().sendFeedback(Component.literal("Model set to " + id.trim() + ".")
                .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int models(CommandContext<FabricClientCommandSource> ctx) {
        if (serverOwnsConfig()) {
            return forward(ctx);
        }
        AiBuildConfig config = AiBuildMod.config();
        Minecraft client = Minecraft.getInstance();
        ctx.getSource().sendFeedback(Component.literal("Asking " + config.activeProvider().displayName()
                + " what it has...").withStyle(ChatFormatting.GRAY));

        AiBuildMod.executor().submit(() -> {
            try {
                List<String> ids = LlmClientFactory.create(config).listModels();
                client.execute(() -> {
                    if (ids.isEmpty()) {
                        CommandBuildRunner.say(Component.literal(
                                "This provider does not list its models. Set one with /aibuild model <id>.")
                                .withStyle(ChatFormatting.GRAY));
                        return;
                    }
                    CommandBuildRunner.say(Component.literal(ids.size() + " models (/aibuild model <id>):")
                            .withStyle(ChatFormatting.AQUA));
                    for (String id : ids) {
                        CommandBuildRunner.say(Component.literal("   " + id));
                    }
                });
            } catch (LlmException | LlmClientFactory.NotConfiguredException e) {
                client.execute(() -> CommandBuildRunner.say(
                        Component.literal(e.getMessage()).withStyle(ChatFormatting.RED)));
            }
        });
        return 1;
    }

    private static int reload(CommandContext<FabricClientCommandSource> ctx) {
        if (serverOwnsConfig() && !Minecraft.getInstance().hasSingleplayerServer()) {
            return forward(ctx);
        }
        String warning = AiBuildMod.reloadConfig();
        if (warning != null) {
            ctx.getSource().sendError(Component.literal(warning));
            return 0;
        }
        ctx.getSource().sendFeedback(Component.literal("Reloaded " + ConfigLoader.configPath())
                .withStyle(ChatFormatting.GREEN));
        return 1;
    }

    // ------------------------------------------------------------------ misc

    static int openSettings() {
        Minecraft client = Minecraft.getInstance();
        // Deferred, because the chat screen is still closing when this runs and
        // setting a screen underneath it would be undone immediately.
        client.execute(() -> client.setScreenAndShow(new AiBuildConfigScreen(null)));
        return 1;
    }

    private static int usage(CommandContext<FabricClientCommandSource> ctx) {
        ctx.getSource().sendFeedback(Component.literal("/aibuild <what to build>").withStyle(ChatFormatting.AQUA));
        ctx.getSource().sendFeedback(Component.literal(
                "  undo, cancel, status, config, mode, provider, model, models, reload")
                .withStyle(ChatFormatting.GRAY));
        return 1;
    }
}
