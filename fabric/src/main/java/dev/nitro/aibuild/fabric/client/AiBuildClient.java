package dev.nitro.aibuild.fabric.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Client entry point.
 *
 * <p>Registers {@code /aibuild} as a client command, which is what makes the mod
 * usable on a server that does not have it: with operator permission, builds go
 * out as /setblock and /fill. On a server that does have it, the same command is
 * handed through to the server side, so nothing changes for the player.
 *
 * <p>Registered as the {@code client} entrypoint, so none of this loads on a
 * dedicated server, and the server jar leaves these classes out entirely.
 */
public final class AiBuildClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            ClientBuildCommand.register(dispatcher);
            // Kept as a shortcut, and because it predates /aibuild config.
            dispatcher.register(ClientCommands.literal("aibuildconfig")
                    .executes(context -> ClientBuildCommand.openSettings()));
        });

        FeedbackFilter.register();
        ClientTickEvents.END_CLIENT_TICK.register(CommandBuildRunner::tick);
    }
}
