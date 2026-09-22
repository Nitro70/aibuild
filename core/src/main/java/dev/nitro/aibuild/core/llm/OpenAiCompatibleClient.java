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
 * OpenAI's chat completions API, and the many providers that copy it: DeepSeek,
 * Groq, xAI, Mistral, OpenRouter, Together, Ollama and LM Studio.
 *
 * <p>JSON is requested through {@code response_format: json_object}, which is far
 * more widely implemented than strict {@code json_schema}. The schema itself is
 * spelled out in the prompt, so a provider that ignores the flag entirely still
 * has everything it needs.
 */
public final class OpenAiCompatibleClient implements LlmClient {

    private final HttpClient http = HttpSupport.newClient();
    private final Gson gson = new Gson();
    private final String name;
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final Duration timeout;
    private final double temperature;
    private final int maxOutputTokens;
    private final boolean jsonMode;

    public OpenAiCompatibleClient(String name, String apiKey, String baseUrl, String model,
                                  Duration timeout, double temperature, int maxOutputTokens,
                                  boolean jsonMode) {
        this.name = name;
        this.apiKey = apiKey;
        this.baseUrl = LlmClientFactory.trimTrailingSlash(baseUrl);
        this.model = model;
        this.timeout = timeout;
        this.temperature = temperature;
        this.maxOutputTokens = maxOutputTokens;
        this.jsonMode = jsonMode;
    }

    @Override
    public String generatePlanJson(String systemInstruction, String userPrompt) throws LlmException {
        JsonArray messages = new JsonArray();
        messages.add(message("system", systemInstruction + "\n\n" + schemaInstruction()));
        messages.add(message("user", userPrompt));

        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.add("messages", messages);
        body.addProperty("temperature", temperature);
        // Newer OpenAI models want max_completion_tokens, older ones and most
        // clones want max_tokens. Sending both is harmless: each side ignores
        // the name it does not know.
        body.addProperty("max_tokens", maxOutputTokens);
        body.addProperty("max_completion_tokens", maxOutputTokens);

        if (jsonMode) {
            JsonObject format = new JsonObject();
            format.addProperty("type", "json_object");
            body.add("response_format", format);
        }

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8));
        if (!apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> response = HttpSupport.send(http, request.build(), timeout, name);
        if (response.statusCode() != 200) {
            throw HttpSupport.failure(response, name, model);
        }
        return extractContent(response.body());
    }

    @Override
    public List<String> listModels() throws LlmException {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/models"))
                .timeout(timeout)
                .GET();
        if (!apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey);
        }

        HttpResponse<String> response = HttpSupport.send(http, request.build(), timeout, name);
        if (response.statusCode() != 200) {
            throw HttpSupport.failure(response, name, model);
        }

        List<String> out = new ArrayList<>();
        JsonElement parsed = JsonParser.parseString(response.body());
        if (!parsed.isJsonObject()) {
            return out;
        }
        JsonArray data = parsed.getAsJsonObject().getAsJsonArray("data");
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
        return name + " (" + model + ")";
    }

    private String schemaInstruction() {
        return "Reply with a single JSON object and nothing else. It must match this JSON schema:\n"
                + PlanSchema.jsonSchema();
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private String extractContent(String body) throws LlmException {
        JsonObject root;
        try {
            root = JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new LlmException(name + " returned a response that was not JSON.", e);
        }

        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new LlmException(name + " returned no choices. Try rewording the prompt.");
        }

        JsonObject choice = choices.get(0).getAsJsonObject();
        String finishReason = choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()
                ? choice.get("finish_reason").getAsString()
                : "";
        if ("length".equals(finishReason)) {
            throw new LlmException(name + " hit the output token limit partway through the build, "
                    + "so the plan is incomplete. Raise maxOutputTokens, or ask for a smaller build.");
        }

        JsonObject message = choice.getAsJsonObject("message");
        if (message == null || !message.has("content") || message.get("content").isJsonNull()) {
            throw new LlmException(name + " returned an empty message.");
        }

        String content = message.get("content").getAsString();
        if (content.isBlank()) {
            throw new LlmException(name + " returned an empty message.");
        }
        // Not every clone honours json_object, so tolerate a code fence or a preamble.
        return HttpSupport.extractJsonObject(content);
    }
}
