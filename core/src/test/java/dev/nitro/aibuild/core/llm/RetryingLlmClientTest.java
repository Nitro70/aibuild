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
