package dev.nitro.aibuild.core.plan;

import java.util.List;

/**
 * A complete build as returned by the model.
 *
 * @param name  short label, shown to the player and used for the undo history
 * @param notes the model's own commentary, shown on request. May be empty.
 * @param ops   the instructions, in the order the model emitted them
 */
public record BuildPlan(String name, String notes, List<Op> ops) {

    public BuildPlan {
        name = name == null || name.isBlank() ? "build" : name.trim();
        notes = notes == null ? "" : notes.trim();
        ops = List.copyOf(ops);
    }

    /** Total blocks this plan touches before duplicate positions are collapsed. */
    public long rawBlockCount() {
        long total = 0;
        for (Op op : ops) {
            total += switch (op) {
                case Op.Place ignored -> 1L;
                case Op.Fill fill -> fill.volume();
            };
        }
        return total;
    }
}
