package dev.nitro.aibuild.fabric.control;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.nitro.aibuild.core.config.AiBuildConfig;
import dev.nitro.aibuild.fabric.AiBuildMod;
import net.minecraft.server.MinecraftServer;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A local TCP port for driving the mod from outside the game.
 *
 * <p>One JSON object per line in, one per line out. Plain text and line
 * delimited so it can be driven from a script, a socket client or by hand with
 * netcat.
 *
 * <p>Off unless switched on in the config. Anything that reaches this port can
 * place blocks and spend API credit, so it binds to loopback by default and
 * refuses to bind anywhere else without a token.
 */
public final class ControlServer {

    /** Guards against a runaway client eating the heap with one enormous line. */
    private static final int MAX_LINE_BYTES = 16 * 1024 * 1024;

    private final Gson gson = new Gson();
    private final List<Socket> clients = Collections.synchronizedList(new ArrayList<>());

    private ServerSocket socket;
    private Thread acceptThread;
    private volatile boolean running;

    /**
     * Opens the port, if the config allows it.
     *
     * @return a message worth logging, or null when the port is simply off
     */
    public String start(MinecraftServer server, AiBuildConfig config) {
        if (!config.controlPortEnabled) {
            return null;
        }
        if (running) {
            return null;
        }

        boolean loopback = isLoopback(config.controlBindAddress);
        if (!loopback && config.controlToken.isBlank()) {
            // Refusing is the right call. A port that can place blocks and spend
            // credit must not be reachable from the network without a secret.
            return "AIBuild control port NOT started: controlBindAddress is '"
                    + config.controlBindAddress + "' which is not loopback, and no controlToken "
                    + "is set. Set a token, or bind to 127.0.0.1.";
        }

        try {
            socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(
                    InetAddress.getByName(config.controlBindAddress), config.controlPort));
        } catch (IOException e) {
            socket = null;
            return "AIBuild control port failed to open on " + config.controlBindAddress + ":"
                    + config.controlPort + " - " + e.getMessage();
        }

        running = true;
        acceptThread = new Thread(() -> acceptLoop(server, config), "aibuild-control");
        acceptThread.setDaemon(true);
        acceptThread.start();

        return "AIBuild control port listening on " + config.controlBindAddress + ":"
                + config.controlPort + (config.controlToken.isBlank() ? " (no token)" : " (token required)");
    }

    private void acceptLoop(MinecraftServer server, AiBuildConfig config) {
        while (running) {
            Socket client;
            try {
                client = socket.accept();
            } catch (IOException e) {
                if (running) {
                    AiBuildMod.LOGGER.warn("AIBuild control port stopped accepting: {}", e.getMessage());
                }
                return;
            }

            clients.add(client);
            Thread worker = new Thread(() -> {
                try {
                    handle(client, server, config);
                } finally {
                    clients.remove(client);
                    closeQuietly(client);
                }
            }, "aibuild-control-client");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void handle(Socket client, MinecraftServer server, AiBuildConfig config) {
        ControlCommands commands = new ControlCommands(server);

        try (InputStream in = client.getInputStream();
             BufferedWriter out = new BufferedWriter(
                     new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))) {

            String line;
            while (running && (line = readLine(in)) != null) {
                if (line.isBlank()) {
                    continue;
                }

                JsonObject response;
                try {
                    JsonObject request = JsonParser.parseString(line).getAsJsonObject();
                    response = authorise(request, config)
                            ? commands.dispatch(request)
                            : ControlCommands.error("bad or missing token");
                } catch (RuntimeException e) {
                    response = ControlCommands.error("could not read that request: " + e.getMessage());
                } catch (Exception e) {
                    response = ControlCommands.error(e.getMessage() == null ? e.toString() : e.getMessage());
                }

                out.write(gson.toJson(response));
                out.write('\n');
                out.flush();
            }
        } catch (IOException e) {
            // A client hanging up mid request is normal, not worth a stack trace.
            AiBuildMod.LOGGER.debug("AIBuild control client closed: {}", e.getMessage());
        }
    }

    private static boolean authorise(JsonObject request, AiBuildConfig config) {
        if (config.controlToken.isBlank()) {
            return true;
        }
        if (!request.has("token")) {
            return false;
        }
        // Constant time, so a client cannot time its way to the token.
        byte[] expected = config.controlToken.getBytes(StandardCharsets.UTF_8);
        byte[] given = request.get("token").getAsString().getBytes(StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(expected, given);
    }

    /** Reads one UTF-8 line, capped so a bad client cannot exhaust the heap. */
    private static String readLine(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(256);
        int read;
        while ((read = in.read()) != -1) {
            if (read == '\n') {
                break;
            }
            if (read != '\r') {
                buffer.write(read);
            }
            if (buffer.size() > MAX_LINE_BYTES) {
                throw new IOException("request line exceeded " + MAX_LINE_BYTES + " bytes");
            }
        }
        if (read == -1 && buffer.size() == 0) {
            return null;
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static boolean isLoopback(String address) {
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (IOException e) {
            return false;
        }
    }

    public void stop() {
        running = false;
        closeQuietly(socket);
        socket = null;

        synchronized (clients) {
            for (Socket client : new ArrayList<>(clients)) {
                closeQuietly(client);
            }
            clients.clear();
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
    }

    public boolean isRunning() {
        return running;
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Closing is best effort during shutdown.
        }
    }
}
