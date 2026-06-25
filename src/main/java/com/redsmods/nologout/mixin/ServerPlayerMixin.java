package com.redsmods.nologout.mixin;

import com.redsmods.nologout.bot.PlayerBotManager;
import net.minecraft.server.level.ServerPlayer;
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
}