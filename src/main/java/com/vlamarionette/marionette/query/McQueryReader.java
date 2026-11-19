package com.vlamarionette.marionette.query;

import java.util.ArrayList;
import java.util.Collections;
import com.mojang.blaze3d.platform.InputConstants;
import com.vlamarionette.marionette.mixin.KeyMappingAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main-thread readers for the query messages.
 *
 * <p>Every method here touches Minecraft state and must only be called from the
 * client thread. Network-thread code goes through {@link QueryService}, which
 * hops with {@code Minecraft.execute}.
 */
public final class McQueryReader {

    private static final Logger LOG = LoggerFactory.getLogger("vla");

    private McQueryReader() {
    }

    public static BlockSnapshot readBlock(Minecraft mc, int x, int y, int z)
            throws QueryFailure {
        ClientLevel level = mc.level;
        if (level == null) {
            throw new QueryFailure("no_level");
        }
        BlockPos pos = new BlockPos(x, y, z);
        if (!level.isInValidBounds(pos)) {
            throw new QueryFailure("out_of_bounds");
        }
        // ClientLevel.hasChunk always returns true; asking the chunk cache with
        // loadOrGenerate=false is the only reliable loaded-chunk check here.
        if (level.getChunk(SectionPos.blockToSectionCoord(x),
                SectionPos.blockToSectionCoord(z), ChunkStatus.FULL, false) == null) {
            throw new QueryFailure("chunk_not_loaded");
        }
        return readAt(level, pos);
    }

    public static TargetSnapshot readCrosshair(Minecraft mc) throws QueryFailure {
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) {
            throw new QueryFailure("no_level");
        }
        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            throw new QueryFailure("no_target");
        }
        BlockSnapshot block = readAt(level, blockHit.getBlockPos());
        Entity camera = mc.getCameraEntity();
        Vec3 eye = camera != null ? camera.getEyePosition() : player.getEyePosition();
        double distance = eye.distanceTo(blockHit.getLocation());
        return new TargetSnapshot(block, distance, blockHit.getDirection().getName());
    }

    public static InventorySnapshot readInventory(Minecraft mc) throws QueryFailure {
        LocalPlayer player = mc.player;
        if (mc.level == null || player == null) {
            throw new QueryFailure("no_level");
        }
        Inventory inventory = player.getInventory();
        List<QueryResponses.Slot> slots = new ArrayList<>();
        int size = inventory.getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            String itemId = idOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
            slots.add(new QueryResponses.Slot(slot,
                    itemId == null ? "minecraft:air" : itemId, stack.getCount()));
        }
        return new InventorySnapshot(inventory.getSelectedSlot(), slots);
    }

    public static SettingsSnapshot readSettings(Minecraft mc) throws QueryFailure {
        LocalPlayer player = mc.player;
        if (mc.level == null || player == null) {
            throw new QueryFailure("no_level");
        }
        Options options = mc.options;
        GameType gameMode = player.gameMode();
        List<QueryResponses.Keybind> keybinds = new ArrayList<>();
        for (KeyMapping mapping : options.keyMappings) {
            InputConstants.Key key = ((KeyMappingAccessor) mapping).vla$getKey();
            String type = key.getType().name().toLowerCase(Locale.ROOT);
            keybinds.add(new QueryResponses.Keybind(mapping.getName(), type,
                    mapping.isUnbound() ? null : key.getValue()));
        }
        return new SettingsSnapshot(
                gameMode == null ? null : gameMode.getName(),
                options.sensitivity().get(),
                options.invertMouseX().get(),
                options.invertMouseY().get(),
                options.smoothCamera,
                options.getCameraType().name().toLowerCase(Locale.ROOT),
                player.isScoping(),
                options.hideGui,
                mc.getWindow().getGuiScale(),
                mc.getWindow().getGuiScaledWidth(),
                mc.getWindow().getGuiScaledHeight(),
                List.copyOf(keybinds));
    }

    /**
     * Dispatches one chat line on the client thread.
     *
     * @return {@code null} on dispatch, otherwise a failure reason
     */
    public static String sendChat(Minecraft mc, ChatRequest request) {
        if (mc.level == null) {
            return "no_level";
        }
        ClientPacketListener connection = mc.getConnection();
        if (connection == null) {
            return "not_connected";
        }
        try {
            if (ChatRequest.KIND_COMMAND.equals(request.kind())) {
                connection.sendCommand(request.body());
            } else {
                connection.sendChat(request.body());
            }
            return null;
        } catch (Throwable t) {
            LOG.error("[vla] chat/command dispatch failed", t);
            return "internal";
        }
    }

    private static BlockSnapshot readAt(ClientLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Map<String, String> properties = new TreeMap<>();
        state.getValues().forEach(value ->
                properties.put(value.property().getName(), value.valueName()));

        BlockEntity blockEntity = level.getBlockEntity(pos);
        String blockEntityId = blockEntity == null
                ? null
                : idOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType()));

        String blockStateId = idOf(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        return new BlockSnapshot(pos.getX(), pos.getY(), pos.getZ(),
                blockStateId == null ? "minecraft:air" : blockStateId,
                properties, blockEntityId);
    }

    private static String idOf(Identifier id) {
        return id == null ? null : id.toString();
    }

    public record BlockSnapshot(int x, int y, int z, String blockState,
            Map<String, String> properties, String blockEntity) {

        public BlockSnapshot {
            properties = Collections.unmodifiableMap(new TreeMap<>(properties));
        }
    }

    public record TargetSnapshot(BlockSnapshot block, double distance, String face) {

        public TargetSnapshot {
            Objects.requireNonNull(block, "block");
        }
    }

    public record InventorySnapshot(int selected, List<QueryResponses.Slot> slots) {

        public InventorySnapshot {
            slots = List.copyOf(slots);
        }
    }

    public record SettingsSnapshot(String gameMode, double sensitivity,
            boolean invertMouseX, boolean invertMouseY, boolean smoothCamera,
            String cameraType, boolean scoping, boolean hideGui, int guiScale,
            int guiWidth, int guiHeight, List<QueryResponses.Keybind> keybinds) {

        public SettingsSnapshot {
            keybinds = List.copyOf(keybinds);
        }
    }
}

