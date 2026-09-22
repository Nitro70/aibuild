package dev.nitro.aibuild.fabric.client;

import dev.nitro.aibuild.core.command.CommandBatcher;
import dev.nitro.aibuild.core.command.CommandBatcher.WorldBlock;
import dev.nitro.aibuild.core.plan.Placement;
import dev.nitro.aibuild.core.plan.Pos;
import dev.nitro.aibuild.core.transform.CoordinateTransform;
import dev.nitro.aibuild.fabric.AiBuildMod;
import dev.nitro.aibuild.fabric.world.BlockResolver;
import dev.nitro.aibuild.fabric.world.BlockStates;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Builds by sending /setblock and /fill from the client, for servers that do not
 * have the mod.
 *
 * <p>Everything here runs on the client thread. Commands go out a few per tick
 * rather than all at once, so a large build does not arrive at the server as one
 * enormous burst.
 */
public final class CommandBuildRunner {

    /**
     * Ticks to keep listening after the last command, because the server's replies
     * arrive a little after the commands do and the failure count is only honest
     * once they are in.
     */
    private static final int SETTLE_TICKS = 40;

    private static final int UNDO_LIMIT = 20;

    /** One batch of commands being sent, either a build or an undo of one. */
    private static final class Job {
        final String name;
        final List<String> commands;
        final int blocks;
        final boolean undo;
        int next;
        int settleTicksLeft = -1;
        boolean cancelled;

        Job(String name, List<String> commands, int blocks, boolean undo) {
            this.name = name;
            this.commands = commands;
            this.blocks = blocks;
            this.undo = undo;
        }

        boolean sending() {
            return !cancelled && next < commands.size();
        }
    }

    /** What a build replaced, as the commands that would put it back. */
    private record UndoRecord(String name, List<String> commands, int blocks) {}

    private static Job active;
    private static final Deque<UndoRecord> UNDO = new ArrayDeque<>();

    /** Undo only makes sense in the world it was recorded in, so it is tied to a connection. */
    private static ClientPacketListener boundTo;

    private static int succeeded;
    private static int unchanged;
    private static int failed;
    private static String firstFailure;

    private CommandBuildRunner() {}

    // ------------------------------------------------------------------ state

    public static boolean isBusy() {
        return active != null;
    }

    /** True while replies from our own commands may still be arriving. */
    public static boolean isListening() {
        return active != null;
    }

    public static boolean hasUndo() {
        return !UNDO.isEmpty();
    }

    // -------------------------------------------------------------- feedback

    static void countSuccess() {
        succeeded++;
    }

    static void countUnchanged() {
        unchanged++;
    }

    static void countFailure(String message) {
        failed++;
        if (firstFailure == null) {
            firstFailure = message;
        }
    }

    // --------------------------------------------------------------- starting

    /**
     * Turns a finished plan into commands and starts sending them.
     *
     * <p>What is currently at each position is read from the client's copy of the
     * world before anything is sent, which is what makes the build undoable. The
     * client only knows block states, not container contents, so undoing over a
     * chest restores the chest but not what was in it.
     */
    public static void startBuild(String name, List<Placement> placements, CoordinateTransform transform,
                                  String dimension, boolean flipDiodes) {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (level == null || client.getConnection() == null) {
            return;
        }
        bindTo(client.getConnection());

        List<WorldBlock> forward = new ArrayList<>(placements.size());
        List<WorldBlock> previous = new ArrayList<>(placements.size());
        int skipped = 0;

        for (Placement placement : placements) {
            BlockState state = BlockResolver.resolve(placement.block(), transform.rotation(), flipDiodes);
            if (state == null) {
                skipped++;
                continue;
            }
            Pos world = transform.toWorld(placement.pos());
            BlockState before = level.getBlockState(new BlockPos(world.x(), world.y(), world.z()));

            forward.add(new WorldBlock(world, BlockStates.toCommandString(state)));
            previous.add(new WorldBlock(world, BlockStates.toCommandString(before)));
        }

        // Undo runs backwards, so things that clung to a support come off before it.
        Collections.reverse(previous);

        List<String> commands = CommandBatcher.toCommands(forward, dimension);
        List<String> undoCommands = CommandBatcher.toCommands(previous, dimension);

        // Recorded up front so a cancelled or half failed build can still be undone.
        UNDO.push(new UndoRecord(name, undoCommands, forward.size()));
        while (UNDO.size() > UNDO_LIMIT) {
            UNDO.removeLast();
        }

        begin(new Job(name, commands, forward.size(), false));
        if (skipped > 0) {
            say(Component.literal(skipped + " blocks could not be resolved and were skipped.")
                    .withStyle(ChatFormatting.YELLOW));
        }
    }

    /** @return false when there is nothing to undo */
    public static boolean startUndo() {
        UndoRecord record = UNDO.poll();
        if (record == null) {
            return false;
        }
        begin(new Job(record.name(), record.commands(), record.blocks(), true));
        return true;
    }

    public static boolean cancel() {
        if (active == null || !active.sending()) {
            return false;
        }
        active.cancelled = true;
        return true;
    }

    private static void begin(Job job) {
        active = job;
        succeeded = 0;
        unchanged = 0;
        failed = 0;
        firstFailure = null;
    }

    // ----------------------------------------------------------------- ticking

    public static void tick(Minecraft client) {
        ClientPacketListener connection = client.getConnection();

        // A new connection means a new world. Anything recorded for the old one
        // would now point at somebody else's blocks, so it all has to go.
        if (connection != boundTo) {
            if (boundTo != null) {
                active = null;
                UNDO.clear();
            }
            boundTo = connection;
        }
        if (active == null || connection == null) {
            return;
        }

        if (active.sending()) {
            int budget = AiBuildMod.config().commandsPerTick;
            for (int i = 0; i < budget && active.sending(); i++) {
                // Sent as the raw packet rather than through sendCommand, which would
                // hand it straight back to the client command dispatcher.
                connection.send(new ServerboundChatCommandPacket(active.commands.get(active.next++)));
            }
            if (client.player != null && active.sending()) {
                int percent = Math.round(100f * active.next / Math.max(1, active.commands.size()));
                client.player.sendOverlayMessage(Component.literal(
                        (active.undo ? "Undoing " : "Building ") + active.name + "  " + percent + "%")
                        .withStyle(ChatFormatting.GRAY));
            }
            return;
        }

        if (active.settleTicksLeft < 0) {
            active.settleTicksLeft = SETTLE_TICKS;
        }
        if (active.settleTicksLeft-- > 0) {
            return;
        }

        report(active);
        active = null;
    }

    private static void report(Job job) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendOverlayMessage(Component.empty());
        }

        String what = job.undo ? "Undid " : "Built ";
        StringBuilder text = new StringBuilder(what).append(job.name);

        if (job.cancelled) {
            text = new StringBuilder("Cancelled ").append(job.name).append(" after ")
                    .append(job.next).append(" of ").append(job.commands.size()).append(" commands");
        } else {
            text.append(": ").append(job.blocks).append(" blocks in ")
                    .append(job.commands.size()).append(job.commands.size() == 1 ? " command" : " commands");
        }
        if (failed > 0) {
            text.append(", ").append(failed).append(" failed");
        }
        if (!job.undo) {
            text.append(". /aibuild undo to remove it.");
        } else {
            text.append('.');
        }

        ChatFormatting colour = failed > 0 || job.cancelled ? ChatFormatting.YELLOW : ChatFormatting.GREEN;
        say(Component.literal(text.toString()).withStyle(colour));

        if (failed > 0 && firstFailure != null) {
            say(Component.literal("First failure: " + firstFailure).withStyle(ChatFormatting.GRAY));
        }
    }

    private static void bindTo(ClientPacketListener connection) {
        if (connection != boundTo) {
            UNDO.clear();
            boundTo = connection;
        }
    }

    static void say(Component message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendSystemMessage(message);
        }
    }
}
