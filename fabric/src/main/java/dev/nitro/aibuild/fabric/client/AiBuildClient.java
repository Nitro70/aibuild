package dev.nitro.aibuild.fabric.client;

import dev.nitro.aibuild.fabric.client.screen.AiBuildConfigScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.minecraft.client.Minecraft;

/**
 * Client entry point, which exists only to put the settings screen somewhere
 * reachable.
 *
 * <p>Registered as the {@code client} entrypoint, so none of this loads on a
 * dedicated server, and the server jar leaves these classes out entirely.
 *
 * <p>The command is a client command rather than part of the server side
 * {@code /aibuild} tree. A server command cannot open a screen without sending a
 * packet, and a packet would mean the mod stopped working with vanilla clients.
 */
public final class AiBuildClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(ClientCommands.literal("aibuildconfig")
                        .executes(context -> {
                            openSettings();
                            return 1;
                        })));
    }

    private static void openSettings() {
        Minecraft client = Minecraft.getInstance();
        // Deferred, because the chat screen is still closing when this runs and
        // setting a screen underneath it would be undone immediately.
        client.execute(() -> client.setScreenAndShow(new AiBuildConfigScreen(null)));
    }
}
