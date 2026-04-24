package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PickaxeItem;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.World;
import meteordevelopment.meteorclient.systems.modules.misc.AutoReconnect;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.*;

public class SpawnerProtect extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgWhitelist = settings.createGroup("Whitelist");
    private final SettingGroup sgWebhook = settings.createGroup("Webhook");

    // ── Webhook Settings ───────────────────────────────────────────────────
    private final Setting<Boolean> webhook = sgWebhook.add(new BoolSetting.Builder()
            .name("webhook").description("Enable webhook notifications").defaultValue(false).build());

    private final Setting<String> webhookUrl = sgWebhook.add(new StringSetting.Builder()
            .name("webhook-url").description("Discord webhook URL").defaultValue("")
            .visible(webhook::get).build());

    private final Setting<Boolean> selfPing = sgWebhook.add(new BoolSetting.Builder()
            .name("self-ping").description("Ping yourself in the webhook").defaultValue(false)
            .visible(webhook::get).build());

    private final Setting<String> discordId = sgWebhook.add(new StringSetting.Builder()
            .name("discord-id").description("Your Discord user ID").defaultValue("")
            .visible(() -> webhook.get() && selfPing.get()).build());

    // ── General Settings (Oben) ───────────────────────────────────────────
    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
            .name("notifications").description("Show chat feedback.").defaultValue(true).build());

    private final Setting<Integer> spawnerRange = sgGeneral.add(new IntSetting.Builder()
            .name("spawner-range").description("Range to check for spawners").defaultValue(16)
            .min(1).max(50).sliderMax(50).build());

    private final Setting<Integer> emergencyDistance = sgGeneral.add(new IntSetting.Builder()
            .name("emergency-distance").description("Instant disconnect distance (0 to disable)").defaultValue(7)
            .min(0).max(20).sliderMax(20).build());

    private final Setting<Integer> minDetectionRange = sgGeneral.add(new IntSetting.Builder()
            .name("min-detection-range").description("Minimum distance to detect a player").defaultValue(0)
            .min(0).max(50).sliderMax(50).build());

    private final Setting<Integer> maxDetectionRange = sgGeneral.add(new IntSetting.Builder()
            .name("max-detection-range").description("Maximum distance to detect a player").defaultValue(50)
            .min(1).max(100).sliderMax(100).build());

    private final Setting<Integer> spawnerCheckDelay = sgGeneral.add(new IntSetting.Builder()
            .name("spawner-check-delay-ms").description("Delay before confirming spawners are gone").defaultValue(3000)
            .min(1000).max(10000).sliderMax(10000).build());

    private final Setting<Integer> spawnerTimeout = sgGeneral.add(new IntSetting.Builder()
            .name("spawner-timeout-ms").description("Time to wait before rescanning a spawner (important for stacked spawners)").defaultValue(3000)
            .min(1000).max(30000).sliderMax(30000).build());

    private final Setting<Boolean> depositToEChest = sgGeneral.add(new BoolSetting.Builder()
            .name("deposit-to-echest").description("Deposit spawners into ender chest").defaultValue(true).build());

    private final Setting<Boolean> disableAutoReconnect = sgGeneral.add(new BoolSetting.Builder()
            .name("disable-auto-reconnect")
            .description("Disables Meteor AutoReconnect when this module disconnects you.")
            .defaultValue(true)
            .build());

    private final Setting<Boolean> detectServerRestarts = sgGeneral.add(new BoolSetting.Builder()
            .name("detect-server-restarts")
            .description("Ignores players if you are near spawn (within 1000 blocks of 0,0,0) to prevent false triggers on restarts.")
            .defaultValue(true)
            .build());

    // ── General Settings (Ganz unten in der Liste) ────────────────────────
    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
            .name("debug").description("Spam debug messages in chat").defaultValue(false).build());

    // ── Whitelist Settings ────────────────────────────────────────────────
    private final Setting<Boolean> enableWhitelist = sgWhitelist.add(new BoolSetting.Builder()
            .name("enable-whitelist").description("Ignore whitelisted players").defaultValue(false).build());

    private final Setting<List<String>> whitelistPlayers = sgWhitelist.add(new StringListSetting.Builder()
            .name("whitelisted-players").description("Player names to ignore").defaultValue(new ArrayList<>())
            .visible(enableWhitelist::get).build());

    private final Setting<List<Item>> depositBlacklist = sgGeneral.add(new ItemListSetting.Builder()
            .name("deposit-blacklist").description("Items to keep when depositing")
            .defaultValue(Arrays.asList(Items.ENDER_PEARL, Items.END_CRYSTAL, Items.OBSIDIAN, Items.RESPAWN_ANCHOR, Items.GLOWSTONE, Items.TOTEM_OF_UNDYING))
            .visible(depositToEChest::get).build());

    // ── State Machine ─────────────────────────────────────────────────────
    private enum State { IDLE, GOING_TO_SPAWNERS, GOING_TO_CHEST, OPENING_CHEST, DEPOSITING_ITEMS, DISCONNECTING, WORLD_CHANGED_ONCE, WORLD_CHANGED_TWICE }
    private State currentState = State.IDLE;

    // ── Runtime Variables ─────────────────────────────────────────────────
    private int stateTick = 0;
    private int transferDelayCounter = 0;
    private int ticksSinceLastClick = 0; 
    private int currentPickaxeSlot = -1;

    private String detectedPlayer = "";
    private long detectionTime = 0;
    private boolean spawnersMinedSuccessfully = false;
    private boolean itemsDepositedSuccessfully = false;
    private boolean emergencyDisconnect = false;
    private String emergencyReason = "";

    private BlockPos currentTarget = null;
    private long noSpawnerStartTime = -1;
    private long currentTargetStartTime = -1;

    private BlockPos targetChest = null;

    private World trackedWorld = null;
    private int worldChangeCount = 0;
    private final int PLAYER_COUNT_THRESHOLD = 3;

    private float targetYaw, targetPitch;
    private boolean rotating = false;
    private static final float ROTATION_SPEED = 8.0f;

    public SpawnerProtect() {
        super(GlazedAddon.CATEGORY, "spawner-protect", "Breaks spawners and secures them when a player is detected");
    }

    @Override
    public void onActivate() {
        resetState();
        if (mc.world != null) {
            trackedWorld = mc.world;
            worldChangeCount = 0;
            info("Monitoring world: " + mc.world.getRegistryKey().getValue() + " for players...");
        }
        ChatUtils.warning("Ensure you have an empty inventory, silk touch pickaxe, and placed ender chest!");
    }

    @Override
    public void onDeactivate() {
        cleanupActions();
    }

    private void resetState() {
        cleanupActions();
        currentState = State.IDLE;
        stateTick = 0;
        transferDelayCounter = 0;
        ticksSinceLastClick = 0;
        currentPickaxeSlot = -1;
        detectedPlayer = "";
        detectionTime = 0;
        spawnersMinedSuccessfully = false;
        itemsDepositedSuccessfully = false;
        emergencyDisconnect = false;
        emergencyReason = "";
        currentTarget = null;
        targetChest = null;
        rotating = false;
        noSpawnerStartTime = -1;
        currentTargetStartTime = -1;
    }

    private void cleanupActions() {
        if (mc.player != null) {
            mc.options.sneakKey.setPressed(false);
            mc.options.attackKey.setPressed(false);
            mc.options.forwardKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
            mc.player.closeHandledScreen();
        }
    }

    private void changeState(State newState) {
        if (currentState != newState) {
            if (debug.get()) info("DEBUG: State changed from " + currentState + " to " + newState);
            currentState = newState;
            stateTick = 0;
            if (newState != State.DISCONNECTING) {
                mc.options.attackKey.setPressed(false);
                mc.options.forwardKey.setPressed(false);
            }
        }
    }

    // ── Sneak Steuerung ───────────────────────────────────────────────────
    private void updateSneakState() {
        if (mc.player == null) return;
        boolean shouldSneak = (currentState == State.GOING_TO_SPAWNERS || currentState == State.GOING_TO_CHEST);

        if (shouldSneak) {
            if (!mc.options.sneakKey.isPressed()) mc.options.sneakKey.setPressed(true);
        } else {
            if (mc.options.sneakKey.isPressed()) mc.options.sneakKey.setPressed(false);
        }
    }

    // ── Haupt Loop ────────────────────────────────────────────────────────
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        updateSneakState();
        handleRotation();
        stateTick++;

        if (mc.world != trackedWorld) {
            handleWorldChange();
            return;
        }

        if (currentState == State.WORLD_CHANGED_ONCE || currentState == State.WORLD_CHANGED_TWICE) return;

        // FIX: Wenn wir uns bereits im Disconnecting-State befinden, 
        // überspringe Player-Checks etc. und führe SOFORT den Disconnect aus.
        if (currentState == State.DISCONNECTING) {
            handleDisconnecting();
            return;
        }

        if (checkEmergencyDisconnect()) return;

        if (transferDelayCounter > 0) {
            transferDelayCounter--;
            return;
        }

        switch (currentState) {
            case IDLE -> checkForPlayers();
            case GOING_TO_SPAWNERS -> handleGoingToSpawners();
            case GOING_TO_CHEST -> handleGoingToChest();
            case OPENING_CHEST -> handleOpeningChest();
            case DEPOSITING_ITEMS -> handleDepositingItems();
            // DISCONNECTING wurde hier rausgenommen, da es jetzt oben abgefangen wird
            default -> {}
        }
    }

    private void handleRotation() {
        if (!rotating) return;
        float yawDiff = wrapDegrees(targetYaw - mc.player.getYaw());
        float pitchDiff = targetPitch - mc.player.getPitch();

        mc.player.setYaw(mc.player.getYaw() + Math.signum(yawDiff) * Math.min(Math.abs(yawDiff), ROTATION_SPEED));
        mc.player.setPitch(mc.player.getPitch() + Math.signum(pitchDiff) * Math.min(Math.abs(pitchDiff), ROTATION_SPEED));

        if (Math.abs(yawDiff) < 1f && Math.abs(pitchDiff) < 1f) rotating = false;
    }

    private float wrapDegrees(float value) {
        value %= 360.0f;
        if (value >= 180.0f) value -= 360.0f;
        if (value < -180.0f) value += 360.0f;
        return value;
    }

    private void handleWorldChange() {
        worldChangeCount++;
        trackedWorld = mc.world;
        if (worldChangeCount == 1) {
            changeState(State.WORLD_CHANGED_ONCE);
            if (notifications.get()) info("World changed - pausing detection...");
        } else if (worldChangeCount >= 2) {
            changeState(State.WORLD_CHANGED_TWICE);
            worldChangeCount = 0;
            if (notifications.get()) info("Returned to spawner world - resuming...");
            changeState(State.IDLE);
        }
    }

    // ── Player Detection ──────────────────────────────────────────────────
    private boolean checkEmergencyDisconnect() {
        if (emergencyDistance.get() <= 0) return false;
        
        // Server Restart Schutz
        if (detectServerRestarts.get() && isNearSpawn()) return false;

        long otherPlayers = mc.world.getPlayers().stream().filter(p -> p != mc.player).count();
        if (otherPlayers >= PLAYER_COUNT_THRESHOLD) return false;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (!(player instanceof AbstractClientPlayerEntity) || player == mc.player) continue;
            if (isPlayerWhitelisted(player.getGameProfile().getName())) continue;

            if (mc.player.distanceTo(player) <= emergencyDistance.get()) {
                emergencyDisconnect = true;
                emergencyReason = player.getGameProfile().getName() + " came too close";
                detectedPlayer = player.getGameProfile().getName();
                detectionTime = System.currentTimeMillis();
                disableAutoReconnectIfEnabled();
                changeState(State.DISCONNECTING);
                return true;
            }
        }
        return false;
    }

    private void checkForPlayers() {
        // Server Restart Schutz
        if (detectServerRestarts.get() && isNearSpawn()) return;

        long otherPlayers = mc.world.getPlayers().stream().filter(p -> p != mc.player).count();
        if (otherPlayers >= PLAYER_COUNT_THRESHOLD) return;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (!(player instanceof AbstractClientPlayerEntity) || player == mc.player) continue;
            double distance = mc.player.getPos().distanceTo(player.getPos());
            if (distance < minDetectionRange.get() || distance > maxDetectionRange.get()) continue;

            String name = player.getGameProfile().getName();
            if (isPlayerWhitelisted(name)) continue;

            detectedPlayer = name;
            detectionTime = System.currentTimeMillis();
            disableAutoReconnectIfEnabled();
            if (notifications.get()) info("Player detected: " + name + " (" + String.format("%.1f", distance) + " blocks)");
            changeState(State.GOING_TO_SPAWNERS);
            break;
        }
    }

    private boolean isNearSpawn() {
        // Berechnet nur den horizontalen Abstand (X und Z), ignoriert Y (Höhe)
        double distXZ = Math.sqrt(mc.player.getX() * mc.player.getX() + mc.player.getZ() * mc.player.getZ());
        return distXZ <= 1000;
    }

    private boolean isPlayerWhitelisted(String name) {
        AdminList adminList = Modules.get().get(AdminList.class);
        if (adminList != null && adminList.isActive() && adminList.isAdmin(name)) return true;
        if (!enableWhitelist.get()) return false;
        return whitelistPlayers.get().stream().anyMatch(w -> w.equalsIgnoreCase(name));
    }

    // ── Spawner Mining Logic ──────────────────────────────────────────────
    private void handleGoingToSpawners() {
        if (currentTarget == null) {
            currentTarget = findNearestSpawner();
            if (currentTarget == null) {
                if (noSpawnerStartTime == -1) {
                    noSpawnerStartTime = System.currentTimeMillis();
                    if (debug.get()) info("DEBUG: No spawners found, starting delay timer...");
                }
                if (System.currentTimeMillis() - noSpawnerStartTime >= spawnerCheckDelay.get()) {
                    if (debug.get()) info("DEBUG: Delay passed. No spawners left. Moving to chest.");
                    changeState(State.GOING_TO_CHEST);
                }
                return;
            }
            noSpawnerStartTime = -1;
            currentTargetStartTime = System.currentTimeMillis();
            if (debug.get()) info("DEBUG: Found spawner at " + currentTarget.toShortString());
        }

        if (currentTargetStartTime != -1) {
            long timeTrying = System.currentTimeMillis() - currentTargetStartTime;
            if (timeTrying > spawnerTimeout.get()) {
                if (debug.get()) info("DEBUG: Timeout reached. Rescanning area...");
                currentTarget = null;
                currentTargetStartTime = -1;
                currentPickaxeSlot = -1;
                mc.options.attackKey.setPressed(false);
                return;
            }
        }

        Direction side = getExposedFaceSide(currentTarget);
        lookAtBlock(currentTarget, side);

        if (mc.crosshairTarget instanceof BlockHitResult hit && hit.getBlockPos().equals(currentTarget)) {
            FindItemResult pickaxe = findSilkTouchPickaxe();
            if (!pickaxe.found()) {
                if (debug.get()) error("DEBUG: No Silk Touch Pickaxe found!");
                changeState(State.GOING_TO_CHEST);
                return;
            }

            if (mc.player.getInventory().selectedSlot != pickaxe.slot()) {
                InvUtils.swap(pickaxe.slot(), true);
                currentPickaxeSlot = pickaxe.slot();
                if (debug.get()) info("DEBUG: Swapped to pickaxe in slot " + pickaxe.slot());
            }

            mc.options.attackKey.setPressed(true);
            mc.interactionManager.updateBlockBreakingProgress(currentTarget, hit.getSide());
        } else {
            mc.options.attackKey.setPressed(false);
        }
    }

    private BlockPos findNearestSpawner() {
        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        double maxDistSq = spawnerRange.get() * spawnerRange.get();

        for (BlockPos pos : BlockPos.iterate(playerPos.add(-spawnerRange.get(), -spawnerRange.get(), -spawnerRange.get()), playerPos.add(spawnerRange.get(), spawnerRange.get(), spawnerRange.get()))) {
            if (mc.world.getBlockState(pos).getBlock() != Blocks.SPAWNER) continue;
            double distSq = pos.getSquaredDistance(mc.player.getPos());
            if (distSq < nearestDistance && distSq <= maxDistSq) {
                nearestDistance = distSq;
                nearest = pos.toImmutable();
            }
        }
        return nearest;
    }

    private FindItemResult findSilkTouchPickaxe() {
        return InvUtils.find(stack -> {
            if (!(stack.getItem() instanceof PickaxeItem)) return false;
            for (var entry : stack.getEnchantments().getEnchantmentEntries()) {
                if (entry.getKey().matchesKey(Enchantments.SILK_TOUCH)) return true;
            }
            return false;
        });
    }

    private Direction getExposedFaceSide(BlockPos pos) {
        for (Direction side : Direction.values()) {
            BlockPos neighbor = pos.offset(side);
            if (mc.world.getBlockState(neighbor).isAir() || !mc.world.getBlockState(neighbor).isFullCube(mc.world, neighbor)) {
                return side;
            }
        }
        return Direction.UP;
    }

    private void lookAtBlock(BlockPos pos, Direction side) {
        Vec3d targetPos = Vec3d.ofCenter(pos).add(Vec3d.of(side.getVector()).multiply(0.5));
        Vec3d dir = targetPos.subtract(mc.player.getEyePos()).normalize();
        targetYaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        targetPitch = (float) Math.toDegrees(-Math.asin(dir.y));
        rotating = true;
    }

    // ── Ender Chest Logic ─────────────────────────────────────────────────
    private void handleGoingToChest() {
        if (!depositToEChest.get()) { changeState(State.DISCONNECTING); return; }

        if (targetChest == null) {
            targetChest = findNearestEnderChest();
            if (targetChest == null) {
                if (debug.get()) error("DEBUG: No ender chest found!");
                changeState(State.DISCONNECTING);
                return;
            }
            if (debug.get()) info("DEBUG: Found ender chest at " + targetChest.toShortString());
        }

        moveTowardsBlock(targetChest);

        if (mc.player.getBlockPos().getSquaredDistance(targetChest) <= 9) {
            if (debug.get()) info("DEBUG: Reached ender chest distance.");
            changeState(State.OPENING_CHEST);
        } else if (stateTick > 600) {
            if (debug.get()) error("DEBUG: Timed out moving to chest!");
            changeState(State.DISCONNECTING);
        }
    }

    private BlockPos findNearestEnderChest() {
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.iterate(mc.player.getBlockPos().add(-16, -8, -16), mc.player.getBlockPos().add(16, 8, 16))) {
            if (mc.world.getBlockState(pos).getBlock() == Blocks.ENDER_CHEST) {
                double dist = pos.getSquaredDistance(mc.player.getPos());
                if (dist < nearestDistance) { nearestDistance = dist; nearest = pos.toImmutable(); }
            }
        }
        return nearest;
    }

    private void moveTowardsBlock(BlockPos target) {
        Vec3d dir = Vec3d.ofCenter(target).subtract(mc.player.getPos()).normalize();
        mc.player.setYaw((float) Math.toDegrees(Math.atan2(-dir.x, dir.z)));
        mc.options.forwardKey.setPressed(true);
    }

    private void handleOpeningChest() {
        if (targetChest == null) { changeState(State.GOING_TO_CHEST); return; }

        if (stateTick % 5 == 0 && mc.interactionManager != null) {
            if (debug.get()) info("DEBUG: Right-clicking ender chest... (attempt " + ((stateTick / 5) + 1) + ")");
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(Vec3d.ofCenter(targetChest), Direction.UP, targetChest, false));
        }

        if (mc.player.currentScreenHandler instanceof GenericContainerScreenHandler) {
            if (debug.get()) info("DEBUG: Ender chest opened successfully!");
            changeState(State.DEPOSITING_ITEMS);
        } else if (stateTick > 200) {
            if (debug.get()) error("DEBUG: Failed to open chest after max attempts!");
            changeState(State.DISCONNECTING);
        }
    }

    // ── Inventory Transfer Logic ──────────────────────────────────────────
    private void handleDepositingItems() {
        if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler)) {
            if (debug.get() && stateTick % 20 == 0) info("DEBUG: Lost chest handler. Waiting before retry...");
            if (stateTick % 20 == 0) {
                changeState(State.OPENING_CHEST);
            }
            return;
        }

        transferItemsToChest(handler);

        if (stateTick > 900) {
            if (debug.get()) error("DEBUG: Timed out depositing items!");
            changeState(State.DISCONNECTING);
        }
    }

    private void transferItemsToChest(GenericContainerScreenHandler handler) {
        int chestSlots = handler.slots.size() - 36;
        if (isChestFull(handler, chestSlots)) {
            if (debug.get()) error("DEBUG: Ender chest is full!");
            changeState(State.DISCONNECTING);
            return;
        }

        for (int i = 0; i < 36; i++) {
            int slotId = chestSlots + i;
            ItemStack stack = handler.getSlot(slotId).getStack();
            if (!stack.isEmpty() && stack.getItem() == Items.SPAWNER) {
                clickSlot(handler, slotId, stack);
                return;
            }
        }

        for (int i = 0; i < 36; i++) {
            int slotId = chestSlots + i;
            ItemStack stack = handler.getSlot(slotId).getStack();
            if (!stack.isEmpty() && !isVitalItem(stack)) {
                clickSlot(handler, slotId, stack);
                return;
            }
        }

        ticksSinceLastClick++;
        
        if (ticksSinceLastClick >= 10) {
            itemsDepositedSuccessfully = true;
            if (debug.get()) info("DEBUG: All items deposited!");
            mc.player.closeHandledScreen();
            transferDelayCounter = 10;
            changeState(State.DISCONNECTING);
        }
    }

    private void clickSlot(GenericContainerScreenHandler handler, int slotId, ItemStack stack) {
        if (debug.get()) info("DEBUG: Clicking " + stack.getName().getString() + " in slot " + slotId);
        mc.interactionManager.clickSlot(handler.syncId, slotId, 0, SlotActionType.QUICK_MOVE, mc.player);
        transferDelayCounter = 3;
        ticksSinceLastClick = 0; 
    }

    private boolean isVitalItem(ItemStack stack) {
        if (stack.isEmpty()) return true;
        if (depositBlacklist.get().contains(stack.getItem())) return true;
        if (stack.getItem() == Items.ENDER_CHEST) return true;
        return false;
    }

    private boolean isChestFull(GenericContainerScreenHandler handler, int chestSlots) {
        for (int i = 0; i < chestSlots; i++) {
            if (handler.getSlot(i).getStack().isEmpty()) return false;
        }
        return true;
    }

    // ── Disconnect & Webhook ──────────────────────────────────────────────
    private void handleDisconnecting() {
        if (debug.get()) info("DEBUG: Disconnecting now...");
        sendWebhookNotification();

        if (emergencyDisconnect) {
            if (notifications.get()) info("EMERGENCY: " + emergencyReason);
        } else {
            if (notifications.get()) info("Protection complete. Disconnecting...");
        }

        if (mc.world != null) mc.world.disconnect();
        this.toggle();
    }

    private void disableAutoReconnectIfEnabled() {
        if (!disableAutoReconnect.get()) return;
        Module autoReconnect = Modules.get().get(AutoReconnect.class);
        if (autoReconnect != null && autoReconnect.isActive()) {
            autoReconnect.toggle();
            if (debug.get()) info("DEBUG: Disabled AutoReconnect.");
        }
    }

    private void sendWebhookNotification() {
        if (!webhook.get() || webhookUrl.get() == null || webhookUrl.get().trim().isEmpty()) return;
        String url = webhookUrl.get().trim();
        long discordTimestamp = detectionTime / 1000L;

        String messageContent = (selfPing.get() && discordId.get() != null && !discordId.get().trim().isEmpty()) 
                ? String.format("<@%s>", discordId.get().trim()) : "";

        String payload = String.format("""
                {
                    "username": "Glazed Webhook",
                    "avatar_url": "https://i.imgur.com/OL2y1cr.png",
                    "content": "%s",
                    "embeds": [{
                        "title": "%s",
                        "description": "%s",
                        "color": %d,
                        "timestamp": "%s",
                        "footer": { "text": "Sent by Glazed" }
                    }]
                }""",
                escapeJson(messageContent),
                emergencyDisconnect ? "SpawnerProtect Emergency" : "SpawnerProtect Alert",
                escapeJson(emergencyDisconnect 
                        ? String.format("**Player:** %s\\n**Reason:** %s", detectedPlayer, emergencyReason)
                        : String.format("**Player:** %s\\n**Spawners:** %s\\n**Deposited:** %s", detectedPlayer, spawnersMinedSuccessfully ? "✅" : "❌", itemsDepositedSuccessfully ? "✅" : "❌")),
                emergencyDisconnect ? 16711680 : 16766720,
                Instant.now().toString()
        );

        Thread.startVirtualThread(() -> {
            try {
                HttpClient client = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build();
                client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (Exception ignored) {}
        });
    }

    private String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}