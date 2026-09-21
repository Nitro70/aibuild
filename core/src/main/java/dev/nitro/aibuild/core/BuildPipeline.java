package dev.nitro.aibuild.core;

import dev.nitro.aibuild.core.block.BlockRegistry;
import dev.nitro.aibuild.core.config.AiBuildConfig;
import dev.nitro.aibuild.core.llm.LlmClient;
import dev.nitro.aibuild.core.llm.LlmException;
import dev.nitro.aibuild.core.order.PlacementOrderer;
import dev.nitro.aibuild.core.order.PlanExpander;
import dev.nitro.aibuild.core.plan.BuildPlan;
import dev.nitro.aibuild.core.plan.Placement;
import dev.nitro.aibuild.core.plan.PlanParser;
import dev.nitro.aibuild.core.prompt.SystemPrompt;
import dev.nitro.aibuild.core.validate.PlanValidator;
import dev.nitro.aibuild.core.validate.ValidationResult;

import java.util.List;

/**
 * Prompt in, ordered placements out.
 *
 * <p>Everything between those two points lives here, so the Fabric module only
 * has to deal with the game: reading the registry, writing blocks and talking to
 * the player. Nothing here touches the world, and it blocks on network or process
 * calls, so it must be run off the server thread.
 */
public final class BuildPipeline {

    /** How many validation complaints to show the model when asking for a repair. */
    private static final int MAX_FEEDBACK_ISSUES = 25;

    /** How many to show the player when a build fails outright. */
    private static final int MAX_PLAYER_ISSUES = 6;

    private final LlmClient client;
    private final BlockRegistry registry;
    private final AiBuildConfig config;

    public BuildPipeline(LlmClient client, BlockRegistry registry, AiBuildConfig config) {
        this.client = client;
        this.registry = registry;
        this.config = config;
    }

    /** A finished, validated build ready to be written into the world. */
    public record Result(BuildPlan plan, List<Placement> placements, boolean repaired) {}

    /** Raised when a build could not be produced. The message is shown to the player. */
    public static final class BuildFailedException extends Exception {
        public BuildFailedException(String message) {
            super(message);
        }

        public BuildFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public Result run(String userPrompt) throws BuildFailedException {
        String system = SystemPrompt.build(config.maxRadius, config.maxHeight, config.maxBlocks);

        BuildPlan plan = requestPlan(system, userPrompt);
        PlanValidator validator = new PlanValidator(
                registry, config.blacklist, config.maxRadius, config.maxHeight);

        ValidationResult validation = validator.validate(plan);
        boolean repaired = false;

        if (!validation.ok() && config.repairOnInvalidPlan) {
            // One repair attempt only. A model that cannot fix its own block states
            // on the second try will not manage it on the fifth, and each attempt
            // costs the player another wait.
            String followUp = userPrompt + "\n\n"
                    + SystemPrompt.repair(validation.asFeedback(MAX_FEEDBACK_ISSUES));
            BuildPlan second = requestPlan(system, followUp);
            ValidationResult secondValidation = validator.validate(second);

            if (secondValidation.ok() || secondValidation.issues().size() < validation.issues().size()) {
                // Keep the better attempt even when it is still broken, so the error
                // the player sees describes the closest the model got.
                plan = second;
                validation = secondValidation;
                repaired = true;
            }
        }

        if (!validation.ok()) {
            throw new BuildFailedException("The plan it produced is not valid Minecraft:\n"
                    + validation.asFeedback(MAX_PLAYER_ISSUES));
        }

        List<Placement> placements;
        try {
            placements = PlanExpander.expand(plan, config.maxBlocks);
        } catch (PlanExpander.TooLargeException e) {
            throw new BuildFailedException("That build needs " + e.actual()
                    + " blocks, over the limit of " + e.limit()
                    + ". Ask for something smaller, or raise maxBlocks in the config.");
        }

        if (placements.isEmpty()) {
            throw new BuildFailedException("The plan came back empty, so there is nothing to build.");
        }

        return new Result(plan, PlacementOrderer.order(placements), repaired);
    }

    /**
     * Sends a prompt straight to the model and hands back exactly what came out,
     * unparsed and unvalidated.
     *
     * <p>For debugging. When a build comes out wrong the first question is always
     * whether the model said something odd or the mod mishandled something
     * sensible, and this is what tells the two apart.
     */
    public String ask(String userPrompt) throws BuildFailedException {
        String system = SystemPrompt.build(config.maxRadius, config.maxHeight, config.maxBlocks);
        try {
            return client.generatePlanJson(system, userPrompt);
        } catch (LlmException e) {
            throw new BuildFailedException(e.getMessage(), e);
        }
    }

    private BuildPlan requestPlan(String system, String prompt) throws BuildFailedException {
        String json;
        try {
            json = client.generatePlanJson(system, prompt);
        } catch (LlmException e) {
            throw new BuildFailedException(e.getMessage(), e);
        }

        try {
            return PlanParser.parse(json);
        } catch (PlanParser.MalformedPlanException e) {
            throw new BuildFailedException("Could not read the plan it returned: " + e.getMessage(), e);
        }
    }
}
