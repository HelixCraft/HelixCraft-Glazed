package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.*;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;

import java.util.*;

public class ItemESP extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();
    private final SettingGroup sgAdvanced = settings.createGroup("Advanced Settings");

    private final List<Item> defaultPlayerItems = new ArrayList<>(List.of(
            // Diamond & Netherite Armor
            Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS,
            Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS,
            // Diamond & Netherite Sword & Pickaxe
            Items.DIAMOND_SWORD, Items.NETHERITE_SWORD,
            Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE,
            // Misc Items
            Items.SHIELD, Items.ELYTRA, Items.MACE, Items.TRIDENT,
            Items.NETHERITE_INGOT, Items.NETHERITE_BLOCK,
            Items.DRAGON_HEAD, Items.PIGLIN_HEAD,
            Items.ENCHANTED_GOLDEN_APPLE, Items.ANCIENT_DEBRIS,
            // All Shulker Boxes
            Items.SHULKER_BOX, Items.WHITE_SHULKER_BOX, Items.ORANGE_SHULKER_BOX, Items.MAGENTA_SHULKER_BOX,
            Items.LIGHT_BLUE_SHULKER_BOX, Items.YELLOW_SHULKER_BOX, Items.LIME_SHULKER_BOX, Items.PINK_SHULKER_BOX,
            Items.GRAY_SHULKER_BOX, Items.LIGHT_GRAY_SHULKER_BOX, Items.CYAN_SHULKER_BOX, Items.PURPLE_SHULKER_BOX,
            Items.BLUE_SHULKER_BOX, Items.BROWN_SHULKER_BOX, Items.GREEN_SHULKER_BOX, Items.RED_SHULKER_BOX, 
            Items.BLACK_SHULKER_BOX
    ));

    public ItemESP() {
        super(GlazedAddon.esp, "item-Esp", "ESP Module that highlights only certain items.");
    }

    // ── General Settings ───────────────────────────────────────────────────
    private final Setting<List<Item>> items = sgGeneral.add(new ItemListSetting.Builder()
            .name("item-checker")
            .description("Items to check for.")
            .defaultValue(defaultPlayerItems)
            .build()
    );

    public final Setting<Boolean> enchants = sgGeneral.add(new BoolSetting.Builder()
            .name("enforce-item-enchants")
            .description("Requires that armor and tools must be enchanted for module to detect.")
            .defaultValue(true)
            .build()
    );

    public final Setting<Boolean> certainenchants = sgGeneral.add(new BoolSetting.Builder()
            .name("find-certain-item-enchants")
            .description("Requires that armor and tools must be enchanted with these enchants.")
            .defaultValue(false)
            .visible(() -> enchants.get())
            .build()
    );

    private final Setting<Set<RegistryKey<Enchantment>>> toolenchants = sgGeneral.add(new EnchantmentListSetting.Builder()
            .name("Mining Tool Enchants")
            .description("List of enchantments required.")
            .visible(() -> enchants.get() && certainenchants.get())
            .defaultValue(Enchantments.EFFICIENCY, Enchantments.UNBREAKING, Enchantments.MENDING)
            .build());

    private final Setting<Set<RegistryKey<Enchantment>>> swordenchants = sgGeneral.add(new EnchantmentListSetting.Builder()
            .name("Sword Enchants")
            .description("List of enchantments required.")
            .visible(() -> enchants.get() && certainenchants.get())
            .defaultValue(Enchantments.UNBREAKING, Enchantments.MENDING)
            .build());

    private final Setting<Set<RegistryKey<Enchantment>>> armorenchants = sgGeneral.add(new EnchantmentListSetting.Builder()
            .name("Armor Enchants")
            .description("List of enchantments required.")
            .visible(() -> enchants.get() && certainenchants.get())
            .defaultValue(Enchantments.UNBREAKING, Enchantments.MENDING)
            .build());

    private final Setting<Set<RegistryKey<Enchantment>>> maceenchants = sgGeneral.add(new EnchantmentListSetting.Builder()
            .name("Mace Enchants")
            .description("List of enchantments required.")
            .visible(() -> enchants.get() && certainenchants.get())
            .defaultValue(Enchantments.UNBREAKING, Enchantments.MENDING)
            .build());

    private final Setting<Set<RegistryKey<Enchantment>>> tridentenchants = sgGeneral.add(new EnchantmentListSetting.Builder()
            .name("Trident Enchants")
            .description("List of enchantments required.")
            .visible(() -> enchants.get() && certainenchants.get())
            .defaultValue(Enchantments.UNBREAKING, Enchantments.MENDING)
            .build());

    private final Setting<Boolean> tracers = sgGeneral.add(new BoolSetting.Builder()
            .name("Tracers")
            .description("Add tracers to item detected")
            .defaultValue(true)
            .build()
    );

    // ── Advanced Settings (Reihenfolge ist wichtig für .visible() Referenzen!) ──

    public final Setting<ShapeMode> shapeMode = sgAdvanced.add(new EnumSetting.Builder<ShapeMode>()
            .name("shape-mode")
            .description("How the shapes are rendered.")
            .defaultValue(ShapeMode.Both)
            .build()
    );

    public final Setting<Double> fillOpacity = sgAdvanced.add(new DoubleSetting.Builder()
            .name("fill-opacity")
            .description("The opacity of the shape fill.")
            .defaultValue(0.3)
            .range(0, 1)
            .sliderMax(1)
            .build()
    );

    private final Setting<Double> fadeDistance = sgAdvanced.add(new DoubleSetting.Builder()
            .name("fade-distance")
            .description("The distance from an entity where the color begins to fade.")
            .defaultValue(3)
            .min(0)
            .sliderMax(12)
            .build()
    );

    private final Setting<Boolean> chatFeedback = sgAdvanced.add(new BoolSetting.Builder()
            .name("chat-feedback")
            .description("Display info about items in chat")
            .defaultValue(false) // <-- Default OFF
            .build()
    );

    private final Setting<Boolean> coordsInChat = sgAdvanced.add(new BoolSetting.Builder()
            .name("display-coords-in-chat")
            .description("Display coords of a detected item")
            .visible(chatFeedback::get)
            .defaultValue(false) // <-- Default OFF
            .build()
    );

    private final Setting<SettingColor> itemsColor = sgAdvanced.add(new ColorSetting.Builder()
            .name("items-color")
            .description("The item's bounding box and tracer color.")
            .defaultValue(new SettingColor(255, 25, 255, 255))
            .build()
    );

    public final Setting<Boolean> distance = sgAdvanced.add(new BoolSetting.Builder()
            .name("distance-colors")
            .description("Changes the color of tracers depending on distance.")
            .defaultValue(true)
            .build()
    );

    private final Setting<SettingColor> distantColor = sgAdvanced.add(new ColorSetting.Builder()
            .name("distant-color")
            .description("The item's bounding box and tracer color when you are far away.")
            .defaultValue(new SettingColor(25, 255, 255, 255))
            .visible(distance::get)
            .build()
    );

    public final Setting<Integer> distanceInt = sgAdvanced.add(new IntSetting.Builder()
            .name("distance-colors-threshold")
            .description("The max distance for colors to change.")
            .defaultValue(128)
            .min(1)
            .sliderRange(1, 1024)
            .visible(distance::get)
            .build()
    );

    // ── Fields ─────────────────────────────────────────────────────────────

    private final Color lineColor = new Color();
    private final Color sideColor = new Color();
    private final Color baseColor = new Color();

    private int count;
    private final Set<Entity> scannedEntities = Collections.synchronizedSet(new HashSet<>());

    // ── Logic ──────────────────────────────────────────────────────────────

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        count = 0;
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof ItemEntity itemEntity)) continue;
            if (shouldSkip(itemEntity)) continue;
            if (!scannedEntities.contains(entity)) {
                StringBuilder message = new StringBuilder(itemEntity.getStack().getItem().getName().getString() + " found ");
                if (chatFeedback.get()) {
                    if (coordsInChat.get()) message.append(" at ").append(entity.getBlockX()).append(", ").append(entity.getBlockY()).append(", ").append(entity.getBlockZ());
                    ChatUtils.sendMsg(Text.of(message.toString()));
                }
            }
            scannedEntities.add(entity);
            drawBoundingBox(event, entity);
            if (tracers.get()) drawTracer(event, entity);
            count++;
        }
    }

    @Override
    public void onActivate() {
        scannedEntities.clear();
    }

    @Override
    public void onDeactivate() {
        scannedEntities.clear();
    }

    private void drawBoundingBox(Render3DEvent event, Entity entity) {
        Color color = getColor(entity);
        if (color != null) {
            lineColor.set(color);
            sideColor.set(color).a((int) (sideColor.a * fillOpacity.get()));
        }

        double x = MathHelper.lerp(event.tickDelta, entity.lastRenderX, entity.getX()) - entity.getX();
        double y = MathHelper.lerp(event.tickDelta, entity.lastRenderY, entity.getY()) - entity.getY();
        double z = MathHelper.lerp(event.tickDelta, entity.lastRenderZ, entity.getZ()) - entity.getZ();
        Box box = entity.getBoundingBox();
        event.renderer.box(x + box.minX, y + box.minY, z + box.minZ, x + box.maxX, y + box.maxY, z + box.maxZ, sideColor, lineColor, shapeMode.get(), 0);
    }

    private void drawTracer(Render3DEvent event, Entity entity) {
        if (mc.options.hudHidden) return;

        Color tracersBaseColor = itemsColor.get();
        if (distance.get()){
            tracersBaseColor = getOpposingColor(tracersBaseColor, entity);
        }

        // FIX: Für 1.21.4 lastRenderX/Y/Z statt lastX/Y/Z verwenden
        double x = entity.lastRenderX + (entity.getX() - entity.lastRenderX) * event.tickDelta;
        double y = entity.lastRenderY + (entity.getY() - entity.lastRenderY) * event.tickDelta;
        double z = entity.lastRenderZ + (entity.getZ() - entity.lastRenderZ) * event.tickDelta;
        
        double height = entity.getBoundingBox().maxY - entity.getBoundingBox().minY;
        y += height / 2;

        event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, x, y, z, tracersBaseColor);
    }
    private Color getOpposingColor(Color c, Entity e) {
        Color interpolatedColor;
        Color oppositeColor = distantColor.get();

        double dist = Math.sqrt(mc.player.squaredDistanceTo(e));
        double maxDistance = distanceInt.get();
        double percent = MathHelper.clamp(dist / maxDistance, 0, 1);

        int r = (int) (c.r + (oppositeColor.r - c.r) * percent);
        int g = (int) (c.g + (oppositeColor.g - c.g) * percent);
        int b = (int) (c.b + (oppositeColor.b - c.b) * percent);
        int a = c.a;

        interpolatedColor = new Color(r, g, b, a);
        return interpolatedColor;
    }

    public static boolean isTool(ItemStack itemStack) {
        return itemStack.isIn(ItemTags.AXES) ||
                itemStack.isIn(ItemTags.HOES) ||
                itemStack.isIn(ItemTags.PICKAXES) ||
                itemStack.isIn(ItemTags.SHOVELS) ||
                itemStack.getItem() instanceof ShearsItem ||
                itemStack.getItem() instanceof FlintAndSteelItem;
    }

    public static boolean isArmor(ItemStack itemStack) {
        return itemStack.isIn(ItemTags.HEAD_ARMOR) ||
                itemStack.isIn(ItemTags.CHEST_ARMOR) ||
                itemStack.isIn(ItemTags.LEG_ARMOR) ||
                itemStack.isIn(ItemTags.FOOT_ARMOR);
    }

    public boolean shouldSkip(ItemEntity entity) {
        boolean skip = false;
        if (enchants.get()) {
            if (!certainenchants.get() && (isTool(entity.getStack()) || isArmor(entity.getStack()) || entity.getStack().isIn(ItemTags.SWORDS) || entity.getStack().getItem() instanceof FishingRodItem || entity.getStack().getItem() instanceof FlintAndSteelItem || entity.getStack().getItem() instanceof MaceItem || entity.getStack().getItem() instanceof ShearsItem || entity.getStack().getItem() instanceof ShieldItem || entity.getStack().getItem() instanceof TridentItem) && entity.getStack().isEnchantable() && entity.getStack().getEnchantments().isEmpty()) skip = true;
            else if (certainenchants.get()){
                if (isTool(entity.getStack())){
                    skip = compareEnchants(entity, toolenchants);
                } else if ( entity.getStack().isIn(ItemTags.SWORDS)){
                    skip = compareEnchants(entity, swordenchants);
                } else if (isArmor(entity.getStack())){
                    skip = compareEnchants(entity, armorenchants);
                } else if (entity.getStack().getItem() instanceof MaceItem){
                    skip = compareEnchants(entity, maceenchants);
                } else if (entity.getStack().getItem() instanceof TridentItem){
                    skip = compareEnchants(entity, tridentenchants);
                }
            }
        }
        if (!items.get().contains(entity.getStack().getItem())) skip = true;
        return skip;
    }

    private boolean compareEnchants(ItemEntity entity, Setting<Set<RegistryKey<Enchantment>>> enchantsetting) {
        boolean skip = false;
        Set<RegistryKey<Enchantment>> itemenchants = new HashSet<>();
        entity.getStack().getEnchantments().getEnchantments().forEach(enchantment -> {
            itemenchants.add(enchantment.getKey().get());
        });
        for (RegistryKey<Enchantment> enchantKey : enchantsetting.get()) {
            if (!itemenchants.contains(enchantKey)) {
                skip = true;
                break;
            }
        }
        return skip;
    }

    public Color getColor(Entity entity) {
        double alpha = getFadeAlpha(entity);
        if (alpha == 0) return null;
        Color color = itemsColor.get(); // Name geändert von monstersColor zu itemsColor
        if (distance.get()){
            color = getOpposingColor(color, entity);
        }
        return baseColor.set(color.r, color.g, color.b, (int) (color.a * alpha));
    }

    private double getFadeAlpha(Entity entity) {
        double dist = PlayerUtils.squaredDistanceToCamera(entity.getX() + entity.getWidth() / 2, entity.getY() + entity.getEyeHeight(entity.getPose()), entity.getZ() + entity.getWidth() / 2);
        double fadeDist = Math.pow(fadeDistance.get(), 2);
        double alpha = 1;
        if (dist <= fadeDist * fadeDist) alpha = (float) (Math.sqrt(dist) / fadeDist);
        if (alpha <= 0.075) alpha = 0;
        return alpha;
    }

    @Override
    public String getInfoString() {
        return Integer.toString(count);
    }

    @EventHandler
    private void onPreTick(TickEvent.Pre event) {
        if (mc.world != null){
            Iterable<net.minecraft.entity.Entity> entities = mc.world.getEntities();
            scannedEntities.removeIf(entity -> {
                Set<Entity> entitySet = new HashSet<>();
                entities.forEach(entity1 -> entitySet.add(entity1));
                return !entitySet.contains(entity);
            });
        }
    }
}