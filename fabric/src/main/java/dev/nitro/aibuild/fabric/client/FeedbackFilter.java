package dev.nitro.aibuild.fabric.client;

import dev.nitro.aibuild.fabric.AiBuildMod;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.Set;

/**
 * Hides the chat line every /setblock and /fill prints while a commands mode
 * build is running, and counts them instead.
 *
 * <p>Matching is on the message's translation key rather than its text, so it
 * works whatever language the server or client is in. Only the handful of keys
 * those commands produce are touched, and only while a build is running, so
 * ordinary chat is never affected.
 */
public final class FeedbackFilter {

    private static final Set<String> SUCCESS = Set.of(
            "commands.setblock.success",
            "commands.fill.success");

    /** Printed when the block was already exactly that, which is not a real failure. */
    private static final Set<String> UNCHANGED = Set.of(
            "commands.setblock.failed",
            "commands.fill.failed");

    private FeedbackFilter() {}

    public static void register() {
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (overlay || !CommandBuildRunner.isListening()) {
                return true;
            }
            String key = keyOf(message, 0);
            if (key == null) {
                return true;
            }

            boolean hide = AiBuildMod.config().hideCommandFeedback;
            if (SUCCESS.contains(key)) {
                CommandBuildRunner.countSuccess();
                return !hide;
            }
            if (UNCHANGED.contains(key)) {
                CommandBuildRunner.countUnchanged();
                return !hide;
            }
            if (isFailure(key)) {
                CommandBuildRunner.countFailure(message.getString());
                return !hide;
            }
            return true;
        });
    }

    /**
     * Errors from a command: an unloaded or out of world position, a fill that is
     * too big, a missing permission, or anything the parser rejected.
     */
    private static boolean isFailure(String key) {
        return key.startsWith("argument.")
                || key.startsWith("command.")
                || key.equals("commands.fill.toobig")
                || key.startsWith("permissions.");
    }

    /**
     * The translation key behind a message. Command errors arrive wrapped in an
     * empty styled component with the real one as its first child, so look one or
     * two levels down as well.
     */
    private static String keyOf(Component message, int depth) {
        if (message.getContents() instanceof TranslatableContents translatable) {
            return translatable.getKey();
        }
        if (depth < 2 && !message.getSiblings().isEmpty()) {
            return keyOf(message.getSiblings().get(0), depth + 1);
        }
        return null;
    }
}
