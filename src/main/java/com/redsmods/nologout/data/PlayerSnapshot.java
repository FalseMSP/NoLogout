package com.redsmods.nologout.data;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PlayerSnapshot {
    public UUID ownerUUID;
    public String ownerName;
    public ResourceKey<Level> dimensionKey; // ServerLevel is not serializable; store the key
    public Vec3 position;                   // Mojmap Vec3, not joml Vector3d
    public float yaw, pitch;
    public float health;
    public float absorption;
    public int foodLevel;
    public float saturation;
    public float exhaustion;
    public int xpLevel;
    public float xpProgress;
    public int totalXp;
    public List<ItemStack> inventoryItems;  // flat list, indexed by slot (0-40)
    public List<MobEffectInstance> effects; // Mojmap name, not StatusEffects
    public int selectedSlot;
    public int score;

    // Capture all state from a live player
    public static PlayerSnapshot capture(ServerPlayer player) {
        PlayerSnapshot snap = new PlayerSnapshot();

        snap.ownerUUID = player.getUUID();
        snap.ownerName = player.getGameProfile().name();
        snap.dimensionKey = player.level().dimension();
        snap.position = player.position();
        snap.yaw = player.getYRot();
        snap.pitch = player.getXRot();

        snap.health = player.getHealth();
        snap.absorption = player.getAbsorptionAmount();

        snap.foodLevel = player.getFoodData().getFoodLevel();
        snap.saturation = player.getFoodData().getSaturationLevel();
//        snap.exhaustion = player.getFoodData().get();

        snap.xpLevel = player.experienceLevel;
        snap.xpProgress = player.experienceProgress;
        snap.totalXp = player.totalExperience;
        snap.score = player.getScore();

        snap.selectedSlot = player.getInventory().getSelectedSlot();

        // Copy all 41 inventory slots (0-35 main, 36-39 armor, 40 offhand)
        Inventory inv = player.getInventory();
        snap.inventoryItems = new ArrayList<>(inv.getContainerSize());
        for (int i = 0; i < inv.getContainerSize(); i++) {
            snap.inventoryItems.add(inv.getItem(i).copy());
        }

        // Deep copy effects so they aren't mutated after capture
        snap.effects = new ArrayList<>();
        for (MobEffectInstance effect : player.getActiveEffects()) {
            snap.effects.add(new MobEffectInstance(effect));
        }

        return snap;
    }
}