package com.nnpg.glazed.mixins;

import com.nnpg.glazed.utils.OverlayMessageTracker;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InGameHud.class)
public class InGameHudMixin {
    @Inject(method = "setOverlayMessage", at = @At("HEAD"))
    private void glazed$logOverlayMessage(Text message, boolean tinted, CallbackInfo ci) {
        OverlayMessageTracker.onOverlayMessage(message);
    }
}
