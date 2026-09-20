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
 * Google Gemini, using its native {@code responseSchema} so the reply is JSON
 * matching the build plan schema rather than prose.
 */
public final class GeminiClient implements LlmClient {

    private static final String NAME = "Gemini";

    private final HttpClient http = HttpSupport.newClient();
    private final Gson gson = new Gson();
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final Duration timeout;
    private final double temperature;
    private final int maxOutputTokens;

    public GeminiClient(String apiKey, String baseUrl, String model, Duration timeout,
                        double temperature, int maxOutputTokens) {
        this.apiKey = apiKey;
        this.baseUrl = LlmClientFactory.trimTrailingSlash(baseUrl);
        this.model = model;
        this.timeout = timeout;
        this.temperature = temperature;
        this.maxOutputTokens = maxOutputTokens;
    }

    @Override
    public String generatePlanJson(String systemInstruction, String userPrompt)
            throws LlmException {

        JsonObject body = new JsonObject();
        body.add("systemInstruction", content(null, systemInstruction));
        JsonArray contents = new JsonArray();
        contents.add(content("user", userPrompt));
        body.add("contents", contents);

        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("responseMimeType", "application/json");
        generationConfig.add("responseSchema", PlanSchema.gemini());
        generationConfig.addProperty("temperature", temperature);
        generationConfig.addProperty("maxOutputTokens", maxOutputTokens);
        body.add("generationConfig", generationConfig);

        HttpResponse<String> response = HttpSupport.send(http, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/models/" + model + ":generateContent"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                .build(), timeout, NAME);

        if (response.statusCode() != 200) {
            throw new LlmException(
                    HttpSupport.describeFailure(response.statusCode(), response.body(), NAME, model));
        }
        return extractText(response.body());
    }

    @Override
    public List<String> listModels() throws LlmException {
        HttpResponse<String> response = HttpSupport.send(http, HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/models?pageSize=200"))
                .timeout(timeout)
                .header("x-goog-api-key", apiKey)
                .GET()
                .build(), timeout, NAME);

        if (response.statusCode() != 200) {
            throw new LlmException(
                    HttpSupport.describeFailure(response.statusCode(), response.body(), NAME, model));
        }

        List<String> out = new ArrayList<>();
        JsonArray models = JsonParser.parseString(response.body())
                .getAsJsonObject().getAsJsonArray("models");
        if (models == null) {
            return out;
        }
        for (JsonElement element : models) {
            JsonObject entry = element.getAsJsonObject();
            if (!supportsGenerateContent(entry) || !entry.has("name")) {
                continue;
            }
            // Ids come back as "models/gemini-x"; the bare id is what config wants.
            String id = entry.get("name").getAsString();
            out.add(id.startsWith("models/") ? id.substring("models/".length()) : id);
        }
        return out;
    }

    @Override
    public String describe() {
        return NAME + " (" + model + ")";
    }

    private static boolean supportsGenerateContent(JsonObject model) {
        JsonElement methods = model.get("supportedGenerationMethods");
        if (methods == null || !methods.isJsonArray()) {
            // Older responses omit the field. Better to list a model than hide it.
            return true;
        }
        for (JsonElement method : methods.getAsJsonArray()) {
            if ("generateContent".equals(method.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject content(String role, String text) {
        JsonObject part = new JsonObject();
        part.addProperty("text", text);
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject content = new JsonObject();
        if (role != null) {
            content.addProperty("role", role);
        }
        content.add("parts", parts);
        return content;
    }

    private String extractText(String body) throws LlmException {
        JsonObject root;
        try {
            root = JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new LlmException("Gemini returned a response that was not JSON.", e);
        }

        JsonObject feedback = root.getAsJsonObject("promptFeedback");
        if (feedback != null && feedback.has("blockReason")) {
            throw new LlmException("Gemini blocked the prompt ("
                    + feedback.get("blockReason").getAsString() + "). Try rewording it.");
        }

        JsonArray candidates = root.getAsJsonArray("candidates");
        if (candidates == null || candidates.isEmpty()) {
            throw new LlmException("Gemini returned no candidates. Try rewording the prompt.");
        }

        JsonObject candidate = candidates.get(0).getAsJsonObject();
        String finishReason = candidate.has("finishReason")
                ? candidate.get("finishReason").getAsString() : "";

        JsonObject content = candidate.getAsJsonObject("content");
        JsonArray parts = content == null ? null : content.getAsJsonArray("parts");

        if (parts == null || parts.isEmpty()) {
            if ("MAX_TOKENS".equals(finishReason)) {
                throw new LlmException("Gemini ran out of output tokens before writing anything. "
                        + "Raise maxOutputTokens in the config, or ask for a smaller build.");
            }
            throw new LlmException("Gemini returned an empty response"
                    + (finishReason.isEmpty() ? "." : " (" + finishReason + ")."));
        }

        StringBuilder text = new StringBuilder();
        for (JsonElement part : parts) {
            JsonObject p = part.getAsJsonObject();
            if (p.has("text")) {
                text.append(p.get("text").getAsString());
            }
        }

        if ("MAX_TOKENS".equals(finishReason)) {
            // Truncated JSON fails to parse with a baffling message, so name the cause here.
            throw new LlmException("Gemini hit the output token limit partway through the build, "
                    + "so the plan is incomplete. Raise maxOutputTokens, or ask for a smaller build.");
        }
        if (text.isEmpty()) {
            throw new LlmException("Gemini returned a response with no text content.");
        }
        return text.toString();
    }
}
