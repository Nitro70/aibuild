package dev.nitro.aibuild.core.llm;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Wraps a provider so that an overloaded or rate limited reply is retried rather
 * than handed straight to the player.
 *
 * <p>Providers say plainly when a failure is temporary. Gemini's 503 comes with
 * "try again later", and a few seconds later it usually works. Giving up on the
 * first one turns a brief spike in someone else's traffic into a failed build.
 *
 * <p>Waits grow each time, with a little randomness so that many clients hit by
 * the same outage do not all come back in the same instant. When the provider
 * names a wait of its own, that is used instead, within a cap.
 *
 * <p>If the model is still busy once the retries run out and a fallback model is
 * configured, the same request goes to that instead. Overload tends to be one
 * model rather than the whole provider, so a different model is usually the
 * quickest way round it.
 */
public final class RetryingLlmClient implements LlmClient {

    /** First wait. Doubles each retry. */
    static final long BASE_DELAY_MS = 2_000;

    /** Nobody should be left staring at chat for longer than this between tries. */
    static final long MAX_DELAY_MS = 20_000;

    /** Sleeps between attempts. Swappable so tests do not actually wait. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** Told about each retry, so the player knows why nothing has happened yet. */
    @FunctionalInterface
    public interface RetryListener {
        /**
         * @param attempt the attempt about to be made, counting from 1
         * @param of      total attempts that will be made on this model
         * @param delayMs how long until it is made
         * @param reason  the failure that caused the retry
         */
        void retrying(int attempt, int of, long delayMs, LlmException reason);

        /** The primary model stayed busy, so the fallback model is being tried. */
        default void fallingBack(LlmException reason) {}

        RetryListener NONE = (attempt, of, delayMs, reason) -> {};
    }

    private final LlmClient primary;
    private final LlmClient fallback;
    private final int maxRetries;
    private final Sleeper sleeper;
    private final RetryListener listener;

    /**
     * @param fallback   used once the primary's retries are spent on transient
     *                   failures, or null for none
     * @param maxRetries retries after the first attempt, so 3 means up to 4 tries
     */
    public RetryingLlmClient(LlmClient primary, LlmClient fallback, int maxRetries,
                             Sleeper sleeper, RetryListener listener) {
        this.primary = primary;
        this.fallback = fallback;
        this.maxRetries = Math.max(0, maxRetries);
        this.sleeper = sleeper;
        this.listener = listener == null ? RetryListener.NONE : listener;
    }

    @Override
    public String generatePlanJson(String systemInstruction, String userPrompt) throws LlmException {
        try {
            return withRetries(primary, systemInstruction, userPrompt);
        } catch (LlmException e) {
            if (fallback == null || !e.isTransient()) {
                throw e;
            }
            listener.fallingBack(e);
            return withRetries(fallback, systemInstruction, userPrompt);
        }
    }

    private String withRetries(LlmClient client, String systemInstruction, String userPrompt)
            throws LlmException {
        int attempts = maxRetries + 1;
        for (int attempt = 1; ; attempt++) {
            try {
                return client.generatePlanJson(systemInstruction, userPrompt);
            } catch (LlmException e) {
                if (!e.isTransient() || attempt >= attempts) {
                    throw e;
                }
                long delay = delayFor(attempt, e);
                listener.retrying(attempt + 1, attempts, delay, e);
                try {
                    sleeper.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** The provider's own wait if it gave one, otherwise a growing one with jitter. */
    static long delayFor(int attempt, LlmException reason) {
        if (reason.retryAfterSeconds() > 0) {
            return Math.min(MAX_DELAY_MS, reason.retryAfterSeconds() * 1000L);
        }
        long exponential = BASE_DELAY_MS << Math.min(attempt - 1, 10);
        // Up to a quarter extra, and only ever added, so each wait is still strictly
        // longer than the one before it.
        long jitter = ThreadLocalRandom.current().nextLong(0, exponential / 4 + 1);
        return Math.min(MAX_DELAY_MS, exponential + jitter);
    }

    @Override
    public List<String> listModels() throws LlmException {
        return primary.listModels();
    }

    @Override
    public String describe() {
        return primary.describe();
    }
}
