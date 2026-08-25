package local.peacefulfarms.pfhopper;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * Client-only helper for Peaceful Farms PF Hoppers and Infinite Storage Barrels.
 *
 * The server still sees an ordinary client. Detection is based only on blocks,
 * GUI titles, and normal server chat messages already visible to the player.
 */
public final class PFHopperHighlighterClient implements ClientModInitializer {
    private static final String HOPPER_PLACED_MESSAGE = "successfully placed a pfhopper";
    private static final String HOPPER_REMOVED_MESSAGE = "successfully remove pfhopper";
    private static final String HOPPER_TITLE = "PF Hopper";

    private static final String BARREL_PLACED_MESSAGE = "infinite barrel placed successfully";
    private static final String BARREL_TITLE = "PFBarrel Storage";

    private static final long CANDIDATE_TTL_MS = 5_000L;

    private static final int HOPPER_COLOR = 0xFFFFA500; // orange
    private static final int BARREL_COLOR = 0xFF00FFFF; // cyan
    private static final double HOPPER_BOX_SIZE = 0.25;
    private static final double BARREL_BOX_SIZE = 0.42;

    private static boolean outlinesEnabled = true;
    private static boolean hoppersEnabled = true;
    private static boolean barrelsEnabled = true;
    private static int renderRange = 128;

    // MC 26.1.2 does not expose RenderType.lines(). Define the line layer
    // explicitly. Depth is disabled so tracked storage remains visible through walls.
    private static final RenderPipeline HIGHLIGHT_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("pfhopperhighlighter", "storage_outline"))
                    .withVertexFormat(DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH, VertexFormat.Mode.LINES)
                    .withDepthStencilState(Optional.empty())
                    .build()
    );
    private static final RenderType HIGHLIGHT_LAYER = RenderType.create(
            "pfhopperhighlighter_storage_outline",
            RenderSetup.builder(HIGHLIGHT_PIPELINE).createRenderSetup()
    );

    // Keep the original hopper file so existing tracked PF Hoppers survive this update.
    private static final Path HOPPER_DATA_FILE = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("pf-hopper-highlighter.tsv");
    private static final Path BARREL_DATA_FILE = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("pf-barrel-highlighter.tsv");
    private static final Path CONFIG_FILE = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("pf-helper.properties");

    private static final Set<TrackedStorage> TRACKED_HOPPERS = new HashSet<>();
    private static final Set<TrackedStorage> TRACKED_BARRELS = new HashSet<>();

    private static BlockPos pendingPlacementNear;
    private static StorageType pendingPlacementType;
    private static long pendingPlacementUntil;

    private static BlockPos lastInteractedStorage;
    private static StorageType lastInteractedType;
    private static long lastInteractionUntil;

    private static BlockPos pendingRemoval;
    private static StorageType pendingRemovalType;
    private static long pendingRemovalUntil;

    @Override
    public void onInitializeClient() {
        loadConfig();
        loadTracked(HOPPER_DATA_FILE, TRACKED_HOPPERS);
        loadTracked(BARREL_DATA_FILE, TRACKED_BARRELS);

        registerCommands();
        UseBlockCallback.EVENT.register(PFHopperHighlighterClient::onUseBlock);
        AttackBlockCallback.EVENT.register(PFHopperHighlighterClient::onAttackBlock);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onGameMessage(message.getString()));
        ClientTickEvents.END_CLIENT_TICK.register(PFHopperHighlighterClient::onClientTick);
        LevelRenderEvents.BEFORE_GIZMOS.register(PFHopperHighlighterClient::renderHighlights);
    }

    private static void registerCommands() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
                ClientCommands.literal("pfhelper")
                        .executes(context -> showStatus(context.getSource()))
                        .then(ClientCommands.literal("status")
                                .executes(context -> showStatus(context.getSource())))
                        .then(ClientCommands.literal("help")
                                .executes(context -> showHelp(context.getSource())))
                        .then(ClientCommands.literal("outlines")
                                .then(ClientCommands.literal("toggle").executes(context -> {
                                    outlinesEnabled = !outlinesEnabled;
                                    saveConfig();
                                    feedback(context.getSource(), "Outlines " + onOff(outlinesEnabled) + ".");
                                    return 1;
                                }))
                                .then(ClientCommands.literal("on").executes(context -> setOutlines(context.getSource(), true)))
                                .then(ClientCommands.literal("off").executes(context -> setOutlines(context.getSource(), false))))
                        .then(ClientCommands.literal("hoppers")
                                .then(ClientCommands.literal("toggle").executes(context -> {
                                    hoppersEnabled = !hoppersEnabled;
                                    saveConfig();
                                    feedback(context.getSource(), "PF Hopper outlines " + onOff(hoppersEnabled) + ".");
                                    return 1;
                                }))
                                .then(ClientCommands.literal("on").executes(context -> setHoppers(context.getSource(), true)))
                                .then(ClientCommands.literal("off").executes(context -> setHoppers(context.getSource(), false))))
                        .then(ClientCommands.literal("barrels")
                                .then(ClientCommands.literal("toggle").executes(context -> {
                                    barrelsEnabled = !barrelsEnabled;
                                    saveConfig();
                                    feedback(context.getSource(), "Infinite Barrel outlines " + onOff(barrelsEnabled) + ".");
                                    return 1;
                                }))
                                .then(ClientCommands.literal("on").executes(context -> setBarrels(context.getSource(), true)))
                                .then(ClientCommands.literal("off").executes(context -> setBarrels(context.getSource(), false))))
                        .then(ClientCommands.literal("range")
                                .executes(context -> {
                                    feedback(context.getSource(), "Outline render distance: " + renderRange + " blocks.");
                                    return 1;
                                })
                                .then(ClientCommands.argument("blocks", IntegerArgumentType.integer(8, 2048))
                                        .executes(context -> {
                                            renderRange = IntegerArgumentType.getInteger(context, "blocks");
                                            saveConfig();
                                            feedback(context.getSource(), "Outline render distance set to " + renderRange + " blocks.");
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("count")
                                .executes(context -> countCurrentChunk(context.getSource())))
        ));
    }

    private static int showHelp(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source) {
        feedback(source, "/pfhelper status - show current settings");
        feedback(source, "/pfhelper outlines <toggle|on|off> - all wireframes");
        feedback(source, "/pfhelper hoppers <toggle|on|off> - PF Hopper wireframes");
        feedback(source, "/pfhelper barrels <toggle|on|off> - Infinite Barrel wireframes");
        feedback(source, "/pfhelper range <8-2048> - render distance in blocks");
        feedback(source, "/pfhelper count - tracked PF Hoppers in your current chunk");
        return 1;
    }

    private static int showStatus(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source) {
        feedback(source, "PF Helper: outlines " + onOff(outlinesEnabled)
                + ", hoppers " + onOff(hoppersEnabled)
                + ", barrels " + onOff(barrelsEnabled)
                + ", range " + renderRange + " blocks.");
        return 1;
    }

    private static int setOutlines(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, boolean enabled) {
        outlinesEnabled = enabled;
        saveConfig();
        feedback(source, "Outlines " + onOff(enabled) + ".");
        return 1;
    }

    private static int setHoppers(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, boolean enabled) {
        hoppersEnabled = enabled;
        saveConfig();
        feedback(source, "PF Hopper outlines " + onOff(enabled) + ".");
        return 1;
    }

    private static int setBarrels(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, boolean enabled) {
        barrelsEnabled = enabled;
        saveConfig();
        feedback(source, "Infinite Barrel outlines " + onOff(enabled) + ".");
        return 1;
    }

    private static int countCurrentChunk(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            feedback(source, "Join a world first.");
            return 0;
        }

        String server = serverKey(minecraft);
        String dimension = dimensionKey(minecraft);
        BlockPos playerPos = minecraft.player.blockPosition();
        int chunkX = playerPos.getX() >> 4;
        int chunkZ = playerPos.getZ() >> 4;

        long hopperCount = TRACKED_HOPPERS.stream()
                .filter(tracked -> tracked.server.equals(server) && tracked.dimension.equals(dimension))
                .filter(tracked -> (tracked.x >> 4) == chunkX && (tracked.z >> 4) == chunkZ)
                .count();
        long barrelCount = TRACKED_BARRELS.stream()
                .filter(tracked -> tracked.server.equals(server) && tracked.dimension.equals(dimension))
                .filter(tracked -> (tracked.x >> 4) == chunkX && (tracked.z >> 4) == chunkZ)
                .count();

        feedback(source, "Chunk " + chunkX + ", " + chunkZ + ": " + hopperCount
                + " tracked PF Hopper" + (hopperCount == 1 ? "" : "s")
                + " (plus " + barrelCount + " tracked Infinite Barrel" + (barrelCount == 1 ? "" : "s") + ").");
        return 1;
    }

    private static void feedback(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, String message) {
        source.sendFeedback(Component.literal("[PF Helper] " + message));
    }

    private static String onOff(boolean enabled) {
        return enabled ? "ON" : "OFF";
    }

    private static InteractionResult onUseBlock(
            net.minecraft.world.entity.player.Player player,
            net.minecraft.world.level.Level level,
            InteractionHand hand,
            BlockHitResult hitResult
    ) {
        if (!level.isClientSide() || hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }

        long now = System.currentTimeMillis();
        BlockPos clicked = hitResult.getBlockPos();

        if (level.getBlockState(clicked).is(Blocks.HOPPER)) {
            lastInteractedStorage = clicked.immutable();
            lastInteractedType = StorageType.HOPPER;
            lastInteractionUntil = now + CANDIDATE_TTL_MS;
        } else if (level.getBlockState(clicked).is(Blocks.BARREL)) {
            lastInteractedStorage = clicked.immutable();
            lastInteractedType = StorageType.BARREL;
            lastInteractionUntil = now + CANDIDATE_TTL_MS;
        }

        if (player.getItemInHand(hand).is(Items.HOPPER)) {
            pendingPlacementNear = clicked.relative(hitResult.getDirection()).immutable();
            pendingPlacementType = StorageType.HOPPER;
            pendingPlacementUntil = now + CANDIDATE_TTL_MS;
        } else if (player.getItemInHand(hand).is(Items.BARREL)) {
            pendingPlacementNear = clicked.relative(hitResult.getDirection()).immutable();
            pendingPlacementType = StorageType.BARREL;
            pendingPlacementUntil = now + CANDIDATE_TTL_MS;
        }

        return InteractionResult.PASS;
    }

    private static InteractionResult onAttackBlock(
            net.minecraft.world.entity.player.Player player,
            net.minecraft.world.level.Level level,
            InteractionHand hand,
            BlockPos pos,
            net.minecraft.core.Direction direction
    ) {
        if (!level.isClientSide()) {
            return InteractionResult.PASS;
        }

        StorageType trackedType = trackedTypeAt(pos);
        if (trackedType != null) {
            pendingRemoval = pos.immutable();
            pendingRemovalType = trackedType;
            pendingRemovalUntil = System.currentTimeMillis() + CANDIDATE_TTL_MS;
        }

        return InteractionResult.PASS;
    }

    private static void onGameMessage(String raw) {
        String normalized = normalize(raw);
        long now = System.currentTimeMillis();

        if (normalized.contains(HOPPER_PLACED_MESSAGE)) {
            confirmPlacement(StorageType.HOPPER, now);
            return;
        }

        if (normalized.contains(BARREL_PLACED_MESSAGE)) {
            confirmPlacement(StorageType.BARREL, now);
            return;
        }

        if (normalized.contains(HOPPER_REMOVED_MESSAGE)) {
            confirmRemoval(StorageType.HOPPER, now);
            return;
        }

        // The exact PFBarrel removal wording can vary. Only accept a barrel removal
        // message while the player has a very recent tracked-barrel break candidate.
        if (pendingRemovalType == StorageType.BARREL
                && now <= pendingRemovalUntil
                && normalized.contains("barrel")
                && (normalized.contains("remove") || normalized.contains("removed") || normalized.contains("broken"))) {
            confirmRemoval(StorageType.BARREL, now);
        }
    }

    private static void confirmPlacement(StorageType type, long now) {
        if (pendingPlacementType == type && pendingPlacementNear != null && now <= pendingPlacementUntil) {
            BlockPos actual = findNearbyStorage(pendingPlacementNear, 2, type);
            if (actual != null) {
                addCurrent(type, actual);
            }
        }
        clearPlacementCandidate();
    }

    private static void confirmRemoval(StorageType type, long now) {
        if (pendingRemovalType == type && pendingRemoval != null && now <= pendingRemovalUntil) {
            removeCurrent(type, pendingRemoval);
        }
        clearRemovalCandidate();
    }

    private static void onClientTick(Minecraft minecraft) {
        long now = System.currentTimeMillis();

        if (pendingPlacementNear != null && now > pendingPlacementUntil) {
            clearPlacementCandidate();
        }
        if (pendingRemoval != null && now > pendingRemovalUntil) {
            clearRemovalCandidate();
        }
        if (lastInteractedStorage != null && now > lastInteractionUntil) {
            clearInteractionCandidate();
        }

        // If a tracked Infinite Barrel was actually broken and no useful server removal
        // message is emitted, remove it after the client receives the block update.
        if (pendingRemoval != null
                && pendingRemovalType == StorageType.BARREL
                && minecraft.level != null
                && minecraft.level.hasChunkAt(pendingRemoval)
                && !minecraft.level.getBlockState(pendingRemoval).is(Blocks.BARREL)) {
            removeCurrent(StorageType.BARREL, pendingRemoval);
            clearRemovalCandidate();
        }

        if (minecraft.level == null || minecraft.screen == null || lastInteractedStorage == null || lastInteractedType == null) {
            return;
        }

        String title = minecraft.screen.getTitle().getString();
        if (now <= lastInteractionUntil) {
            if (lastInteractedType == StorageType.HOPPER
                    && HOPPER_TITLE.equalsIgnoreCase(title)
                    && minecraft.level.getBlockState(lastInteractedStorage).is(Blocks.HOPPER)) {
                addCurrent(StorageType.HOPPER, lastInteractedStorage);
                clearInteractionCandidate();
            } else if (lastInteractedType == StorageType.BARREL
                    && normalize(title).contains(normalize(BARREL_TITLE))
                    && minecraft.level.getBlockState(lastInteractedStorage).is(Blocks.BARREL)) {
                addCurrent(StorageType.BARREL, lastInteractedStorage);
                clearInteractionCandidate();
            }
        }
    }

    private static void renderHighlights(LevelRenderContext context) {
        if (!outlinesEnabled) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }

        PoseStack matrices = context.poseStack();
        if (matrices == null) {
            return;
        }

        String server = serverKey(minecraft);
        String dimension = dimensionKey(minecraft);
        BlockPos playerPos = minecraft.player.blockPosition();
        double maxDistanceSq = (double) renderRange * renderRange;
        VertexConsumer vertices = context.bufferSource().getBuffer(HIGHLIGHT_LAYER);
        Vec3 camera = minecraft.gameRenderer.getMainCamera().position();

        if (hoppersEnabled) {
            renderTrackedSet(minecraft, matrices.last(), vertices, camera, playerPos,
                    server, dimension, maxDistanceSq, TRACKED_HOPPERS,
                    StorageType.HOPPER, HOPPER_BOX_SIZE, HOPPER_COLOR);
        }

        if (barrelsEnabled) {
            renderTrackedSet(minecraft, matrices.last(), vertices, camera, playerPos,
                    server, dimension, maxDistanceSq, TRACKED_BARRELS,
                    StorageType.BARREL, BARREL_BOX_SIZE, BARREL_COLOR);
        }
    }

    private static void renderTrackedSet(
            Minecraft minecraft,
            PoseStack.Pose pose,
            VertexConsumer vertices,
            Vec3 camera,
            BlockPos playerPos,
            String server,
            String dimension,
            double maxDistanceSq,
            Set<TrackedStorage> trackedSet,
            StorageType type,
            double boxSize,
            int color
    ) {
        for (TrackedStorage tracked : trackedSet) {
            if (!tracked.server.equals(server) || !tracked.dimension.equals(dimension)) {
                continue;
            }

            BlockPos pos = tracked.pos();
            if (playerPos.distSqr(pos) > maxDistanceSq) {
                continue;
            }

            if (minecraft.level.hasChunkAt(pos) && !isExpectedBlock(minecraft, pos, type)) {
                continue;
            }

            AABB box = centeredBox(pos, boxSize).move(-camera.x, -camera.y, -camera.z);
            drawBox(pose, vertices, box, color);
        }
    }

    private static AABB centeredBox(BlockPos pos, double size) {
        double half = size / 2.0;
        double centerX = pos.getX() + 0.5;
        double centerY = pos.getY() + 0.5;
        double centerZ = pos.getZ() + 0.5;
        return new AABB(
                centerX - half, centerY - half, centerZ - half,
                centerX + half, centerY + half, centerZ + half
        );
    }

    private static void drawBox(PoseStack.Pose pose, VertexConsumer vertices, AABB box, int color) {
        line(pose, vertices, box.minX, box.minY, box.minZ, box.maxX, box.minY, box.minZ, color);
        line(pose, vertices, box.maxX, box.minY, box.minZ, box.maxX, box.minY, box.maxZ, color);
        line(pose, vertices, box.maxX, box.minY, box.maxZ, box.minX, box.minY, box.maxZ, color);
        line(pose, vertices, box.minX, box.minY, box.maxZ, box.minX, box.minY, box.minZ, color);

        line(pose, vertices, box.minX, box.maxY, box.minZ, box.maxX, box.maxY, box.minZ, color);
        line(pose, vertices, box.maxX, box.maxY, box.minZ, box.maxX, box.maxY, box.maxZ, color);
        line(pose, vertices, box.maxX, box.maxY, box.maxZ, box.minX, box.maxY, box.maxZ, color);
        line(pose, vertices, box.minX, box.maxY, box.maxZ, box.minX, box.maxY, box.minZ, color);

        line(pose, vertices, box.minX, box.minY, box.minZ, box.minX, box.maxY, box.minZ, color);
        line(pose, vertices, box.maxX, box.minY, box.minZ, box.maxX, box.maxY, box.minZ, color);
        line(pose, vertices, box.maxX, box.minY, box.maxZ, box.maxX, box.maxY, box.maxZ, color);
        line(pose, vertices, box.minX, box.minY, box.maxZ, box.minX, box.maxY, box.maxZ, color);
    }

    private static void line(PoseStack.Pose pose, VertexConsumer vertices,
                             double x1, double y1, double z1,
                             double x2, double y2, double z2, int color) {
        float nx = (float) (x2 - x1);
        float ny = (float) (y2 - y1);
        float nz = (float) (z2 - z1);
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 0.0F) {
            nx /= len;
            ny /= len;
            nz /= len;
        }

        vertices.addVertex(pose, (float) x1, (float) y1, (float) z1)
                .setColor(color)
                .setNormal(pose, nx, ny, nz)
                .setLineWidth(3.0F);
        vertices.addVertex(pose, (float) x2, (float) y2, (float) z2)
                .setColor(color)
                .setNormal(pose, nx, ny, nz)
                .setLineWidth(3.0F);
    }

    private static BlockPos findNearbyStorage(BlockPos expected, int radius, StorageType type) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }

        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos candidate = expected.offset(dx, dy, dz);
                    if (!isExpectedBlock(minecraft, candidate, type)) {
                        continue;
                    }

                    double distance = expected.distSqr(candidate);
                    if (distance < bestDistance) {
                        best = candidate.immutable();
                        bestDistance = distance;
                    }
                }
            }
        }

        return best;
    }

    private static boolean isExpectedBlock(Minecraft minecraft, BlockPos pos, StorageType type) {
        if (minecraft.level == null) {
            return false;
        }
        return type == StorageType.HOPPER
                ? minecraft.level.getBlockState(pos).is(Blocks.HOPPER)
                : minecraft.level.getBlockState(pos).is(Blocks.BARREL);
    }

    private static StorageType trackedTypeAt(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }

        String server = serverKey(minecraft);
        String dimension = dimensionKey(minecraft);
        TrackedStorage key = new TrackedStorage(server, dimension, pos.getX(), pos.getY(), pos.getZ());
        if (TRACKED_HOPPERS.contains(key)) {
            return StorageType.HOPPER;
        }
        if (TRACKED_BARRELS.contains(key)) {
            return StorageType.BARREL;
        }
        return null;
    }

    private static void addCurrent(StorageType type, BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        TrackedStorage storage = new TrackedStorage(
                serverKey(minecraft), dimensionKey(minecraft),
                pos.getX(), pos.getY(), pos.getZ()
        );
        Set<TrackedStorage> target = type == StorageType.HOPPER ? TRACKED_HOPPERS : TRACKED_BARRELS;
        if (target.add(storage)) {
            saveTracked(type == StorageType.HOPPER ? HOPPER_DATA_FILE : BARREL_DATA_FILE, target);
        }
    }

    private static void removeCurrent(StorageType type, BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        TrackedStorage storage = new TrackedStorage(
                serverKey(minecraft), dimensionKey(minecraft),
                pos.getX(), pos.getY(), pos.getZ()
        );
        Set<TrackedStorage> target = type == StorageType.HOPPER ? TRACKED_HOPPERS : TRACKED_BARRELS;
        if (target.remove(storage)) {
            saveTracked(type == StorageType.HOPPER ? HOPPER_DATA_FILE : BARREL_DATA_FILE, target);
        }
    }

    private static String serverKey(Minecraft minecraft) {
        ServerData server = minecraft.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isBlank()) {
            return server.ip.toLowerCase(Locale.ROOT);
        }
        return minecraft.isLocalServer() ? "singleplayer" : "unknown-server";
    }

    private static String dimensionKey(Minecraft minecraft) {
        if (minecraft.level == null) {
            return "unknown-dimension";
        }
        return minecraft.level.dimension().identifier().toString();
    }

    private static String normalize(String raw) {
        return raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private static void clearPlacementCandidate() {
        pendingPlacementNear = null;
        pendingPlacementType = null;
        pendingPlacementUntil = 0L;
    }

    private static void clearRemovalCandidate() {
        pendingRemoval = null;
        pendingRemovalType = null;
        pendingRemovalUntil = 0L;
    }

    private static void clearInteractionCandidate() {
        lastInteractedStorage = null;
        lastInteractedType = null;
        lastInteractionUntil = 0L;
    }

    private static void loadConfig() {
        if (!Files.isRegularFile(CONFIG_FILE)) {
            saveConfig();
            return;
        }

        Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(CONFIG_FILE, StandardCharsets.UTF_8)) {
            properties.load(reader);
            outlinesEnabled = Boolean.parseBoolean(properties.getProperty("outlines.enabled", "true"));
            hoppersEnabled = Boolean.parseBoolean(properties.getProperty("hoppers.enabled", "true"));
            barrelsEnabled = Boolean.parseBoolean(properties.getProperty("barrels.enabled", "true"));
            renderRange = clampRange(parseInt(properties.getProperty("render.range", "128"), 128));
        } catch (IOException exception) {
            System.err.println("[PF Helper] Could not read " + CONFIG_FILE + ": " + exception);
        }
    }

    private static void saveConfig() {
        Properties properties = new Properties();
        properties.setProperty("outlines.enabled", Boolean.toString(outlinesEnabled));
        properties.setProperty("hoppers.enabled", Boolean.toString(hoppersEnabled));
        properties.setProperty("barrels.enabled", Boolean.toString(barrelsEnabled));
        properties.setProperty("render.range", Integer.toString(renderRange));

        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(
                    CONFIG_FILE, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                properties.store(writer, "PF Helper client settings");
            }
        } catch (IOException exception) {
            System.err.println("[PF Helper] Could not save " + CONFIG_FILE + ": " + exception);
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static int clampRange(int value) {
        return Math.max(8, Math.min(2048, value));
    }

    private static void loadTracked(Path file, Set<TrackedStorage> target) {
        target.clear();
        if (!Files.isRegularFile(file)) {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }

                String[] parts = line.split("\\t");
                if (parts.length != 5) {
                    continue;
                }

                try {
                    target.add(new TrackedStorage(
                            parts[0], parts[1],
                            Integer.parseInt(parts[2]),
                            Integer.parseInt(parts[3]),
                            Integer.parseInt(parts[4])
                    ));
                } catch (NumberFormatException ignored) {
                    // Skip malformed user-edited rows rather than preventing the game from loading.
                }
            }
        } catch (IOException exception) {
            System.err.println("[PF Helper] Could not read " + file + ": " + exception);
        }
    }

    private static void saveTracked(Path file, Set<TrackedStorage> tracked) {
        try {
            Files.createDirectories(file.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(
                    file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                writer.write("# server\\tdimension\\tx\\ty\\tz");
                writer.newLine();
                for (TrackedStorage storage : tracked.stream().sorted().toList()) {
                    writer.write(storage.server);
                    writer.write('\t');
                    writer.write(storage.dimension);
                    writer.write('\t');
                    writer.write(Integer.toString(storage.x));
                    writer.write('\t');
                    writer.write(Integer.toString(storage.y));
                    writer.write('\t');
                    writer.write(Integer.toString(storage.z));
                    writer.newLine();
                }
            }
        } catch (IOException exception) {
            System.err.println("[PF Helper] Could not save " + file + ": " + exception);
        }
    }

    private enum StorageType {
        HOPPER,
        BARREL
    }

    private record TrackedStorage(String server, String dimension, int x, int y, int z)
            implements Comparable<TrackedStorage> {

        BlockPos pos() {
            return new BlockPos(x, y, z);
        }

        @Override
        public int compareTo(TrackedStorage other) {
            int result = server.compareTo(other.server);
            if (result != 0) return result;
            result = dimension.compareTo(other.dimension);
            if (result != 0) return result;
            result = Integer.compare(x, other.x);
            if (result != 0) return result;
            result = Integer.compare(y, other.y);
            if (result != 0) return result;
            return Integer.compare(z, other.z);
        }
    }
}
