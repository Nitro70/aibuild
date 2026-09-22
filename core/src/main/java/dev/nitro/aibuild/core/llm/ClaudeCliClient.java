package dev.nitro.aibuild.core.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.nitro.aibuild.core.prompt.PlanSchema;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Claude through the Claude Code CLI rather than an API key.
 *
 * <p>If the machine running the server is already signed in to Claude Code, this
 * uses that subscription and needs no key at all. It is the only provider here
 * with that option, which is why it gets its own entry rather than a flag on the
 * Anthropic one.
 *
 * <p>The CLI is run in print mode with JSON output, and the prompt goes in over
 * stdin. Passing it as an argument would break on long builds, since Windows caps
 * a command line at about 32k characters.
 */
public final class ClaudeCliClient implements LlmClient {

    private static final String NAME = "Claude Code CLI";

    private final String executable;
    private final String model;
    private final Duration timeout;
    private final List<String> extraArgs;

    /**
     * @param executable the command to run, normally {@code claude}. Use a full
     *                   path when the server's PATH does not include it.
     * @param model      an optional model override, or empty for the CLI's own default
     * @param extraArgs  any further flags to pass through
     */
    public ClaudeCliClient(String executable, String model, Duration timeout, List<String> extraArgs) {
        this.executable = executable == null || executable.isBlank() ? "claude" : executable.trim();
        this.model = model == null ? "" : model.trim();
        this.timeout = timeout;
        this.extraArgs = extraArgs == null ? List.of() : List.copyOf(extraArgs);
    }

    @Override
    public String generatePlanJson(String systemInstruction, String userPrompt) throws LlmException {
        // The CLI has no separate system channel in print mode, so the standing
        // instructions are folded into the prompt.
        String prompt = systemInstruction
                + "\n\nReply with a single JSON object and nothing else, matching this JSON schema:\n"
                + PlanSchema.jsonSchema()
                + "\n\nThe build request is:\n"
                + userPrompt;

        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("-p");
        command.add("--output-format");
        command.add("json");
        if (!model.isBlank()) {
            command.add("--model");
            command.add(model);
        }
        command.addAll(extraArgs);

        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(false).start();
        } catch (IOException e) {
            throw new LlmException("Could not start '" + executable + "'. Install Claude Code and make "
                    + "sure it is on the PATH of whatever runs the server, or set claudeCliPath in the "
                    + "config to its full path.", e);
        }

        String stdout;
        String stderr;
        try {
            try (OutputStream in = process.getOutputStream()) {
                in.write(prompt.getBytes(StandardCharsets.UTF_8));
            }
            // Read both streams before waiting, or a full stderr pipe can deadlock
            // the process while it blocks trying to write to it.
            stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

            if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw LlmException.timeout(NAME + " did not finish within " + timeout.toSeconds()
                        + " seconds. Try a simpler build, or raise requestTimeoutSeconds.");
            }
        } catch (IOException e) {
            process.destroyForcibly();
            throw new LlmException("Failed while talking to " + NAME + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new LlmException("The request was interrupted.", e);
        }

        if (process.exitValue() != 0) {
            String detail = stderr.isBlank() ? stdout : stderr;
            throw new LlmException(NAME + " exited with code " + process.exitValue()
                    + ". Check that you are signed in by running 'claude' in a terminal. "
                    + trimForDisplay(detail));
        }
        return extractResult(stdout);
    }

    /** The CLI offers no model listing, so this is empty by design. */
    @Override
    public List<String> listModels() {
        return List.of();
    }

    @Override
    public String describe() {
        return NAME + (model.isBlank() ? "" : " (" + model + ")");
    }

    /**
     * Unwraps the CLI's own JSON envelope to get at the model's answer.
     *
     * <p>Print mode returns an object carrying the reply in a "result" field. If
     * the shape ever changes, fall back to reading the whole output as the plan.
     */
    private String extractResult(String stdout) throws LlmException {
        if (stdout.isBlank()) {
            throw new LlmException(NAME + " produced no output.");
        }

        try {
            JsonElement parsed = JsonParser.parseString(stdout.trim());
            if (parsed.isJsonObject()) {
                JsonObject root = parsed.getAsJsonObject();

                if (root.has("is_error") && root.get("is_error").isJsonPrimitive()
                        && root.get("is_error").getAsBoolean()) {
                    throw new LlmException(NAME + " reported an error: "
                            + trimForDisplay(root.has("result") ? root.get("result").getAsString() : stdout));
                }
                if (root.has("result") && root.get("result").isJsonPrimitive()) {
                    return HttpSupport.extractJsonObject(root.get("result").getAsString());
                }
                // Already the plan itself rather than an envelope.
                if (root.has("ops")) {
                    return root.toString();
                }
            }
        } catch (com.google.gson.JsonSyntaxException ignored) {
            // Not JSON at all, so fall through and scrape it.
        }
        return HttpSupport.extractJsonObject(stdout);
    }

    private static String trimForDisplay(String text) {
        String cleaned = text == null ? "" : text.strip();
        return cleaned.length() > 300 ? cleaned.substring(0, 300) + "..." : cleaned;
    }
}
