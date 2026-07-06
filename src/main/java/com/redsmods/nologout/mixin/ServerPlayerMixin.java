package com.redsmods.nologout.mixin;

import carpet.patches.EntityPlayerMPFake;
import com.redsmods.nologout.bot.PlayerBotManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

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

		if (!(self instanceof EntityPlayerMPFake)) {
			ItemStack structureVoidStack = new ItemStack(Blocks.STRUCTURE_VOID.asItem());

			structureVoidStack.set(DataComponents.CUSTOM_NAME,
					Component.literal("Revive Stone")
							.withStyle(style -> style.withItalic(false).withColor(ChatFormatting.LIGHT_PURPLE)));

			structureVoidStack.set(DataComponents.LORE,
					new ItemLore(List.of(
							Component.literal("Place this on a corpse, drop a totem")
									.withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GRAY)),
							Component.literal("of undying to revive; player has to be online")
									.withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GRAY)),
							Component.literal("Throw with a golden apple to gain a")
									.withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GRAY)),
							Component.literal("permanent heart (max 10)")
									.withStyle(style -> style.withItalic(false).withColor(ChatFormatting.GRAY))
					)));

			ItemEntity itemEntity = self.spawnAtLocation(self.level(), structureVoidStack);
            if (itemEntity != null) itemEntity.setUnlimitedLifetime();

		}
	}
}