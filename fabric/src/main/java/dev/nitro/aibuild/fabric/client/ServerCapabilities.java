package dev.nitro.aibuild.fabric.client;

import com.mojang.brigadier.tree.CommandNode;
import dev.nitro.aibuild.core.config.BuildMode;
import dev.nitro.aibuild.fabric.command.AiBuildCommand;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

/**
 * What the current server lets this player do, read from the command tree it
 * already sent.
 *
 * <p>A server only sends the commands a player is allowed to run, so the tree is
 * an exact permission check that costs nothing and sends nothing. That is what
 * lets the mod refuse before spending any API credit, rather than after the model
 * has already been paid for a plan that then cannot be placed.
 */
public record ServerCapabilities(boolean serverHasMod,
                                 boolean canSetblock,
                                 boolean canFill,
                                 boolean canExecute,
                                 boolean connected) {

    /** Where a build should go, given the chosen mode and what the server allows. */
    public enum Route {
        /** Hand the command to the server side mod. */
        SERVER,
        /** Build from here with /setblock and /fill. */
        COMMANDS,
        /** Nothing can build right now. */
        NONE
    }

    public static ServerCapabilities read(Minecraft client) {
        ClientPacketListener connection = client.getConnection();
        if (connection == null) {
            return new ServerCapabilities(false, false, false, false, false);
        }
        CommandNode<?> root = connection.getCommands().getRoot();

        CommandNode<?> aibuild = root.getChild("aibuild");
        boolean mod = aibuild != null && aibuild.getChild(AiBuildCommand.SERVER_MARKER) != null;

        return new ServerCapabilities(
                mod,
                root.getChild("setblock") != null,
                root.getChild("fill") != null,
                root.getChild("execute") != null,
                true);
    }

    /** Commands mode needs all three: fill for runs, setblock for singles, execute to pin the dimension. */
    public boolean canUseCommands() {
        return canSetblock && canFill && canExecute;
    }

    public Route route(BuildMode mode) {
        if (!connected) {
            return Route.NONE;
        }
        return switch (mode) {
            case AUTO -> serverHasMod ? Route.SERVER : Route.COMMANDS;
            case DIRECT -> serverHasMod ? Route.SERVER : Route.NONE;
            case COMMANDS -> Route.COMMANDS;
        };
    }

    /** What is missing for commands mode, worded for the player. */
    public String missingForCommands() {
        StringBuilder missing = new StringBuilder();
        if (!canSetblock) {
            missing.append("/setblock");
        }
        if (!canFill) {
            missing.append(missing.isEmpty() ? "" : ", ").append("/fill");
        }
        if (!canExecute) {
            missing.append(missing.isEmpty() ? "" : ", ").append("/execute");
        }
        return missing.toString();
    }
}
