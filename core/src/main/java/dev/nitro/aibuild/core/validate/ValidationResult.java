package dev.nitro.aibuild.core.validate;

import java.util.List;
import java.util.stream.Collectors;

/** The outcome of checking a plan against the live block registry. */
public record ValidationResult(List<ValidationIssue> issues) {

    public ValidationResult {
        issues = List.copyOf(issues);
    }

    public boolean ok() {
        return issues.isEmpty();
    }

    /** Issues rendered for the repair prompt, capped so one bad plan cannot flood it. */
    public String asFeedback(int maxIssues) {
        return issues.stream()
                .limit(maxIssues)
                .map(ValidationIssue::toString)
                .collect(Collectors.joining("\n"));
    }
}
