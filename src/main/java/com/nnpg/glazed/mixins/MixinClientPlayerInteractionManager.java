package com.nnpg.glazed.mixins;

import com.nnpg.glazed.modules.main.LayerLock;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public class MixinClientPlayerInteractionManager {

    @Inject(method = "interactBlock", at = @At("HEAD"), cancellable = true)
    private void onInteractBlock(ClientPlayerEntity player, Hand hand, BlockHitResult hitResult, CallbackInfoReturnable<ActionResult> cir) {
        LayerLock module = Modules.get().get(LayerLock.class);
        
        if (module == null || !module.isActive() || !module.isLocked()) return;

        ItemStack stack = player.getStackInHand(hand);
        
        if (!(stack.getItem() instanceof BlockItem blockItem)) return;

        BlockPos placedPos = hitResult.getBlockPos().offset(hitResult.getSide());
        int placementY = placedPos.getY();

        boolean blocked = false;
        String reason = "";

        // 1. Y-Level Check (Unterstützt jetzt Bereiche!)
        if (placementY < module.getLockedYStart() || placementY > module.getLockedYEnd()) {
            blocked = true;
            reason = String.format("Wrong Y=%d (Range: %d-%d)", placementY, module.getLockedYStart(), module.getLockedYEnd());
        } 
        // 2. Block Filter Check (Nur wenn Advanced Settings an sind)
        else if (!module.isBlockAllowed(blockItem)) {
            blocked = true;
            reason = "Block not allowed (" + blockItem.getName().getString() + ")";
        }

        if (blocked) {
            if (module.showNotifications()) {
                player.sendMessage(Text.literal("§c[LayerLock] Blocked §7— " + reason), true);
            }
            cir.setReturnValue(ActionResult.FAIL);
        }
    }
}