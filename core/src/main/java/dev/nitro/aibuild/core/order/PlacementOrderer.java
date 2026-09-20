package dev.nitro.aibuild.core.order;

import dev.nitro.aibuild.core.block.BlockCategory;
import dev.nitro.aibuild.core.block.BlockClassifier;
import dev.nitro.aibuild.core.plan.Placement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Sorts placements into the order they must actually be written.
 *
 * <p>Two passes: everything that stands on its own, bottom up, then everything
 * that clings to something. Without this, dust and torches are placed into empty
 * air and drop as items, which is the single most common way an otherwise
 * correct plan produces a broken contraption.
 */
public final class PlacementOrderer {

    private PlacementOrderer() {}

    private static final Comparator<Placement> BOTTOM_UP =
            Comparator.<Placement>comparingInt(p -> p.pos().y())
                    .thenComparingInt(p -> p.pos().x())
                    .thenComparingInt(p -> p.pos().z());

    public static List<Placement> order(List<Placement> placements) {
        List<Placement> structural = new ArrayList<>();
        List<Placement> attached = new ArrayList<>();

        for (Placement p : placements) {
            if (BlockClassifier.classify(p.block()) == BlockCategory.STRUCTURAL) {
                structural.add(p);
            } else {
                attached.add(p);
            }
        }

        structural.sort(BOTTOM_UP);
        attached.sort(BOTTOM_UP);

        List<Placement> out = new ArrayList<>(placements.size());
        out.addAll(structural);
        out.addAll(attached);
        return out;
    }
}
