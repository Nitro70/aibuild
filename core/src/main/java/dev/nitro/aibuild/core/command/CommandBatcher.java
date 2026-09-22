package dev.nitro.aibuild.core.command;

import dev.nitro.aibuild.core.plan.Pos;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns an ordered list of world blocks into {@code /setblock} and {@code /fill}
 * commands.
 *
 * <p>A straight run of the same block becomes one {@code /fill} rather than one
 * {@code /setblock} per block, which is what keeps a floor from costing hundreds
 * of commands. Only neighbouring entries in the list are ever merged, so the
 * order the placement pass worked out (support first, bottom up) is kept exactly.
 *
 * <p>Never emits the {@code strict} mode. Strict skips the neighbour shape pass,
 * which leaves panes, fences and stairs unconnected: exactly the bug the direct
 * build path had to be fixed for.
 */
public final class CommandBatcher {

    /** A block in world coordinates, already rotated, in /setblock syntax. */
    public record WorldBlock(Pos pos, String state) {}

    /**
     * Longest run merged into one fill. Well under the server's block
     * modification limit, and short enough that one rejected command costs little.
     */
    public static final int DEFAULT_MAX_RUN = 256;

    private CommandBatcher() {}

    /**
     * @param dimension pins every command to this dimension, e.g.
     *                  {@code minecraft:overworld}, so a player changing dimension
     *                  mid build cannot drag it along. Null or blank to omit.
     * @return commands without a leading slash, which is how the command packet
     *         wants them
     */
    public static List<String> toCommands(List<WorldBlock> blocks, String dimension, int maxRun) {
        String prefix = dimension == null || dimension.isBlank()
                ? ""
                : "execute in " + dimension.trim() + " run ";
        int limit = Math.max(1, maxRun);

        List<String> commands = new ArrayList<>();
        int i = 0;
        while (i < blocks.size()) {
            WorldBlock start = blocks.get(i);
            int end = i;
            Pos step = null;

            while (end + 1 < blocks.size() && end - i + 1 < limit) {
                WorldBlock previous = blocks.get(end);
                WorldBlock next = blocks.get(end + 1);
                if (!next.state().equals(start.state())) {
                    break;
                }
                Pos delta = difference(next.pos(), previous.pos());
                if (!isUnitStep(delta)) {
                    break;
                }
                if (step == null) {
                    step = delta;
                } else if (!step.equals(delta)) {
                    // Turning a corner would make the fill a box, not a line.
                    break;
                }
                end++;
            }

            if (end == i) {
                commands.add(prefix + setblock(start));
            } else {
                commands.add(prefix + fill(start.pos(), blocks.get(end).pos(), start.state()));
            }
            i = end + 1;
        }
        return commands;
    }

    public static List<String> toCommands(List<WorldBlock> blocks, String dimension) {
        return toCommands(blocks, dimension, DEFAULT_MAX_RUN);
    }

    private static String setblock(WorldBlock block) {
        Pos p = block.pos();
        return "setblock " + p.x() + " " + p.y() + " " + p.z() + " " + block.state();
    }

    private static String fill(Pos from, Pos to, String state) {
        return "fill " + from.x() + " " + from.y() + " " + from.z() + " "
                + to.x() + " " + to.y() + " " + to.z() + " " + state;
    }

    private static Pos difference(Pos a, Pos b) {
        return new Pos(a.x() - b.x(), a.y() - b.y(), a.z() - b.z());
    }

    /** Exactly one axis moves, by exactly one block. */
    private static boolean isUnitStep(Pos delta) {
        int moved = Math.abs(delta.x()) + Math.abs(delta.y()) + Math.abs(delta.z());
        return moved == 1;
    }
}
