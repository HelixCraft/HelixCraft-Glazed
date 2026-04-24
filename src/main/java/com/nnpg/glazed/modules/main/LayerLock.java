package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.FastUse;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.List;

public class LayerLock extends Module {

    // ── Settings Groups ───────────────────────────────────────────────────
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgAdvanced = settings.createGroup("Advanced Settings"); // Erstellt den Trenner im GUI

    // ── Advanced Variablen (Zuerst deklariert um Vorwärtsreferenzen zu vermeiden) ──
    
    private final Setting<Boolean> advancedSettings = sgAdvanced.add(new BoolSetting.Builder()
        .name("advanced-settings")
        .description("Unlock Y-Level ranges and block filtering.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> yLevelRange = sgAdvanced.add(new BoolSetting.Builder()
        .name("y-level-range")
        .description("Use two keys to define a min and max Y level instead of a single line.")
        .defaultValue(false)
        .visible(() -> advancedSettings.get())
        .build()
    );

    private final Setting<Keybind> startKey = sgAdvanced.add(new KeybindSetting.Builder()
        .name("start-y-key")
        .description("Set the minimum Y level.")
        .defaultValue(Keybind.none())
        .visible(() -> advancedSettings.get() && yLevelRange.get())
        .build()
    );

    private final Setting<Keybind> endKey = sgAdvanced.add(new KeybindSetting.Builder()
        .name("end-y-key")
        .description("Set the maximum Y level.")
        .defaultValue(Keybind.none())
        .visible(() -> advancedSettings.get() && yLevelRange.get())
        .build()
    );

    public enum BlockFilterMode { WHITELIST, BLACKLIST }

    private final Setting<BlockFilterMode> blockFilterMode = sgAdvanced.add(new EnumSetting.Builder<BlockFilterMode>()
        .name("block-filter-mode")
        .description("Choose if the list acts as a whitelist or blacklist.")
        .defaultValue(BlockFilterMode.WHITELIST)
        .visible(() -> advancedSettings.get())
        .build()
    );

    private final Setting<List<Item>> whitelistBlocks = sgAdvanced.add(new ItemListSetting.Builder()
        .name("whitelist-blocks")
        .description("Only these blocks can be placed.")
        .defaultValue(Items.STONE, Items.COBBLESTONE)
        .filter(item -> item instanceof BlockItem)
        .visible(() -> advancedSettings.get() && blockFilterMode.get() == BlockFilterMode.WHITELIST)
        .build()
    );

    private final Setting<List<Item>> blacklistBlocks = sgAdvanced.add(new ItemListSetting.Builder()
        .name("blacklist-blocks")
        .description("These blocks are NOT allowed to be placed.")
        .defaultValue(Items.TNT)
        .filter(item -> item instanceof BlockItem)
        .visible(() -> advancedSettings.get() && blockFilterMode.get() == BlockFilterMode.BLACKLIST)
        .build()
    );

    // ── Normale Variablen (Jetzt sicher deklariert, da sie oben referenziert werden) ──

    private final Setting<Keybind> setKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("set-key")
        .description("Press while looking at a block to lock placement to that Y level.")
        .defaultValue(Keybind.none())
        .visible(() -> !(advancedSettings.get() && yLevelRange.get())) 
        .build()
    );

    private final Setting<Boolean> usePlacementY = sgGeneral.add(new BoolSetting.Builder()
        .name("use-placement-y")
        .description("ON = lock to where the new block would appear (Y + 1). OFF = raw block Y.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications")
        .description("Show messages when the locked Y changes or a placement is blocked.")
        .defaultValue(true)
        .build()
    );
    
    private final Setting<Boolean> enableFastUse = sgGeneral.add(new BoolSetting.Builder()
        .name("enable-fast-use")
        .description("Automatically turns on Meteor's FastUse module.")
        .defaultValue(false)
        .build()
    );


    // ── State ─────────────────────────────────────────────────────────────
    private int lockedYStart = Integer.MIN_VALUE;
    private int lockedYEnd = Integer.MIN_VALUE;

    private boolean fastUseWasEnabledBeforeUs = false;
    private boolean wasFastUseSettingOnLastTick = false;

    public LayerLock() {
        super(GlazedAddon.CATEGORY, "layer-lock", "Restricts block placement to Y levels and specific blocks.");
    }

    @Override
    public void onActivate() {
        wasFastUseSettingOnLastTick = false;
        fastUseWasEnabledBeforeUs = false;
        if (notifications.get()) info("§aLayerLock Active§r.");
    }

    @Override
    public void onDeactivate() {
        if (enableFastUse.get() && !fastUseWasEnabledBeforeUs) {
            toggleModule(FastUse.class, false);
        }
        if (notifications.get()) info("§cLayerLock Disabled§r.");
    }

    private void toggleModule(Class<? extends Module> moduleClass, boolean enable) {
        Module module = Modules.get().get(moduleClass);
        if (module != null) {
            if (enable && !module.isActive()) module.toggle();
            else if (!enable && module.isActive()) module.toggle();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        boolean settingCurrentlyOn = enableFastUse.get();
        if (settingCurrentlyOn && !wasFastUseSettingOnLastTick) {
            Module fastUse = Modules.get().get(FastUse.class);
            if (fastUse != null) fastUseWasEnabledBeforeUs = fastUse.isActive();
        }
        if (settingCurrentlyOn) toggleModule(FastUse.class, true);
        wasFastUseSettingOnLastTick = settingCurrentlyOn;
    }

    // ── Getter für den Mixin ──────────────────────────────────────────────

    public boolean isLocked() {
        return lockedYStart != Integer.MIN_VALUE;
    }

    public int getLockedYStart() {
        return lockedYStart;
    }

    public int getLockedYEnd() {
        return lockedYEnd == Integer.MIN_VALUE ? lockedYStart : lockedYEnd;
    }

    public boolean isAdvancedEnabled() {
        return advancedSettings.get();
    }

    public boolean isBlockAllowed(BlockItem item) {
        if (!advancedSettings.get()) return true;
        if (blockFilterMode.get() == BlockFilterMode.WHITELIST) {
            return whitelistBlocks.get().contains(item);
        } else {
            return !blacklistBlocks.get().contains(item);
        }
    }

    public boolean showNotifications() {
        return notifications.get();
    }

    // ── Key handler ───────────────────────────────────────────────────────

    private int calculateY(BlockHitResult hit) {
        BlockPos hoveredPos = hit.getBlockPos();
        if (usePlacementY.get()) {
            int y = hoveredPos.getY() + hit.getSide().getOffsetY();
            if (hit.getSide().getOffsetY() == 0) y = hoveredPos.getY();
            return y;
        } else {
            return hoveredPos.getY();
        }
    }

    @EventHandler
    private void onKey(KeyEvent event) {
        if (mc.player == null || mc.world == null || mc.currentScreen != null) return;

        boolean rangeActive = advancedSettings.get() && yLevelRange.get();

        if (!rangeActive && setKey.get().isPressed()) {
            if (mc.crosshairTarget == null || mc.crosshairTarget.getType() != HitResult.Type.BLOCK) {
                lockedYStart = Integer.MIN_VALUE;
                lockedYEnd = Integer.MIN_VALUE;
                if (notifications.get()) ChatUtils.info("§c[LayerLock] Lock cleared§r.");
                return;
            }
            int y = calculateY((BlockHitResult) mc.crosshairTarget);
            lockedYStart = y;
            lockedYEnd = y;
            if (notifications.get()) ChatUtils.info(String.format("§a[LayerLock] Locked §rto §eY=%d§r.", y));
            return;
        }

        if (rangeActive && startKey.get().isPressed()) {
            if (mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.BLOCK) {
                lockedYStart = calculateY((BlockHitResult) mc.crosshairTarget);
                if (notifications.get()) ChatUtils.info(String.format("§a[LayerLock] Start Y §rset to §eY=%d§r.", lockedYStart));
            }
            return;
        }

        if (rangeActive && endKey.get().isPressed()) {
            if (mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.BLOCK) {
                lockedYEnd = calculateY((BlockHitResult) mc.crosshairTarget);
                if (notifications.get()) ChatUtils.info(String.format("§a[LayerLock] End Y §rset to §eY=%d§r.", lockedYEnd));
            }
            return;
        }
    }
}