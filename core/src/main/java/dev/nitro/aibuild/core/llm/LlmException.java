package dev.nitro.aibuild.core.llm;

/**
 * A failure talking to a model provider, worded so it can be shown to a player.
 *
 * <p>Messages must never contain the API key. Keys travel only in request headers
 * and are never put in a URL, a log line or an exception.
 */
public class LlmException extends Exception {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
