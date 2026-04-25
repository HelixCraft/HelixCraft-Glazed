package com.nnpg.glazed.modules.main;

import com.nnpg.glazed.GlazedAddon;
import com.nnpg.glazed.utils.OverlayMessageTracker;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.ItemSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AutoDelivere extends Module {
    private static final long COMMAND_DELAY_MS = 1000L;
    private static final long DELIVERING_WINDOW_MS = 500L;
    private static final long SCREEN_TIMEOUT_MS = 4000L;
    private static final int ORDER_ROWS = 6;
    private static final int DEPOSIT_ROWS = 4;
    private static final int CONFIRM_ROWS = 3;
    private static final int CONFIRM_SLOT_ID = 15;
    private static final int ORDER_REFRESH_SLOT_ID = 49;

    private static final Pattern PRICE_PATTERN = Pattern.compile("\\$\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern DELIVERED_PATTERN = Pattern.compile("([0-9]+(?:\\.[0-9]+)?[KMBkmb]?)\\s*/\\s*([0-9]+(?:\\.[0-9]+)?[KMBkmb]?)\\s*Delivered", Pattern.CASE_INSENSITIVE);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> useTextInput = sgGeneral.add(new BoolSetting.Builder()
        .name("use-text-input")
        .description("Use a direct text query instead of item selection.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Item> targetItem = sgGeneral.add(new ItemSetting.Builder()
        .name("target-item")
        .description("The actual Minecraft item this module should order and move.")
        .defaultValue(Items.BONE)
        .visible(() -> !useTextInput.get())
        .build()
    );

    private final Setting<String> textItemName = sgGeneral.add(new StringSetting.Builder()
        .name("item-name")
        .description("Direct /order query fallback. This text is sent exactly as entered.")
        .defaultValue("bones")
        .visible(useTextInput::get)
        .build()
    );

    private final Setting<String> orderQueryOverride = sgGeneral.add(new StringSetting.Builder()
        .name("order-query-override")
        .description("Optional manual /order query override. Leave empty to auto-generate it from the selected item.")
        .defaultValue("")
        .visible(() -> !useTextInput.get())
        .build()
    );

    private final Setting<Boolean> disableWhenFinished = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-when-finished-ordering")
        .description("Disable the module when no more matching items remain in inventory after ordering finishes.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> stepDelay = sgGeneral.add(new IntSetting.Builder()
        .name("step-delay")
        .description("Delay in ticks between container interactions and follow-up steps.")
        .defaultValue(10)
        .min(0)
        .max(100)
        .sliderMax(40)
        .build()
    );

    private final Setting<SelectionMode> selectionMode = sgGeneral.add(new EnumSetting.Builder<SelectionMode>()
        .name("selection-mode")
        .description("How to choose an order from the 6x9 order screen.")
        .defaultValue(SelectionMode.FIRST_ORDER)
        .build()
    );

    private Stage stage = Stage.WAIT_COMMAND;
    private long stageStartMs;
    private long nextActionAtMs;
    private int lastOrderSyncId = -1;
    private boolean deliveringDetected;
    private boolean refreshBeforeNextSelection;
    private String cachedTextQuery = "";
    private Item cachedTextResolvedItem;
    private boolean cachedTextItemResolved;

    public AutoDelivere() {
        super(GlazedAddon.CATEGORY, "auto-delivere", "Orders and delivers items using the order GUI flow.");
    }

    @Override
    public void onActivate() {
        stage = Stage.WAIT_COMMAND;
        stageStartMs = System.currentTimeMillis();
        nextActionAtMs = stageStartMs + COMMAND_DELAY_MS;
        lastOrderSyncId = -1;
        deliveringDetected = false;
        refreshBeforeNextSelection = false;
        cachedTextQuery = "";
        cachedTextResolvedItem = null;
        cachedTextItemResolved = false;
    }

    @Override
    public void onDeactivate() {
        stage = Stage.WAIT_COMMAND;
        nextActionAtMs = 0L;
        lastOrderSyncId = -1;
        deliveringDetected = false;
        refreshBeforeNextSelection = false;
        cachedTextQuery = "";
        cachedTextResolvedItem = null;
        cachedTextItemResolved = false;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;

        long now = System.currentTimeMillis();
        if (stage != Stage.WAIT_DELIVERING_WINDOW && now < nextActionAtMs) return;

        switch (stage) {
            case WAIT_COMMAND -> {
                if (now - stageStartMs >= COMMAND_DELAY_MS) {
                    ChatUtils.sendPlayerMsg("/order " + getOrderQuery());
                    enterStage(Stage.WAIT_ORDER_SCREEN);
                }
            }

            case WAIT_ORDER_SCREEN -> {
                GenericContainerScreenHandler handler = getGenericHandler(ORDER_ROWS);
                if (handler != null) {
                    lastOrderSyncId = handler.syncId;
                    if (refreshBeforeNextSelection) {
                        enterStage(Stage.REFRESH_ORDER_SCREEN);
                    } else {
                        enterStage(Stage.SELECT_ORDER);
                    }
                    return;
                }

                if (now - stageStartMs > SCREEN_TIMEOUT_MS) {
                    ChatUtils.sendPlayerMsg("/order " + getOrderQuery());
                    stageStartMs = now;
                }
            }

            case REFRESH_ORDER_SCREEN -> {
                GenericContainerScreenHandler handler = getGenericHandler(ORDER_ROWS);
                if (handler == null) {
                    enterStage(Stage.WAIT_ORDER_SCREEN);
                    return;
                }

                mc.interactionManager.clickSlot(handler.syncId, ORDER_REFRESH_SLOT_ID, 0, SlotActionType.PICKUP, mc.player);
                refreshBeforeNextSelection = false;
                enterStage(Stage.SELECT_ORDER);
            }

            case SELECT_ORDER -> {
                GenericContainerScreenHandler handler = getGenericHandler(ORDER_ROWS);
                if (handler == null) {
                    enterStage(Stage.WAIT_ORDER_SCREEN);
                    return;
                }

                int slot = selectionMode.get() == SelectionMode.FIRST_ORDER ? findFirstMatchingOrderSlot(handler) : findBestOrderSlot(handler);
                if (slot < 0) {
                    refreshBeforeNextSelection = true;
                    enterStage(Stage.REFRESH_ORDER_SCREEN);
                    return;
                }

                mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.PICKUP, mc.player);
                enterStage(Stage.WAIT_DEPOSIT_SCREEN);
            }

            case WAIT_DEPOSIT_SCREEN -> {
                GenericContainerScreenHandler handler = getGenericHandler(DEPOSIT_ROWS);
                if (handler != null) {
                    enterStage(Stage.DUMP_ITEMS);
                    return;
                }

                if (now - stageStartMs > SCREEN_TIMEOUT_MS) restartOrderPage();
            }

            case DUMP_ITEMS -> {
                GenericContainerScreenHandler handler = getGenericHandler(DEPOSIT_ROWS);
                if (handler == null) {
                    enterStage(Stage.WAIT_DEPOSIT_SCREEN);
                    return;
                }

                quickMoveMatchingInventory(handler);
                enterStage(Stage.CLOSE_DEPOSIT_SCREEN);
            }

            case CLOSE_DEPOSIT_SCREEN -> {
                if (mc.currentScreen != null) mc.player.closeHandledScreen();
                enterStage(Stage.WAIT_CONFIRM_SCREEN);
            }

            case WAIT_CONFIRM_SCREEN -> {
                GenericContainerScreenHandler handler = getGenericHandler(CONFIRM_ROWS);
                if (handler != null) {
                    enterStage(Stage.CLICK_CONFIRM);
                    return;
                }

                if (now - stageStartMs > SCREEN_TIMEOUT_MS) restartOrderPage();
            }

            case CLICK_CONFIRM -> {
                GenericContainerScreenHandler handler = getGenericHandler(CONFIRM_ROWS);
                if (handler == null) {
                    enterStage(Stage.WAIT_CONFIRM_SCREEN);
                    return;
                }

                mc.interactionManager.clickSlot(handler.syncId, CONFIRM_SLOT_ID, 0, SlotActionType.PICKUP, mc.player);
                deliveringDetected = false;
                enterStageImmediate(Stage.WAIT_DELIVERING_WINDOW);
            }

            case WAIT_DELIVERING_WINDOW -> {
                if (OverlayMessageTracker.sawDeliveringOverlaySince(stageStartMs)) {
                    deliveringDetected = true;
                    enterStage(Stage.POST_CONFIRM_SUCCESS);
                    return;
                }

                if (now - stageStartMs >= DELIVERING_WINDOW_MS) {
                    enterStage(Stage.POST_CONFIRM_FAILURE);
                }
            }

            case POST_CONFIRM_SUCCESS -> {
                GenericContainerScreenHandler depositHandler = getGenericHandler(DEPOSIT_ROWS);
                if (depositHandler != null) {
                    if (hasMatchingInventoryItems()) enterStage(Stage.DUMP_ITEMS);
                    else if (finishIfDone()) return;
                    else {
                        mc.player.closeHandledScreen();
                        enterStage(Stage.WAIT_ORDER_SCREEN);
                    }
                    return;
                }

                GenericContainerScreenHandler confirmHandler = getGenericHandler(CONFIRM_ROWS);
                if (confirmHandler != null) {
                    mc.player.closeHandledScreen();
                    enterStage(Stage.WAIT_EMPTY_DEPOSIT_SCREEN);
                    return;
                }

                if (now - stageStartMs > SCREEN_TIMEOUT_MS) restartOrderPage();
            }

            case POST_CONFIRM_FAILURE -> {
                GenericContainerScreenHandler orderHandler = getGenericHandler(ORDER_ROWS);
                if (orderHandler != null) {
                    refreshBeforeNextSelection = true;
                    enterStage(Stage.REFRESH_ORDER_SCREEN);
                    return;
                }

                if (mc.currentScreen != null) {
                    refreshBeforeNextSelection = true;
                    mc.player.closeHandledScreen();
                    enterStage(Stage.WAIT_ORDER_SCREEN);
                    return;
                }

                if (now - stageStartMs > SCREEN_TIMEOUT_MS) restartOrderPage();
            }

            case WAIT_EMPTY_DEPOSIT_SCREEN -> {
                GenericContainerScreenHandler handler = getGenericHandler(DEPOSIT_ROWS);
                if (handler != null) {
                    if (hasMatchingInventoryItems()) enterStage(Stage.DUMP_ITEMS);
                    else if (finishIfDone()) return;
                    else {
                        mc.player.closeHandledScreen();
                        enterStage(Stage.WAIT_ORDER_SCREEN);
                    }
                    return;
                }

                if (now - stageStartMs > SCREEN_TIMEOUT_MS) restartOrderPage();
            }
        }
    }

    private void restartOrderPage() {
        if (mc.currentScreen != null) mc.player.closeHandledScreen();
        ChatUtils.sendPlayerMsg("/order " + getOrderQuery());
        enterStage(Stage.WAIT_ORDER_SCREEN);
    }

    private void quickMoveMatchingInventory(GenericContainerScreenHandler handler) {
        int containerSlots = handler.getRows() * 9;
        for (int slot = containerSlots; slot < handler.slots.size(); slot++) {
            ItemStack stack = handler.getSlot(slot).getStack();
            if (!stack.isEmpty() && matchesTargetItem(stack)) {
                mc.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.QUICK_MOVE, mc.player);
            }
        }
    }

    private boolean hasMatchingInventoryItems() {
        for (ItemStack stack : mc.player.getInventory().main) {
            if (!stack.isEmpty() && matchesTargetItem(stack)) return true;
        }

        for (ItemStack stack : mc.player.getInventory().offHand) {
            if (!stack.isEmpty() && matchesTargetItem(stack)) return true;
        }

        return false;
    }

    private boolean matchesTargetItem(ItemStack stack) {
        if (useTextInput.get()) {
            Item resolvedItem = resolveTextInputTargetItem();
            if (resolvedItem != null) return stack.isOf(resolvedItem);

            String wanted = normalizeItemToken(textItemName.get());
            String display = normalizeItemToken(stack.getName().getString());
            String path = normalizeItemToken(Registries.ITEM.getId(stack.getItem()).getPath());
            return tokenMatches(wanted, display) || tokenMatches(wanted, path);
        }

        return stack.isOf(targetItem.get());
    }

    private String getOrderQuery() {
        if (useTextInput.get()) return textItemName.get().trim();

        String override = orderQueryOverride.get().trim();
        if (!override.isEmpty()) return override;

        return buildDefaultOrderQuery(targetItem.get());
    }

    private Item resolveTextInputTargetItem() {
        String query = textItemName.get();
        if (query.equals(cachedTextQuery)) {
            return cachedTextItemResolved ? cachedTextResolvedItem : null;
        }

        cachedTextQuery = query;
        cachedTextResolvedItem = null;
        cachedTextItemResolved = false;

        String wanted = normalizeItemToken(query);
        if (wanted.isEmpty()) return null;

        for (Item item : Registries.ITEM) {
            String path = normalizeItemToken(Registries.ITEM.getId(item).getPath());
            String display = normalizeItemToken(item.getName().getString());
            if (tokenMatches(wanted, path) || tokenMatches(wanted, display)) {
                cachedTextResolvedItem = item;
                cachedTextItemResolved = true;
                return item;
            }
        }

        return null;
    }

    private String buildDefaultOrderQuery(Item item) {
        String displayName = item.getName().getString().trim();
        String path = Registries.ITEM.getId(item).getPath();

        if (shouldPluralizeQuery(displayName, path)) {
            return pluralizeDisplayName(displayName);
        }

        return displayName;
    }

    private boolean shouldPluralizeQuery(String displayName, String path) {
        if (path.endsWith("_block")) return false;
        if (displayName.contains(" ")) return false;

        String singularBase = path;
        if (singularBase.endsWith("s") && singularBase.length() > 1) {
            singularBase = singularBase.substring(0, singularBase.length() - 1);
        }

        IdentifierLike candidates = new IdentifierLike(
            singularBase + "_block",
            singularBase + "block"
        );

        for (String candidate : candidates.values) {
            if (candidate == null || candidate.isEmpty()) continue;
            if (Registries.ITEM.getIds().stream().anyMatch(id -> id.getPath().equals(candidate))) {
                return true;
            }
        }

        return false;
    }

    private String pluralizeDisplayName(String displayName) {
        if (displayName.endsWith("s")) return displayName;
        return displayName + "s";
    }

    private String normalizeItemToken(String input) {
        return input == null ? "" : input.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private boolean tokenMatches(String wanted, String actual) {
        if (wanted.isEmpty() || actual.isEmpty()) return false;
        if (wanted.equals(actual)) return true;
        if (wanted.endsWith("s") && wanted.substring(0, wanted.length() - 1).equals(actual)) return true;
        if (actual.endsWith("s") && actual.substring(0, actual.length() - 1).equals(wanted)) return true;
        return actual.contains(wanted) || wanted.contains(actual);
    }

    private boolean finishIfDone() {
        if (!disableWhenFinished.get()) return false;

        if (mc.currentScreen != null) mc.player.closeHandledScreen();
        info("Finished ordering - no more matching items in inventory.");
        toggle();
        return true;
    }

    private int findFirstMatchingOrderSlot(GenericContainerScreenHandler handler) {
        int upperSlots = Math.min(45, handler.slots.size());
        for (int slot = 0; slot < upperSlots; slot++) {
            ItemStack stack = handler.getSlot(slot).getStack();
            if (!stack.isEmpty() && matchesTargetItem(stack)) return slot;
        }

        return -1;
    }

    private int findBestOrderSlot(GenericContainerScreenHandler handler) {
        List<OrderEntry> orders = new ArrayList<>();
        int upperSlots = Math.min(45, handler.slots.size());

        for (int slot = 0; slot < upperSlots; slot++) {
            ItemStack stack = handler.getSlot(slot).getStack();
            if (stack.isEmpty()) continue;
            if (!matchesTargetItem(stack)) continue;

            OrderEntry entry = parseOrder(slot, stack);
            if (entry != null) orders.add(entry);
        }

        if (orders.isEmpty()) return -1;

        double maxPrice = 0.0;
        for (OrderEntry order : orders) {
            if (order.remaining <= 0) continue;
            maxPrice = Math.max(maxPrice, order.price);
        }

        if (maxPrice <= 0.0) return orders.getFirst().slot;

        final double alpha = 5.0;
        OrderEntry bestOrder = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (OrderEntry order : orders) {
            if (order.remaining <= 0) continue;

            double priceRatio = order.price / maxPrice;
            double r = order.remaining;
            double volumeScore;

            if (r < 100_000.0) volumeScore = 0.2 * (r / 100_000.0);
            else if (r < 1_000_000.0) volumeScore = 0.2 + 0.8 * ((r - 100_000.0) / 900_000.0);
            else volumeScore = 1.0;

            order.score = Math.pow(priceRatio, alpha) * volumeScore;
            if (order.score > bestScore) {
                bestScore = order.score;
                bestOrder = order;
            }
        }

        return bestOrder != null ? bestOrder.slot : -1;
    }

    private OrderEntry parseOrder(int slot, ItemStack stack) {
        List<Text> tooltip = stack.getTooltip(Item.TooltipContext.create(mc.world), mc.player, TooltipType.BASIC);
        double price = -1.0;
        double ordered = -1.0;
        double total = -1.0;

        for (Text line : tooltip) {
            String text = line.getString();

            if (price < 0.0) {
                Matcher priceMatcher = PRICE_PATTERN.matcher(text);
                if (priceMatcher.find()) price = parseDecimal(priceMatcher.group(1));
            }

            if (ordered < 0.0 || total < 0.0) {
                Matcher deliveredMatcher = DELIVERED_PATTERN.matcher(text);
                if (deliveredMatcher.find()) {
                    ordered = parseCompactNumber(deliveredMatcher.group(1));
                    total = parseCompactNumber(deliveredMatcher.group(2));
                }
            }
        }

        if (price < 0.0 || ordered < 0.0 || total <= 0.0) return null;

        OrderEntry entry = new OrderEntry();
        entry.slot = slot;
        entry.price = price;
        entry.orderedAmount = ordered;
        entry.totalAmount = total;
        entry.remaining = total - ordered;
        return entry;
    }

    private double parseDecimal(String value) {
        try {
            return Double.parseDouble(value.replace(",", ""));
        } catch (NumberFormatException ignored) {
            return -1.0;
        }
    }

    private double parseCompactNumber(String value) {
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace(",", "");
        double multiplier = 1.0;

        if (normalized.endsWith("K")) {
            multiplier = 1_000.0;
            normalized = normalized.substring(0, normalized.length() - 1);
        } else if (normalized.endsWith("M")) {
            multiplier = 1_000_000.0;
            normalized = normalized.substring(0, normalized.length() - 1);
        } else if (normalized.endsWith("B")) {
            multiplier = 1_000_000_000.0;
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        double base = parseDecimal(normalized);
        return base < 0.0 ? -1.0 : base * multiplier;
    }

    private GenericContainerScreenHandler getGenericHandler(int rows) {
        if (!(mc.player.currentScreenHandler instanceof GenericContainerScreenHandler handler)) return null;
        return handler.getRows() == rows ? handler : null;
    }

    private void enterStage(Stage stage) {
        this.stage = stage;
        this.stageStartMs = System.currentTimeMillis();
        this.nextActionAtMs = this.stageStartMs + ticksToMs(stepDelay.get());
    }

    private void enterStageImmediate(Stage stage) {
        this.stage = stage;
        this.stageStartMs = System.currentTimeMillis();
        this.nextActionAtMs = this.stageStartMs;
    }

    private long ticksToMs(int ticks) {
        return ticks * 50L;
    }

    private enum Stage {
        WAIT_COMMAND,
        WAIT_ORDER_SCREEN,
        REFRESH_ORDER_SCREEN,
        SELECT_ORDER,
        WAIT_DEPOSIT_SCREEN,
        DUMP_ITEMS,
        CLOSE_DEPOSIT_SCREEN,
        WAIT_CONFIRM_SCREEN,
        CLICK_CONFIRM,
        WAIT_DELIVERING_WINDOW,
        POST_CONFIRM_SUCCESS,
        POST_CONFIRM_FAILURE,
        WAIT_EMPTY_DEPOSIT_SCREEN
    }

    private enum SelectionMode {
        FIRST_ORDER,
        BEST_ORDER
    }

    private static class OrderEntry {
        private int slot;
        private double price;
        private double orderedAmount;
        private double totalAmount;
        private double remaining;
        private double score;
    }

    private static class IdentifierLike {
        private final String[] values;

        private IdentifierLike(String... values) {
            this.values = values;
        }
    }
}
