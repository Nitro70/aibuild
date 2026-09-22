package dev.nitro.aibuild.core.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Shared HTTP plumbing and error wording for the provider clients. */
final class HttpSupport {

    /** Gemini's RetryInfo, e.g. {@code "retryDelay": "7s"} or {@code "7.5s"}. */
    private static final java.util.regex.Pattern RETRY_DELAY =
            java.util.regex.Pattern.compile("\"retryDelay\"\\s*:\\s*\"(\\d+)(?:\\.\\d+)?s\"");

    private HttpSupport() {}

    static HttpClient newClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    static HttpResponse<String> send(HttpClient http, HttpRequest request, Duration timeout, String provider)
            throws LlmException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw LlmException.timeout(provider + " did not respond within " + timeout.toSeconds()
                    + " seconds. Try a simpler build, or raise requestTimeoutSeconds in the config.", e);
        } catch (IOException e) {
            throw new LlmException("Could not reach " + provider + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("The request was interrupted.", e);
        }
    }

    /**
     * A failed response as an exception carrying its status and any wait the
     * provider asked for, so the retry layer can tell a busy provider from a
     * broken request.
     */
    static LlmException failure(HttpResponse<String> response, String provider, String model) {
        int status = response.statusCode();
        return new LlmException(describeFailure(status, response.body(), provider, model),
                status, retryAfterSeconds(response));
    }

    /**
     * How long the provider asked us to wait. The standard Retry-After header when
     * present, otherwise Gemini's RetryInfo in the body, written like "7s".
     *
     * @return -1 when there is no hint
     */
    static int retryAfterSeconds(HttpResponse<String> response) {
        return retryAfterSeconds(response.headers().firstValue("retry-after").orElse(""), response.body());
    }

    /** The parsing on its own, so it can be tested without a real response. */
    static int retryAfterSeconds(String header, String body) {
        String value = header == null ? "" : header.trim();
        if (value.matches("\\d+")) {
            return Integer.parseInt(value);
        }
        java.util.regex.Matcher inBody = RETRY_DELAY.matcher(body == null ? "" : body);
        if (inBody.find()) {
            return Integer.parseInt(inBody.group(1));
        }
        return -1;
    }

    /**
     * Turns a failed response into something a player can act on.
     *
     * <p>Never includes the API key. Only the status, the provider's own message
     * and a hint about what usually causes it.
     */
    static String describeFailure(int status, String body, String provider, String model) {
        String detail = apiMessage(body);
        return switch (status) {
            case 400 -> provider + " rejected the request (400). " + detail;
            case 401, 403 -> provider + " refused the API key (" + status + "). Check the key in "
                    + "the config file or the matching environment variable. " + detail;
            case 404 -> provider + " has no model named '" + model + "' for this key (404). "
                    + "Run /aibuild models to see what is available. " + detail;
            case 413 -> provider + " said the request was too large (413). Ask for a smaller build. " + detail;
            case 429 -> provider + " rate limited the request (429). Wait a moment and try again. " + detail;
            case 500, 502, 503, 504 -> provider + " had a server error (" + status + "). "
                    + "This is usually temporary. " + detail;
            default -> provider + " returned HTTP " + status + ". " + detail;
        };
    }

    private static String apiMessage(String body) {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                return "";
            }
            JsonObject root = parsed.getAsJsonObject();
            JsonElement error = root.get("error");
            if (error != null && error.isJsonObject()) {
                JsonObject errorObject = error.getAsJsonObject();
                if (errorObject.has("message")) {
                    return errorObject.get("message").getAsString();
                }
            }
            if (error != null && error.isJsonPrimitive()) {
                return error.getAsString();
            }
            if (root.has("message")) {
                return root.get("message").getAsString();
            }
        } catch (RuntimeException ignored) {
            // A non JSON error body adds nothing the status code does not already say.
        }
        return "";
    }

    /**
     * Pulls a JSON object out of text that may be wrapped in prose or a code fence.
     *
     * <p>Only needed for providers without a strict structured output mode, where
     * a model occasionally decides to explain itself first.
     */
    static String extractJsonObject(String text) throws LlmException {
        String trimmed = text.trim();
        if (trimmed.startsWith("{")) {
            return trimmed;
        }

        int fence = trimmed.indexOf("```");
        if (fence >= 0) {
            int start = trimmed.indexOf('\n', fence);
            int end = trimmed.indexOf("```", fence + 3);
            if (start > 0 && end > start) {
                String inner = trimmed.substring(start + 1, end).trim();
                if (inner.startsWith("{")) {
                    return inner;
                }
            }
        }

        int open = trimmed.indexOf('{');
        int close = trimmed.lastIndexOf('}');
        if (open >= 0 && close > open) {
            return trimmed.substring(open, close + 1);
        }
        throw new LlmException("The model replied with text rather than a build plan.");
    }
}
