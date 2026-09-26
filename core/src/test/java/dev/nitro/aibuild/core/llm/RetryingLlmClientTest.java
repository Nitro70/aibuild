package dev.nitro.aibuild.core.llm;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryingLlmClientTest {

    /** Replays a scripted sequence of outcomes: a string reply, or an exception to throw. */
    private static final class ScriptedClient implements LlmClient {
        private final String name;
        private final Deque<Object> script = new ArrayDeque<>();
        int calls;

        ScriptedClient(String name, Object... outcomes) {
            this.name = name;
            for (Object outcome : outcomes) {
                script.add(outcome);
            }
        }

        @Override
        public String generatePlanJson(String systemInstruction, String userPrompt) throws LlmException {
            calls++;
            Object next = script.poll();
            if (next instanceof LlmException e) {
                throw e;
            }
            return (String) next;
        }

        @Override
        public List<String> listModels() {
            return List.of();
        }

        @Override
        public String describe() {
            return name;
        }
    }

    private static LlmException busy() {
        return new LlmException("Gemini had a server error (503).", 503, -1);
    }

    private final List<Long> slept = new ArrayList<>();
    private final List<String> notices = new ArrayList<>();

    private RetryingLlmClient retrying(LlmClient primary, LlmClient fallback, int retries) {
        return new RetryingLlmClient(primary, fallback, retries,
                slept::add, (attempt, of, delayMs, reason) -> notices.add(attempt + "/" + of));
    }

    @Test
    void overloadedThenFineSucceedsWithoutTheCallerSeeingTheError() throws Exception {
        ScriptedClient primary = new ScriptedClient("gemini", busy(), busy(), "{\"ops\":[]}");
        assertEquals("{\"ops\":[]}", retrying(primary, null, 3).generatePlanJson("s", "u"));
        assertEquals(3, primary.calls);
        assertEquals(2, slept.size());
    }

    @Test
    void waitsLongerEachTime() throws Exception {
        ScriptedClient primary = new ScriptedClient("gemini", busy(), busy(), busy(), "{}");
        retrying(primary, null, 3).generatePlanJson("s", "u");
        assertEquals(3, slept.size());
        assertTrue(slept.get(1) > slept.get(0), "second wait should be longer: " + slept);
        assertTrue(slept.get(2) > slept.get(1), "third wait should be longer: " + slept);
    }

    /** A bad key is never going to start working, so retrying it only wastes the player's time. */
    @Test
    void permanentErrorsAreNotRetried() {
        ScriptedClient primary = new ScriptedClient("gemini",
                new LlmException("refused the API key (401)", 401, -1), "{}");
        assertThrows(LlmException.class, () -> retrying(primary, null, 3).generatePlanJson("s", "u"));
        assertEquals(1, primary.calls);
        assertTrue(slept.isEmpty());
    }

    @Test
    void givesUpAfterTheLastRetryWithTheRealError() {
        ScriptedClient primary = new ScriptedClient("gemini", busy(), busy(), busy(), busy());
        LlmException error = assertThrows(LlmException.class,
                () -> retrying(primary, null, 3).generatePlanJson("s", "u"));
        assertEquals(4, primary.calls);
        assertEquals(503, error.status());
    }

    @Test
    void honoursRetryAfterWhenTheProviderGivesOne() throws Exception {
        ScriptedClient primary = new ScriptedClient("gemini",
                new LlmException("rate limited (429)", 429, 7), "{}");
        retrying(primary, null, 3).generatePlanJson("s", "u");
        assertEquals(List.of(7000L), slept);
    }

    @Test
    void retryAfterIsCappedSoAPlayerIsNeverLeftWaitingMinutes() throws Exception {
        ScriptedClient primary = new ScriptedClient("gemini",
                new LlmException("rate limited (429)", 429, 600), "{}");
        retrying(primary, null, 3).generatePlanJson("s", "u");
        assertTrue(slept.get(0) <= RetryingLlmClient.MAX_DELAY_MS, "waited " + slept.get(0));
    }

    /** Overload is usually one model, so a different model is the fastest way round it. */
    @Test
    void fallsBackToAnotherModelWhenThePrimaryStaysBusy() throws Exception {
        ScriptedClient primary = new ScriptedClient("gemini-2.5-flash", busy(), busy());
        ScriptedClient fallback = new ScriptedClient("gemini-lite", "{\"fallback\":true}");
        assertEquals("{\"fallback\":true}", retrying(primary, fallback, 1).generatePlanJson("s", "u"));
        assertEquals(2, primary.calls);
        assertEquals(1, fallback.calls);
    }

    @Test
    void fallbackIsNotUsedForPermanentErrors() {
        ScriptedClient primary = new ScriptedClient("gemini", new LlmException("bad request (400)", 400, -1));
        ScriptedClient fallback = new ScriptedClient("gemini-lite", "{}");
        assertThrows(LlmException.class, () -> retrying(primary, fallback, 3).generatePlanJson("s", "u"));
        assertEquals(0, fallback.calls);
    }

    @Test
    void playerIsToldAboutEachRetry() throws Exception {
        ScriptedClient primary = new ScriptedClient("gemini", busy(), busy(), "{}");
        retrying(primary, null, 3).generatePlanJson("s", "u");
        assertEquals(List.of("2/4", "3/4"), notices);
    }

    @Test
    void zeroRetriesMeansOneAttempt() {
        ScriptedClient primary = new ScriptedClient("gemini", busy(), "{}");
        assertThrows(LlmException.class, () -> retrying(primary, null, 0).generatePlanJson("s", "u"));
        assertEquals(1, primary.calls);
    }

    @Test
    void retryAfterHeaderIsRead() {
        assertEquals(12, HttpSupport.retryAfterSeconds("12", ""));
        assertEquals(12, HttpSupport.retryAfterSeconds(" 12 ", ""));
    }

    /** Gemini puts its wait in the error body rather than a header. */
    @Test
    void geminiRetryDelayInTheBodyIsRead() {
        String body = "{\"error\":{\"code\":429,\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\","
                + "\"retryDelay\": \"7s\"}]}}";
        assertEquals(7, HttpSupport.retryAfterSeconds("", body));
        assertEquals(3, HttpSupport.retryAfterSeconds(null, "{\"retryDelay\":\"3.25s\"}"));
    }

    @Test
    void noHintMeansMinusOne() {
        assertEquals(-1, HttpSupport.retryAfterSeconds("", "{\"error\":{\"message\":\"busy\"}}"));
        // An HTTP date is legal for Retry-After, but not worth parsing; fall back to backoff.
        assertEquals(-1, HttpSupport.retryAfterSeconds("Wed, 21 Oct 2026 07:28:00 GMT", null));
    }

    /** The real body of a Gemini free tier 429 once the day's requests are gone, trimmed. */
    private static final String GEMINI_DAILY_QUOTA = """
            {"error": {"code": 429, "status": "RESOURCE_EXHAUSTED",
              "message": "You exceeded your current quota, please check your plan and billing details. \\n* Quota exceeded for metric: generativelanguage.googleapis.com/generate_content_free_tier_requests, limit: 20, model: gemini-3.5-flash\\nPlease retry in 59.674235455s.",
              "details": [
                {"@type": "type.googleapis.com/google.rpc.QuotaFailure",
                 "violations": [{"quotaMetric": "generativelanguage.googleapis.com/generate_content_free_tier_requests",
                                 "quotaId": "GenerateRequestsPerDayPerProjectPerModel-FreeTier",
                                 "quotaDimensions": {"location": "global", "model": "gemini-3.5-flash"},
                                 "quotaValue": "20"}]},
                {"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "59s"}]}}
            """;

    private static LlmException dailyQuota() {
        return HttpSupport.failure(429, GEMINI_DAILY_QUOTA, -1, "Gemini", "gemini-3.5-flash");
    }

    /**
     * Every retry spends one of the day's requests, failed or not, so retrying a
     * spent allowance only fails again and tells the player to wait a minute when
     * the real wait is until tomorrow.
     */
    @Test
    void aSpentDailyAllowanceIsNotRetried() {
        ScriptedClient primary = new ScriptedClient("gemini", dailyQuota(), "{}");
        LlmException error = assertThrows(LlmException.class,
                () -> retrying(primary, null, 3).generatePlanJson("s", "u"));
        assertEquals(1, primary.calls);
        assertTrue(slept.isEmpty(), "slept " + slept);
        assertTrue(error.isQuotaExhausted());
    }

    /** The allowance is per model, so another model still has its own. */
    @Test
    void aSpentDailyAllowanceGoesStraightToTheFallback() throws Exception {
        ScriptedClient primary = new ScriptedClient("gemini-3.5-flash", dailyQuota());
        ScriptedClient fallback = new ScriptedClient("gemini-2.5-flash", "{\"fallback\":true}");
        assertEquals("{\"fallback\":true}", retrying(primary, fallback, 3).generatePlanJson("s", "u"));
        assertEquals(1, primary.calls);
        assertTrue(slept.isEmpty(), "slept " + slept);
    }

    @Test
    void dailyQuotaIsToldApartFromAPerMinuteLimit() {
        assertTrue(dailyQuota().isQuotaExhausted());
        assertTrue(!dailyQuota().isTransient());

        String perMinute = GEMINI_DAILY_QUOTA.replace("PerDay", "PerMinute");
        LlmException minute = HttpSupport.failure(429, perMinute, 30, "Gemini", "gemini-3.5-flash");
        assertTrue(!minute.isQuotaExhausted());
        assertTrue(minute.isTransient());

        LlmException plain = HttpSupport.failure(429, "{\"error\":{\"message\":\"slow down\"}}", -1, "Gemini", "m");
        assertTrue(plain.isTransient());
    }

    /** OpenAI style providers say "insufficient_quota" when the account has no credit left. */
    @Test
    void noCreditLeftIsNotRetried() {
        String body = "{\"error\":{\"message\":\"You exceeded your current quota, please check your plan and "
                + "billing details.\",\"type\":\"insufficient_quota\",\"code\":\"insufficient_quota\"}}";
        LlmException error = HttpSupport.failure(429, body, -1, "OpenAI", "gpt-5");
        assertTrue(error.isQuotaExhausted());
        assertTrue(!error.isTransient());
    }

    /** "Retry in 59s" is what Google says, and it is wrong: the allowance is back tomorrow. */
    @Test
    void theDailyQuotaMessageSaysWhatHappenedAndWhatToDo() {
        String message = dailyQuota().getMessage();
        assertTrue(message.contains("20"), message);
        assertTrue(message.contains("gemini-3.5-flash"), message);
        assertTrue(message.contains("Failed requests count"), message);
        assertTrue(!message.contains("retry in"), message);
    }

    /**
     * Measured on Gemini: a mansion "just under 50000 blocks" was answered 1 time in 11
     * while a normal house went through 2 in 3, in the same minutes. So a busy reply
     * on a big build is the build's size as much as anyone else's traffic.
     */
    @Test
    void aBusyReplySuggestsASmallerBuild() {
        String message = HttpSupport.failure(503, "{\"error\":{\"message\":\"high demand\"}}", -1,
                "Gemini", "gemini-3.5-flash").getMessage();
        assertTrue(message.contains("smaller"), message);
    }

    @Test
    void transientClassification() {
        for (int status : new int[] {429, 500, 502, 503, 504}) {
            assertTrue(new LlmException("x", status, -1).isTransient(), "status " + status);
        }
        for (int status : new int[] {400, 401, 403, 404}) {
            assertTrue(!new LlmException("x", status, -1).isTransient(), "status " + status);
        }
        assertTrue(LlmException.timeout("timed out").isTransient());
    }
}
