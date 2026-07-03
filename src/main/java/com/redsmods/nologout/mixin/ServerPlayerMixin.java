package com.redsmods.nologout.mixin;

import carpet.patches.EntityPlayerMPFake;
import com.redsmods.nologout.bot.PlayerBotManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
	@Inject(method = "disconnect", at = @At("HEAD"))
	private void onPlayerDisconnect(CallbackInfo ci) {
		ServerPlayer self = (ServerPlayer) (Object)this;
		PlayerBotManager.onPlayerLeave(self,self.level().getServer());
	}

	@Inject(method = "die", at = @At("HEAD"))
	private void onPlayerDeath(DamageSource damageSource, CallbackInfo ci) {
		ServerPlayer self = (ServerPlayer) (Object)this;
		PlayerBotManager.onBotDeath(self, self.level().getServer());

		if (!(self instanceof EntityPlayerMPFake bot)) {
			ItemStack structureVoidStack = new ItemStack(Blocks.STRUCTURE_VOID.asItem());
			self.spawnAtLocation(self.level(), structureVoidStack);
		}
	}
}