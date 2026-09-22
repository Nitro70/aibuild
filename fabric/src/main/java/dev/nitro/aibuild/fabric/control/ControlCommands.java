package dev.nitro.aibuild.fabric.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.nitro.aibuild.core.BuildPipeline;
import dev.nitro.aibuild.core.config.AiBuildConfig;
import dev.nitro.aibuild.core.config.ProviderSettings;
import dev.nitro.aibuild.core.llm.LlmClient;
import dev.nitro.aibuild.core.llm.LlmClientFactory;
import dev.nitro.aibuild.core.llm.Provider;
import dev.nitro.aibuild.core.plan.BuildPlan;
import dev.nitro.aibuild.core.plan.Placement;
import dev.nitro.aibuild.core.plan.PlanParser;
import dev.nitro.aibuild.core.plan.Pos;
import dev.nitro.aibuild.core.transform.Cardinal;
import dev.nitro.aibuild.core.transform.CoordinateTransform;
import dev.nitro.aibuild.core.transform.PlanRotation;
import dev.nitro.aibuild.fabric.AiBuildMod;
import dev.nitro.aibuild.fabric.build.ActiveBuild;
import dev.nitro.aibuild.fabric.undo.BuildSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * What the control port can actually be asked to do.
 *
 * <p>Split across three kinds of command: run something through the model, act
 * on the world directly without a model involved, and look things up. The middle
 * one matters most for debugging, because it separates "the model said something
 * odd" from "the mod mishandled something sensible".
 */
public final class ControlCommands {

    /** World work is handed to the server thread; this is how long we wait for it. */
    private static final long SERVER_THREAD_TIMEOUT_SECONDS = 60;

    private final MinecraftServer server;

    public ControlCommands(MinecraftServer server) {
        this.server = server;
    }

    public JsonObject dispatch(JsonObject request) throws Exception {
        String command = request.has("cmd")
                ? request.get("cmd").getAsString().toLowerCase(Locale.ROOT)
                : "";

        return switch (command) {
            case "ping" -> ping();
            case "status" -> status();
            case "ask" -> ask(request);
            case "plan" -> plan(request);
            case "prompt" -> prompt(request);
            case "place" -> place(request);
            case "undo" -> undo(request);
            case "cancel" -> cancel(request);
            case "inspect" -> inspect(request);
            case "blocks" -> blocks(request);
            case "players" -> players();
            case "help", "" -> help();
            default -> error("unknown command '" + command + "'. Send {\"cmd\":\"help\"}.");
        };
    }

    // ------------------------------------------------------------- information

    private JsonObject ping() {
        JsonObject out = ok();
        out.addProperty("mod", "aibuild");
        out.addProperty("minecraft", net.minecraft.SharedConstants.getCurrentVersion().name());
        return out;
    }

    private JsonObject help() {
        JsonObject out = ok();
        JsonArray commands = new JsonArray();
        commands.add("ping - is it alive");
        commands.add("status - provider, model, limits, active builds");
        commands.add("ask {text} - send a prompt to the model, return its raw reply, build nothing");
        commands.add("plan {text} - model plus validation, return the plan, build nothing");
        commands.add("prompt {text, player?, origin?, facing?} - the full thing, builds in world");
        commands.add("place {plan:{name,ops}, player?, origin?, facing?} - build a plan directly, no model");
        commands.add("undo {player?} - undo the last build");
        commands.add("cancel {player?} - stop a running build");
        commands.add("inspect {x,y,z, dimension?} - read the block state actually in the world");
        commands.add("blocks {query, limit?} - search the block registry for real ids");
        commands.add("players - who is online");
        out.add("commands", commands);
        return out;
    }

    private JsonObject status() throws Exception {
        AiBuildConfig config = AiBuildMod.config();
        Provider provider = config.activeProvider();
        ProviderSettings settings = config.settingsFor(provider);

        JsonObject out = ok();
        out.addProperty("provider", provider.id());
        out.addProperty("providerName", provider.displayName());
        out.addProperty("model", settings.modelOrDefault(provider.defaultModel()));
        // Whether a key exists, never the key itself.
        out.addProperty("apiKeySet", !LlmClientFactory.resolveApiKey(provider, settings).isBlank());
        out.addProperty("maxBlocks", config.maxBlocks);
        out.addProperty("maxRadius", config.maxRadius);
        out.addProperty("maxHeight", config.maxHeight);
        out.addProperty("blocksPerTick", config.blocksPerTick);
        out.addProperty("flipRepeaterFacing", config.flipRepeaterFacing);
        out.addProperty("rotateToPlayer", config.rotateToPlayer);
        out.add("players", onServerThread(this::playerNames));
        return out;
    }

    private JsonObject players() throws Exception {
        JsonObject out = ok();
        out.add("players", onServerThread(this::playerNames));
        return out;
    }

    private JsonArray playerNames() {
        JsonArray names = new JsonArray();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            names.add(player.getGameProfile().name());
        }
        return names;
    }

    // ------------------------------------------------------------ model access

    /** Straight to the model, raw reply back, nothing built. */
    private JsonObject ask(JsonObject request) throws Exception {
        String text = requireString(request, "text");
        BuildPipeline pipeline = new BuildPipeline(
                client(), AiBuildMod.registry(), AiBuildMod.config());

        JsonObject out = ok();
        out.addProperty("raw", pipeline.ask(text));
        return out;
    }

    /** Model plus parse plus validate, nothing built. The usual debugging entry point. */
    private JsonObject plan(JsonObject request) throws Exception {
        String text = requireString(request, "text");
        AiBuildConfig config = AiBuildMod.config();
        BuildPipeline pipeline = new BuildPipeline(client(), AiBuildMod.registry(), config);

        BuildPipeline.Result result = pipeline.run(text);
        JsonObject out = ok();
        out.addProperty("name", result.plan().name());
        out.addProperty("notes", result.plan().notes());
        out.addProperty("repaired", result.repaired());
        out.addProperty("ops", result.plan().ops().size());
        out.addProperty("blocks", result.placements().size());
        out.add("placements", describePlacements(result.placements()));
        return out;
    }

    /** The whole thing, ending in blocks appearing in the world. */
    private JsonObject prompt(JsonObject request) throws Exception {
        String text = requireString(request, "text");
        Target target = resolveTarget(request);

        AiBuildConfig config = AiBuildMod.config();
        BuildPipeline pipeline = new BuildPipeline(client(), AiBuildMod.registry(), config);
        // Runs on this thread, which is already off the server thread, so the
        // blocking HTTP call cannot stall the game.
        BuildPipeline.Result result = pipeline.run(text);

        int scheduled = schedule(target, result.plan().name(), result.placements());
        JsonObject out = ok();
        out.addProperty("name", result.plan().name());
        out.addProperty("notes", result.plan().notes());
        out.addProperty("repaired", result.repaired());
        out.addProperty("blocks", scheduled);
        return out;
    }

    /**
     * Builds a plan supplied in the request, with no model involved.
     *
     * <p>The plan goes through exactly the same parser, validator and ordering as
     * a generated one, so a hand written plan cannot sneak past the block checks.
     */
    private JsonObject place(JsonObject request) throws Exception {
        if (!request.has("plan") || !request.get("plan").isJsonObject()) {
            return error("place needs a 'plan' object, shaped like {\"name\":\"...\",\"ops\":[...]}");
        }
        Target target = resolveTarget(request);
        AiBuildConfig config = AiBuildMod.config();

        BuildPlan plan = PlanParser.parse(request.getAsJsonObject("plan").toString());
        var validator = new dev.nitro.aibuild.core.validate.PlanValidator(
                AiBuildMod.registry(), config.blacklist, config.maxRadius, config.maxHeight);
        var validation = validator.validate(plan);
        if (!validation.ok()) {
            JsonObject out = error("that plan is not valid");
            JsonArray issues = new JsonArray();
            validation.issues().forEach(issue -> issues.add(issue.toString()));
            out.add("issues", issues);
            return out;
        }

        List<Placement> placements = dev.nitro.aibuild.core.order.PlacementOrderer.order(
                dev.nitro.aibuild.core.order.PlanExpander.expand(plan, config.maxBlocks));

        int scheduled = schedule(target, plan.name(), placements);
        JsonObject out = ok();
        out.addProperty("name", plan.name());
        out.addProperty("blocks", scheduled);
        return out;
    }

    // ------------------------------------------------------------- world access

    private JsonObject undo(JsonObject request) throws Exception {
        Target target = resolveTarget(request);
        BuildSnapshot restored = onServerThread(() ->
                AiBuildMod.undoStore().undo(target.owner, target.level));

        if (restored == null) {
            return error("nothing to undo for that player");
        }
        JsonObject out = ok();
        out.addProperty("name", restored.name());
        out.addProperty("blocks", restored.size());
        return out;
    }

    private JsonObject cancel(JsonObject request) throws Exception {
        Target target = resolveTarget(request);
        boolean cancelled = onServerThread(() -> AiBuildMod.scheduler().cancel(target.owner));
        JsonObject out = ok();
        out.addProperty("cancelled", cancelled);
        return out;
    }

    /** Reads what is genuinely in the world, which is how a build is checked. */
    private JsonObject inspect(JsonObject request) throws Exception {
        int x = requireInt(request, "x");
        int y = requireInt(request, "y");
        int z = requireInt(request, "z");

        ServerLevel level = resolveLevel(request);
        if (level == null) {
            return error("no such dimension");
        }

        BlockPos pos = new BlockPos(x, y, z);
        String state = onServerThread(() -> dev.nitro.aibuild.fabric.world.BlockStates.describe(level.getBlockState(pos)));

        JsonObject out = ok();
        out.addProperty("x", x);
        out.addProperty("y", y);
        out.addProperty("z", z);
        out.addProperty("block", state);
        return out;
    }

    /** Searches real block ids, for when a name is not quite right. */
    private JsonObject blocks(JsonObject request) {
        String query = request.has("query")
                ? request.get("query").getAsString().toLowerCase(Locale.ROOT)
                : "";
        int limit = request.has("limit") ? request.get("limit").getAsInt() : 50;

        JsonArray matches = new JsonArray();
        for (Identifier id : BuiltInRegistries.BLOCK.keySet()) {
            String text = id.toString();
            if (query.isEmpty() || text.contains(query)) {
                matches.add(text);
                if (matches.size() >= limit) {
                    break;
                }
            }
        }
        JsonObject out = ok();
        out.addProperty("matched", matches.size());
        out.add("blocks", matches);
        return out;
    }

    // ------------------------------------------------------------------ helpers

    /** Where a build should land: whose undo history, which world, which origin. */
    private record Target(java.util.UUID owner, ServerLevel level, CoordinateTransform transform) {}

    private Target resolveTarget(JsonObject request) throws Exception {
        AiBuildConfig config = AiBuildMod.config();

        Target resolved = onServerThread(() -> {
            ServerPlayer player = pickPlayer(request);
            if (player == null) {
                return null;
            }

            ServerLevel level = player.level() instanceof ServerLevel serverLevel
                    ? serverLevel
                    : server.overworld();

            Cardinal facing = request.has("facing")
                    ? cardinalOf(request.get("facing").getAsString())
                    : Cardinal.fromYaw(player.getYRot());

            Pos origin;
            if (request.has("origin")) {
                JsonObject o = request.getAsJsonObject("origin");
                origin = new Pos(o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("z").getAsInt());
            } else {
                BlockPos base = player.blockPosition();
                origin = offsetFrom(base, facing, config.originOffset);
            }

            PlanRotation rotation = config.rotateToPlayer
                    ? PlanRotation.toFace(facing)
                    : PlanRotation.NONE;

            return new Target(player.getUUID(), level, new CoordinateTransform(origin, rotation));
        });

        if (resolved == null) {
            throw new IllegalStateException(
                    "no player to build for. Join the world, or pass \"player\":\"name\".");
        }
        return resolved;
    }

    private ServerPlayer pickPlayer(JsonObject request) {
        if (request.has("player")) {
            return server.getPlayerList().getPlayerByName(request.get("player").getAsString());
        }
        List<ServerPlayer> online = server.getPlayerList().getPlayers();
        return online.isEmpty() ? null : online.get(0);
    }

    private ServerLevel resolveLevel(JsonObject request) throws Exception {
        if (!request.has("dimension")) {
            return onServerThread(() -> {
                ServerPlayer player = pickPlayer(request);
                return player != null && player.level() instanceof ServerLevel level
                        ? level
                        : server.overworld();
            });
        }
        String wanted = request.get("dimension").getAsString();
        return onServerThread(() -> {
            for (ServerLevel level : server.getAllLevels()) {
                if (level.dimension().identifier().toString().equals(wanted)) {
                    return level;
                }
            }
            return null;
        });
    }

    private int schedule(Target target, String name, List<Placement> placements) throws Exception {
        AiBuildConfig config = AiBuildMod.config();
        return onServerThread(() -> {
            AiBuildMod.scheduler().start(new ActiveBuild(
                    target.owner, target.level, name, placements, target.transform,
                    config.flipRepeaterFacing,
                    new BuildSnapshot(name, target.level.dimension())));
            return placements.size();
        });
    }

    private LlmClient client() throws LlmClientFactory.NotConfiguredException {
        return LlmClientFactory.create(AiBuildMod.config());
    }

    private static Pos offsetFrom(BlockPos base, Cardinal facing, int distance) {
        return switch (facing) {
            case NORTH -> new Pos(base.getX(), base.getY(), base.getZ() - distance);
            case SOUTH -> new Pos(base.getX(), base.getY(), base.getZ() + distance);
            case EAST -> new Pos(base.getX() + distance, base.getY(), base.getZ());
            case WEST -> new Pos(base.getX() - distance, base.getY(), base.getZ());
        };
    }

    private static Cardinal cardinalOf(String text) {
        return switch (text.toLowerCase(Locale.ROOT)) {
            case "north" -> Cardinal.NORTH;
            case "east" -> Cardinal.EAST;
            case "west" -> Cardinal.WEST;
            default -> Cardinal.SOUTH;
        };
    }

    private static JsonArray describePlacements(List<Placement> placements) {
        JsonArray array = new JsonArray();
        for (Placement placement : placements) {
            JsonObject entry = new JsonObject();
            entry.addProperty("x", placement.pos().x());
            entry.addProperty("y", placement.pos().y());
            entry.addProperty("z", placement.pos().z());
            entry.addProperty("block", placement.block().toString());
            array.add(entry);
        }
        return array;
    }

    private <T> T onServerThread(Supplier<T> action) throws Exception {
        return server.submit(action).get(SERVER_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static String requireString(JsonObject request, String field) {
        if (!request.has(field) || request.get(field).isJsonNull()) {
            throw new IllegalArgumentException("missing '" + field + "'");
        }
        return request.get(field).getAsString();
    }

    private static int requireInt(JsonObject request, String field) {
        if (!request.has(field) || request.get(field).isJsonNull()) {
            throw new IllegalArgumentException("missing '" + field + "'");
        }
        return request.get(field).getAsInt();
    }

    static JsonObject ok() {
        JsonObject out = new JsonObject();
        out.addProperty("ok", true);
        return out;
    }

    static JsonObject error(String message) {
        JsonObject out = new JsonObject();
        out.addProperty("ok", false);
        out.addProperty("error", message == null ? "unknown error" : message);
        return out;
    }
}
