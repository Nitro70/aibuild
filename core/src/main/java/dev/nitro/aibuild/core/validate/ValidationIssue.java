package dev.nitro.aibuild.core.validate;

/**
 * One problem with a plan.
 *
 * @param opIndex index into the plan's op list, or -1 for a whole plan problem
 * @param message wording aimed at the model, since these are fed back verbatim
 *                when asking it to repair its own output
 */
public record ValidationIssue(int opIndex, String message) {

    public static ValidationIssue plan(String message) {
        return new ValidationIssue(-1, message);
    }

    @Override
    public String toString() {
        return opIndex < 0 ? message : "op " + opIndex + ": " + message;
    }
}
