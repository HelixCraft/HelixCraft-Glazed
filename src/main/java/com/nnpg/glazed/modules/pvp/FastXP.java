package com.nnpg.glazed.modules.pvp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.Hand;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.concurrent.ThreadLocalRandom;

public class FastXP extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> antiPrevention = sgGeneral.add(new BoolSetting.Builder()
        .name("anti-prevention")
        .description("Skips random ticks to simulate human clicking patterns (avg ~15-18 throws/sec).")
        .defaultValue(true)
        .build()
    );

    // Zählt runter, wie viele Ticks wir noch pausieren müssen
    private int waitTicks = 0;

    public FastXP() {
        super(GlazedAddon.CATEGORY, "fast-xp", "Throws XP bottles extremely fast when holding right click.");
    }

    @Override
    public void onDeactivate() {
        waitTicks = 0; // Reset beim Deaktivieren
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // Wenn wir noch in einer "Pause" sind, zähle runter und tue nichts
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }

        // Prüfe ob rechte Maustaste gedrückt ist
        if (!mc.options.useKey.isPressed()) return;

        ItemStack mainHand = mc.player.getMainHandStack();
        ItemStack offHand = mc.player.getOffHandStack();

        if (mainHand.isOf(Items.EXPERIENCE_BOTTLE) || offHand.isOf(Items.EXPERIENCE_BOTTLE)) {
            Hand handToUse = mainHand.isOf(Items.EXPERIENCE_BOTTLE) ? Hand.MAIN_HAND : Hand.OFF_HAND;

            // Wirf die Flasche
            mc.interactionManager.interactItem(mc.player, handToUse);

            // Berechne die nächste Pause
            if (antiPrevention.get()) {
                // 75% Chance: Wirf beim nächsten Tick wieder (50ms Delay)
                // 25% Chance: Überspringe einen Tick (100ms Delay)
                // -> Durchschnitt: 62,5ms pro Wurf (ca. 16 Würfe/sek)
                // Das erzeugt ein extrem natürliches "Klick-Klick---Klick-Klick-Klick" Muster
                if (ThreadLocalRandom.current().nextFloat() < 0.25f) {
                    waitTicks = 1; 
                } else {
                    waitTicks = 0; // Keine Pause
                }
            } else {
                waitTicks = 0; // Strikte 20 TPS (50ms)
            }
        }
    }
}