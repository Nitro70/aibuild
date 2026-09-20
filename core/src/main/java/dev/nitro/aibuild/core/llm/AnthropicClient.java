package dev.nitro.aibuild.core.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.nitro.aibuild.core.prompt.PlanSchema;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Anthropic's messages API, using an API key.
 *
 * <p>There is no JSON response mode here, so the plan schema is declared as a
 * tool and the model is forced to call it. That makes the reply structured by
 * construction rather than by request, which is more reliable than asking for
 * JSON in the prompt.
 *
 * <p>For a Claude Code subscription rather than an API key, see
 * {@link ClaudeCliClient}.
 */
public final class AnthropicClient implements LlmClient {

    private static final String NAME = "Anthropic";
    private static final String API_VERSION = "2023-06-01";

    private final HttpClient http = HttpSupport.newClient();
    private final Gson gson = new Gson();
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final Duration timeout;
    private final double temperature;
    private final int maxOutputTokens;

    public AnthropicClient(String apiKey, String baseUrl, String model, Duration timeout,
                           double temperature, int maxOutputTokens) {
        this.apiKey = apiKey;
        this.baseUrl = LlmClientFactory.trimTrailingSlash(baseUrl);
        this.model = model;
        this.timeout = timeout;
        this.temperature = temperature;
        this.maxOutputTokens = maxOutputTokens;
    }

    @Override
    public String generatePlanJson(String systemInstruction, String userPrompt) throws LlmException {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", PlanSchema.NAME);
        tool.addProperty("description", "Return the finished build as a list of block placements.");
        tool.add("input_schema", PlanSchema.jsonSchema());
        JsonArray tools = new JsonArray();
        tools.add(tool);

        JsonObject toolChoice = new JsonObject();
        toolChoice.addProperty("type", "tool");
        toolChoice.addProperty("name", PlanSchema.NAME);

        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", userPrompt);
        JsonArray messages = new JsonArray();
        messages.add(message);

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", maxOutputTokens);
        body.addProperty("temperature", temperature);
        body.addProperty("system", systemInstruction);
        body.add("messages", messages);
        body.add("tools", tools);
        body.add("tool_choice", toolChoice);

        HttpResponse<String> response = HttpSupport.send(http, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/messages"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                .build(), timeout, NAME);

        if (response.statusCode() != 200) {
            throw new LlmException(
                    HttpSupport.describeFailure(response.statusCode(), response.body(), NAME, model));
        }
        return extractToolInput(response.body());
    }

    @Override
    public List<String> listModels() throws LlmException {
        HttpResponse<String> response = HttpSupport.send(http, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/models?limit=100"))
                .timeout(timeout)
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .GET()
                .build(), timeout, NAME);

        if (response.statusCode() != 200) {
            throw new LlmException(
                    HttpSupport.describeFailure(response.statusCode(), response.body(), NAME, model));
        }

        List<String> out = new ArrayList<>();
        JsonArray data = JsonParser.parseString(response.body())
                .getAsJsonObject().getAsJsonArray("data");
        if (data == null) {
            return out;
        }
        for (JsonElement element : data) {
            JsonObject entry = element.getAsJsonObject();
            if (entry.has("id")) {
                out.add(entry.get("id").getAsString());
            }
        }
        return out;
    }

    @Override
    public String describe() {
        return NAME + " (" + model + ")";
    }

    private String extractToolInput(String body) throws LlmException {
        JsonObject root;
        try {
            root = JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new LlmException("Anthropic returned a response that was not JSON.", e);
        }

        String stopReason = root.has("stop_reason") && !root.get("stop_reason").isJsonNull()
                ? root.get("stop_reason").getAsString()
                : "";
        if ("max_tokens".equals(stopReason)) {
            throw new LlmException("Claude hit the output token limit partway through the build, "
                    + "so the plan is incomplete. Raise maxOutputTokens, or ask for a smaller build.");
        }

        JsonArray content = root.getAsJsonArray("content");
        if (content == null || content.isEmpty()) {
            throw new LlmException("Anthropic returned an empty response.");
        }

        for (JsonElement element : content) {
            JsonObject block = element.getAsJsonObject();
            if ("tool_use".equals(optionalType(block)) && block.has("input")) {
                return block.get("input").toString();
            }
        }

        // Forced tool use should make this unreachable, but a plain text reply is
        // still worth trying to read rather than discarding.
        for (JsonElement element : content) {
            JsonObject block = element.getAsJsonObject();
            if ("text".equals(optionalType(block)) && block.has("text")) {
                return HttpSupport.extractJsonObject(block.get("text").getAsString());
            }
        }
        throw new LlmException("Anthropic did not return a build plan.");
    }

    private static String optionalType(JsonObject block) {
        return block.has("type") ? block.get("type").getAsString() : "";
    }
}
