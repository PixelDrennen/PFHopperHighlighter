package local.peacefulfarms.pfhopper;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.Optional;

/**
 * A tiny client-only tracker for Peaceful Farms PF Hoppers.
 *
 * Detection signals:
 *  - PF Hopper placement success message.
 *  - PF Hopper removal success message.
 *  - Hopper container title "PF Hopper" when an existing hopper is opened.
 *
 * The server still sees this as an ordinary client; no custom packets are sent.
 */
public final class PFHopperHighlighterClient implements ClientModInitializer {
    private static final String PLACED_MESSAGE = "successfully placed a pfhopper";
    private static final String REMOVED_MESSAGE = "successfully remove pfhopper";
    private static final String PF_HOPPER_TITLE = "PF Hopper";

    private static final long CANDIDATE_TTL_MS = 5_000L;
    private static final double RENDER_RANGE = 128.0;
    // ARGB orange, chosen to stand out against most base blocks.
    private static final int HIGHLIGHT_COLOR = 0xFFFFA500;

    // MC 26.1.2 no longer exposes RenderType.lines(). Define the line layer
    // explicitly using the 26.1 render-pipeline API. Depth is disabled so
    // tracked PF Hoppers remain visible through walls.
    private static final RenderPipeline HIGHLIGHT_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("pfhopperhighlighter", "hopper_outline"))
                    .withVertexFormat(DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH, VertexFormat.Mode.LINES)
                    .withDepthStencilState(Optional.empty())
                    .build()
    );
    private static final RenderType HIGHLIGHT_LAYER = RenderType.create(
            "pfhopperhighlighter_hopper_outline",
            RenderSetup.builder(HIGHLIGHT_PIPELINE).createRenderSetup()
    );

    private static final Path DATA_FILE = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("pf-hopper-highlighter.tsv");

    private static final Set<TrackedHopper> TRACKED = new HashSet<>();

    private static BlockPos pendingPlacementNear;
    private static long pendingPlacementUntil;

    private static BlockPos lastInteractedHopper;
    private static long lastInteractionUntil;

    private static BlockPos pendingRemoval;
    private static long pendingRemovalUntil;

    @Override
    public void onInitializeClient() {
        load();

        UseBlockCallback.EVENT.register(PFHopperHighlighterClient::onUseBlock);
        AttackBlockCallback.EVENT.register(PFHopperHighlighterClient::onAttackBlock);
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onGameMessage(message.getString()));
        ClientTickEvents.END_CLIENT_TICK.register(PFHopperHighlighterClient::onClientTick);
        LevelRenderEvents.BEFORE_GIZMOS.register(PFHopperHighlighterClient::renderHighlights);
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
            lastInteractedHopper = clicked.immutable();
            lastInteractionUntil = now + CANDIDATE_TTL_MS;
        }

        if (player.getItemInHand(hand).is(Items.HOPPER)) {
            // The exact placement can be the clicked position (replaceable target) or the
            // adjacent position. Store the expected area and resolve the actual hopper only
            // after Peaceful Farms confirms that a PF Hopper was successfully created.
            pendingPlacementNear = clicked.relative(hitResult.getDirection()).immutable();
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

        if (level.getBlockState(pos).is(Blocks.HOPPER) || isTrackedHere(pos)) {
            pendingRemoval = pos.immutable();
            pendingRemovalUntil = System.currentTimeMillis() + CANDIDATE_TTL_MS;
        }

        return InteractionResult.PASS;
    }

    private static void onGameMessage(String raw) {
        String normalized = normalize(raw);
        long now = System.currentTimeMillis();

        if (normalized.contains(PLACED_MESSAGE)) {
            if (pendingPlacementNear != null && now <= pendingPlacementUntil) {
                BlockPos actual = findNearbyHopper(pendingPlacementNear, 2);
                if (actual != null) {
                    addCurrent(actual);
                }
            }
            clearPlacementCandidate();
            return;
        }

        if (normalized.contains(REMOVED_MESSAGE)) {
            if (pendingRemoval != null && now <= pendingRemovalUntil) {
                removeCurrent(pendingRemoval);
            }
            clearRemovalCandidate();
        }
    }

    private static void onClientTick(Minecraft minecraft) {
        long now = System.currentTimeMillis();

        if (pendingPlacementNear != null && now > pendingPlacementUntil) {
            clearPlacementCandidate();
        }
        if (pendingRemoval != null && now > pendingRemovalUntil) {
            clearRemovalCandidate();
        }
        if (lastInteractedHopper != null && now > lastInteractionUntil) {
            clearInteractionCandidate();
        }

        if (minecraft.level == null || minecraft.screen == null || lastInteractedHopper == null) {
            return;
        }

        String title = minecraft.screen.getTitle().getString();
        if (PF_HOPPER_TITLE.equalsIgnoreCase(title)
                && now <= lastInteractionUntil
                && minecraft.level.getBlockState(lastInteractedHopper).is(Blocks.HOPPER)) {
            addCurrent(lastInteractedHopper);
            clearInteractionCandidate();
        }
    }

    private static void renderHighlights(LevelRenderContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }

        String server = serverKey(minecraft);
        String dimension = dimensionKey(minecraft);
        BlockPos playerPos = minecraft.player.blockPosition();
        double maxDistanceSq = RENDER_RANGE * RENDER_RANGE;

        PoseStack matrices = context.poseStack();
        if (matrices == null) {
            return;
        }

        VertexConsumer vertices = context.bufferSource().getBuffer(HIGHLIGHT_LAYER);
        Vec3 camera = minecraft.gameRenderer.getMainCamera().position();

        for (TrackedHopper tracked : TRACKED) {
            if (!tracked.server.equals(server) || !tracked.dimension.equals(dimension)) {
                continue;
            }

            BlockPos pos = tracked.pos();
            double distanceSq = playerPos.distSqr(pos);
            if (distanceSq > maxDistanceSq) {
                continue;
            }

            if (minecraft.level.hasChunkAt(pos) && !minecraft.level.getBlockState(pos).is(Blocks.HOPPER)) {
                continue;
            }

            AABB box = new AABB(pos).inflate(0.045).move(-camera.x, -camera.y, -camera.z);
            drawBox(matrices.last(), vertices, box, HIGHLIGHT_COLOR);
        }
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

    private static BlockPos findNearbyHopper(BlockPos expected, int radius) {
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
                    if (!minecraft.level.getBlockState(candidate).is(Blocks.HOPPER)) {
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

    private static boolean isTrackedHere(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return false;
        }

        String server = serverKey(minecraft);
        String dimension = dimensionKey(minecraft);
        return TRACKED.contains(new TrackedHopper(server, dimension, pos.getX(), pos.getY(), pos.getZ()));
    }

    private static void addCurrent(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        TrackedHopper hopper = new TrackedHopper(
                serverKey(minecraft),
                dimensionKey(minecraft),
                pos.getX(), pos.getY(), pos.getZ()
        );

        if (TRACKED.add(hopper)) {
            save();
        }
    }

    private static void removeCurrent(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }

        TrackedHopper hopper = new TrackedHopper(
                serverKey(minecraft),
                dimensionKey(minecraft),
                pos.getX(), pos.getY(), pos.getZ()
        );

        if (TRACKED.remove(hopper)) {
            save();
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
        pendingPlacementUntil = 0L;
    }

    private static void clearRemovalCandidate() {
        pendingRemoval = null;
        pendingRemovalUntil = 0L;
    }

    private static void clearInteractionCandidate() {
        lastInteractedHopper = null;
        lastInteractionUntil = 0L;
    }

    private static void load() {
        TRACKED.clear();
        if (!Files.isRegularFile(DATA_FILE)) {
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(DATA_FILE, StandardCharsets.UTF_8)) {
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
                    TRACKED.add(new TrackedHopper(
                            parts[0],
                            parts[1],
                            Integer.parseInt(parts[2]),
                            Integer.parseInt(parts[3]),
                            Integer.parseInt(parts[4])
                    ));
                } catch (NumberFormatException ignored) {
                    // Skip malformed user-edited rows rather than preventing the game from loading.
                }
            }
        } catch (IOException exception) {
            System.err.println("[PF Hopper Highlighter] Could not read " + DATA_FILE + ": " + exception);
        }
    }

    private static void save() {
        try {
            Files.createDirectories(DATA_FILE.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(
                    DATA_FILE,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            )) {
                writer.write("# server\\tdimension\\tx\\ty\\tz");
                writer.newLine();

                for (TrackedHopper hopper : TRACKED.stream().sorted().toList()) {
                    writer.write(hopper.server);
                    writer.write('\t');
                    writer.write(hopper.dimension);
                    writer.write('\t');
                    writer.write(Integer.toString(hopper.x));
                    writer.write('\t');
                    writer.write(Integer.toString(hopper.y));
                    writer.write('\t');
                    writer.write(Integer.toString(hopper.z));
                    writer.newLine();
                }
            }
        } catch (IOException exception) {
            System.err.println("[PF Hopper Highlighter] Could not save " + DATA_FILE + ": " + exception);
        }
    }

    private record TrackedHopper(String server, String dimension, int x, int y, int z)
            implements Comparable<TrackedHopper> {

        BlockPos pos() {
            return new BlockPos(x, y, z);
        }

        @Override
        public int compareTo(TrackedHopper other) {
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
