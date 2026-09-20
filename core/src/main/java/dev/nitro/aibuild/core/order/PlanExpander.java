package dev.nitro.aibuild.core.order;

import dev.nitro.aibuild.core.plan.BlockSpec;
import dev.nitro.aibuild.core.plan.BuildPlan;
import dev.nitro.aibuild.core.plan.Op;
import dev.nitro.aibuild.core.plan.Placement;
import dev.nitro.aibuild.core.plan.Pos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Flattens a plan's ops into one write per position, with later ops winning. */
public final class PlanExpander {

    private PlanExpander() {}

    /** Raised when a plan would touch more blocks than the configured cap. */
    public static final class TooLargeException extends RuntimeException {
        private final long actual;
        private final long limit;

        TooLargeException(long actual, long limit) {
            super("plan covers " + actual + " blocks, which is over the limit of " + limit);
            this.actual = actual;
            this.limit = limit;
        }

        public long actual() {
            return actual;
        }

        public long limit() {
            return limit;
        }
    }

    /**
     * @param maxBlocks hard cap on distinct positions written
     * @throws TooLargeException if the cap would be exceeded
     */
    public static List<Placement> expand(BuildPlan plan, long maxBlocks) {
        // Check the cheap upper bound first so a runaway fill is rejected before
        // it is materialised into memory.
        long raw = plan.rawBlockCount();
        if (raw > maxBlocks * 8L) {
            throw new TooLargeException(raw, maxBlocks);
        }

        Map<Pos, BlockSpec> byPos = new LinkedHashMap<>();
        for (Op op : plan.ops()) {
            switch (op) {
                case Op.Place place -> byPos.put(place.pos(), place.block());
                case Op.Fill fill -> expandFill(fill, byPos);
            }
            if (byPos.size() > maxBlocks) {
                throw new TooLargeException(byPos.size(), maxBlocks);
            }
        }

        List<Placement> out = new ArrayList<>(byPos.size());
        for (Map.Entry<Pos, BlockSpec> e : byPos.entrySet()) {
            out.add(new Placement(e.getKey(), e.getValue()));
        }
        return out;
    }

    private static void expandFill(Op.Fill fill, Map<Pos, BlockSpec> into) {
        Pos a = fill.from();
        Pos b = fill.to();
        int x0 = Math.min(a.x(), b.x());
        int x1 = Math.max(a.x(), b.x());
        int y0 = Math.min(a.y(), b.y());
        int y1 = Math.max(a.y(), b.y());
        int z0 = Math.min(a.z(), b.z());
        int z1 = Math.max(a.z(), b.z());

        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    into.put(new Pos(x, y, z), fill.block());
                }
            }
        }
    }
}
