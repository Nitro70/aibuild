package dev.nitro.aibuild.core.llm;

import java.util.List;

/**
 * One model provider, reduced to the two things this mod needs from it.
 *
 * <p>Implementations make blocking network or process calls, so they must never
 * be used on the server thread.
 */
public interface LlmClient {

    /**
     * Asks for one build plan.
     *
     * <p>Each implementation builds the schema dialect its provider expects, and
     * falls back to describing the schema in the prompt when the provider has no
     * structured output mode of its own.
     *
     * @param systemInstruction the standing instructions
     * @param userPrompt        what the player asked for
     * @return raw JSON text, still to be parsed into a plan
     */
    String generatePlanJson(String systemInstruction, String userPrompt) throws LlmException;

    /**
     * Model ids this configuration can reach.
     *
     * @return an empty list when the provider offers no way to enumerate them
     */
    List<String> listModels() throws LlmException;

    /** Shown in messages, e.g. "Google Gemini". */
    String describe();
}
