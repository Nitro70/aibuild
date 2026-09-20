package dev.nitro.aibuild.core.validate;

import dev.nitro.aibuild.core.block.BlockRegistry;
import dev.nitro.aibuild.core.plan.BlockSpec;
import dev.nitro.aibuild.core.plan.BuildPlan;
import dev.nitro.aibuild.core.plan.Op;
import dev.nitro.aibuild.core.plan.Pos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks a plan against the live registry before a single block is written.
 *
 * <p>Catching a bad block state here rather than in the world is the whole point:
 * the issues produced are specific enough to hand straight back to the model as a
 * repair prompt.
 */
public final class PlanValidator {

    private final BlockRegistry registry;
    private final Set<String> blacklist;
    private final int maxRadius;
    private final int maxHeight;

    /**
     * @param blacklist namespaced ids the server refuses to place at all
     * @param maxRadius largest allowed distance from the origin on X and Z
     * @param maxHeight largest allowed distance from the origin on Y, both directions
     */
    public PlanValidator(BlockRegistry registry, Set<String> blacklist, int maxRadius, int maxHeight) {
        this.registry = registry;
        this.blacklist = Set.copyOf(blacklist);
        this.maxRadius = maxRadius;
        this.maxHeight = maxHeight;
    }

    public ValidationResult validate(BuildPlan plan) {
        List<ValidationIssue> issues = new ArrayList<>();

        if (plan.ops().isEmpty()) {
            issues.add(ValidationIssue.plan("the plan contains no ops, so nothing would be built"));
            return new ValidationResult(issues);
        }

        List<Op> ops = plan.ops();
        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            checkBlock(i, op.block(), issues);
            switch (op) {
                case Op.Place place -> checkBounds(i, place.pos(), issues);
                case Op.Fill fill -> {
                    checkBounds(i, fill.from(), issues);
                    checkBounds(i, fill.to(), issues);
                }
            }
        }
        return new ValidationResult(issues);
    }

    private void checkBlock(int index, BlockSpec spec, List<ValidationIssue> issues) {
        String id = spec.id();

        if (blacklist.contains(id)) {
            issues.add(new ValidationIssue(index, "'" + id + "' is not allowed on this server"));
            return;
        }
        if (!registry.hasBlock(id)) {
            issues.add(new ValidationIssue(index,
                    "'" + id + "' is not a real block in this version of Minecraft"));
            return;
        }

        Set<String> known = registry.propertyNames(id);
        for (Map.Entry<String, String> e : spec.properties().entrySet()) {
            String name = e.getKey();
            String value = e.getValue();

            if (!known.contains(name)) {
                issues.add(new ValidationIssue(index, "'" + id + "' has no state property '" + name
                        + "'. It accepts: " + joined(known)));
                continue;
            }
            Set<String> legal = registry.propertyValues(id, name);
            if (!legal.contains(value)) {
                issues.add(new ValidationIssue(index, "'" + value + "' is not a legal value for '"
                        + name + "' on '" + id + "'. Legal values: " + joined(legal)));
            }
        }
    }

    private void checkBounds(int index, Pos pos, List<ValidationIssue> issues) {
        if (Math.abs(pos.x()) > maxRadius || Math.abs(pos.z()) > maxRadius) {
            issues.add(new ValidationIssue(index, "position " + pos + " is further than "
                    + maxRadius + " blocks from the origin on X or Z"));
        }
        if (Math.abs(pos.y()) > maxHeight) {
            issues.add(new ValidationIssue(index, "position " + pos + " is further than "
                    + maxHeight + " blocks from the origin on Y"));
        }
    }

    private static String joined(Set<String> values) {
        if (values.isEmpty()) {
            return "(none)";
        }
        // Long enumerations waste repair-prompt budget without adding information.
        List<String> sorted = new ArrayList<>(values);
        sorted.sort(String::compareTo);
        if (sorted.size() > 12) {
            return String.join(", ", sorted.subList(0, 12)) + ", ... (" + sorted.size() + " total)";
        }
        return String.join(", ", sorted);
    }
}
