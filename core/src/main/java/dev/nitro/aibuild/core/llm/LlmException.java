package dev.nitro.aibuild.core.llm;

import java.util.Set;

/**
 * A failure talking to a model provider, worded so it can be shown to a player.
 *
 * <p>Carries the HTTP status when there was one, because whether a failure is
 * worth retrying depends entirely on it. A 503 means the provider is overloaded
 * and will probably answer a few seconds later. A 401 means the key is wrong and
 * never will.
 *
 * <p>Messages must never contain the API key. Keys travel only in request headers
 * and are never put in a URL, a log line or an exception.
 */
public class LlmException extends Exception {

    /** Overloaded, rate limited, or a passing server fault: worth another try. */
    private static final Set<Integer> TRANSIENT_STATUSES = Set.of(429, 500, 502, 503, 504);

    private final int status;
    private final int retryAfterSeconds;
    private final boolean timedOut;

    public LlmException(String message) {
        this(message, 0, -1, false, null);
    }

    public LlmException(String message, Throwable cause) {
        this(message, 0, -1, false, cause);
    }

    /**
     * @param status            HTTP status, or 0 when the failure was not an HTTP response
     * @param retryAfterSeconds what the provider asked us to wait, or -1 if it did not say
     */
    public LlmException(String message, int status, int retryAfterSeconds) {
        this(message, status, retryAfterSeconds, false, null);
    }

    private LlmException(String message, int status, int retryAfterSeconds, boolean timedOut, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
        this.timedOut = timedOut;
    }

    /** No reply in time. Usually load on the provider's side, so worth retrying. */
    public static LlmException timeout(String message) {
        return new LlmException(message, 0, -1, true, null);
    }

    public static LlmException timeout(String message, Throwable cause) {
        return new LlmException(message, 0, -1, true, cause);
    }

    public int status() {
        return status;
    }

    /** -1 when the provider gave no hint. */
    public int retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public boolean isTransient() {
        return timedOut || TRANSIENT_STATUSES.contains(status);
    }
}
