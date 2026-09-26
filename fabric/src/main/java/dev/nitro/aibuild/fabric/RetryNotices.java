package dev.nitro.aibuild.fabric;

import dev.nitro.aibuild.core.llm.LlmException;
import dev.nitro.aibuild.core.llm.RetryingLlmClient;

import java.util.function.Consumer;

/**
 * Turns retry events into something a player can read, and writes each one to the
 * log.
 *
 * <p>The log line matters as much as the chat line. Without it, a burst of busy
 * replies from a provider leaves nothing behind but chat messages, and no record
 * of how many attempts were made or how long each wait was.
 */
public final class RetryNotices {

    private RetryNotices() {}

    /** @param say delivers a line to the player, on whatever thread that needs */
    public static RetryingLlmClient.RetryListener to(String provider, Consumer<String> say) {
        return new RetryingLlmClient.RetryListener() {
            @Override
            public void retrying(int attempt, int of, long delayMs, LlmException reason) {
                long seconds = Math.max(1, Math.round(delayMs / 1000.0));
                AiBuildMod.LOGGER.info("{} busy (status {}), retry {} of {} in {}ms",
                        provider, reason.status(), attempt, of, delayMs);
                say.accept(provider + " is busy" + statusSuffix(reason) + ", trying again in "
                        + seconds + "s (attempt " + attempt + " of " + of + ").");
            }

            @Override
            public void fallingBack(LlmException reason) {
                if (reason.isQuotaExhausted()) {
                    AiBuildMod.LOGGER.info("{} allowance used up (status {}), trying the fallback model",
                            provider, reason.status());
                    say.accept("This key's allowance for that model is used up, so trying the "
                            + "fallback model.");
                    return;
                }
                AiBuildMod.LOGGER.info("{} still busy (status {}) after retries, trying the fallback model",
                        provider, reason.status());
                say.accept(provider + " is still busy, so trying the fallback model.");
            }
        };
    }

    private static String statusSuffix(LlmException reason) {
        return reason.status() > 0 ? " (" + reason.status() + ")" : "";
    }
}
