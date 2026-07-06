package com.redsmods.nologout.bot;

import carpet.patches.EntityPlayerMPFake;
import carpet.patches.FakeClientConnection;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.serialization.DynamicOps;
import com.redsmods.nologout.data.PlayerSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.ChatFormatting;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.TeamColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class PlayerBotManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("nologout");

    private static final Map<UUID, EntityPlayerMPFake> activeBots = new HashMap<>();
    private static final Map<UUID, PlayerSnapshot> cachedSnapshots = new HashMap<>();

    // -------------------------------------------------------------------------
    // Summoning-circle safe log-off
    // -------------------------------------------------------------------------

    // How far (in blocks, each axis) to look around the player for a
    // summoning circle's marker block. Mirrors the circle mod's own search
    // radius so "inside the circle" means the same thing in both mods.
    private static final int CIRCLE_CHECK_RADIUS = 1;

    // -------------------------------------------------------------------------
    // Combat-log punishment window
    // -------------------------------------------------------------------------

    // How long after logging off the bot counts as "freshly logged off" for
    // punishment purposes. Also how long the bot displays a red nametag.
    private static final long COMBAT_LOG_WINDOW_MS = 60 * 1000L;

    // Team used purely to color the bot's nametag red during the window above.
    private static final String DANGER_TEAM_NAME = "nologout_danger";

    // ownerUUID -> real-world timestamp (millis) the bot was spawned
    private static final Map<UUID, Long> botSpawnTimestamps = new HashMap<>();

    // ownerUUID -> true if the bot died while inside the punishment window and
    // the owner still needs to be struck down the next time they log back in
    private static final Map<UUID, Boolean> pendingCombatLogDeath = new HashMap<>();

    // ownerUUID -> true if the bot died OUTSIDE the punishment window and the
    // owner still needs the softer "offline death" penalty applied on rejoin
    // (empty inventory, -2 max health, respawn at bed/world spawn).
    private static final Map<UUID, Boolean> offlineDeathPending = new HashMap<>();

    // bot UUID -> suppressed while we deliberately kill a bot ourselves (reconnect,
    // manual removal, offline-death cleanup, etc.) so the die() mixin's call into
    // onBotDeath only fires for real, unexpected deaths.
    private static final Set<UUID> suppressedDeaths = new HashSet<>();
    private static final String HIDDEN_TEAM_NAME = "nologout_hidden";

    // -------------------------------------------------------------------------
    // Called from mixin on player disconnect
    // -------------------------------------------------------------------------

    public static void onPlayerLeave(ServerPlayer player, MinecraftServer server) {
        if (player instanceof EntityPlayerMPFake) return;   // <-- ignore the bot's own disconnect
        if (player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL) return;

        if (activeBots.containsKey(player.getUUID())) {
            removeBotFor(player.getUUID(), server);
        }

        // Logging off standing inside a summoning circle is treated as a safe
        // log-off: no ghost bot stands in for the player, and none of the
        // combat-log punishment bookkeeping below is started. This is a
        // vanilla-equivalent disconnect from here on.
        if (isInsideSummoningCircle(player)) {
            return;
        }

        PlayerSnapshot snap = PlayerSnapshot.capture(player);
        cachedSnapshots.put(snap.ownerUUID, snap);

        // Captured once and threaded through both the on-disk record and the
        // in-memory map so the punishment window survives a server restart
        // instead of silently resetting.
        long spawnTimestamp = System.currentTimeMillis();
        saveSnapshot(snap, spawnTimestamp, false, server);
        spawnBot(snap, server, spawnTimestamp);
    }

    // True if a summoning circle's Blocks.STRUCTURE_VOID marker block is
    // within CIRCLE_CHECK_RADIUS of the player on every axis. Deliberately
    // checks for the marker block itself rather than reaching into the
    // summoning-circle mod's internal state, since the two mods are
    // otherwise independent of each other.
    private static boolean isInsideSummoningCircle(ServerPlayer player) {
        BlockPos center = player.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-CIRCLE_CHECK_RADIUS, -CIRCLE_CHECK_RADIUS, -CIRCLE_CHECK_RADIUS),
                center.offset(CIRCLE_CHECK_RADIUS, CIRCLE_CHECK_RADIUS, CIRCLE_CHECK_RADIUS))) {
            if (player.level().getBlockState(pos).is(Blocks.STRUCTURE_VOID)) {
                player.level().setBlock(pos, Blocks.AIR.defaultBlockState(),0,0);
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Called from mixin on player join
    // -------------------------------------------------------------------------

    public static void onPlayerJoin(ServerPlayer player, MinecraftServer server) {
        if (player.gameMode() == GameType.SPECTATOR) return;
        UUID uuid = player.getUUID();

        // If the bot died inside the combat-log window, the player pays for it
        // the moment they set foot back in the world. This flag (and the
        // matching on-disk copy) survives a restart, so this fires correctly
        // even if the server went down between the bot's death and the
        // player's next login.
        if (Boolean.TRUE.equals(pendingCombatLogDeath.remove(uuid))) {
            // clear their items bc their bot aldr dropped all the stuff
            player.getInventory().clearContent();
            player.removeAllEffects();
            player.setHealth(player.getMaxHealth());
            player.getFoodData().setFoodLevel(20);
            player.getFoodData().setSaturation(5.0F);
            player.experienceLevel = 0;
            player.experienceProgress = 0f;
            player.totalExperience = 0;
            killPlayerInstantly(player);

            cleanupTrackingFor(uuid, server);
            return;
        }

        // Same idea, but for a bot that died OUTSIDE the window — softer
        // penalty, no instant kill. This is checked here (persisted flag)
        // rather than by inspecting a live bot object, so it's correct
        // whether the bot died five seconds ago or the server restarted
        // in between.
        if (Boolean.TRUE.equals(offlineDeathPending.remove(uuid))) {
            handleOfflineDeath(player);
            cleanupTrackingFor(uuid, server);
            return;
        }

        // Any bot still tracked here is guaranteed alive — dead bots are
        // removed from this map (and discarded) the instant onBotDeath fires,
        // so there's no isRemoved()/isDeadOrDying() check needed anymore.
        EntityPlayerMPFake bot = activeBots.get(uuid);
        if (bot != null) {
            restoreFromBot(player, bot);
            cleanupTrackingFor(uuid, server);
            return;
        }

        PlayerSnapshot snap = loadSnapshot(uuid, server);
        if (snap != null) {
            restoreFromSnapshot(player, snap, server);
            cachedSnapshots.remove(uuid);
            deleteSnapshot(uuid, server);
        }

        botSpawnTimestamps.remove(uuid);
    }

    // The punishment/restore has now been paid or applied — clear whatever
    // bookkeeping (in-memory or on-disk) was still hanging around for this player.
    private static void cleanupTrackingFor(UUID uuid, MinecraftServer server) {
        activeBots.remove(uuid);
        cachedSnapshots.remove(uuid);
        deleteSnapshot(uuid, server);
        botSpawnTimestamps.remove(uuid);
    }

    // -------------------------------------------------------------------------
    // Server-restart recovery
    // -------------------------------------------------------------------------

    // Call this once from the mod's server-started lifecycle hook (e.g.
    // ServerLifecycleEvents.SERVER_STARTED on Fabric) so that any bots which
    // were active when the server last went down come back instead of just
    // vanishing along with their punishment state. Safe to call even if the
    // nologout snapshot directory is empty.
    public static void restoreAllOnStartup(MinecraftServer server) {
        File dir = getSnapshotDir(server);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".dat"));
        if (files == null || files.length == 0) return;

        int restored = 0;
        for (File file : files) {
            String fileName = file.getName();
            UUID ownerUUID;
            try {
                ownerUUID = UUID.fromString(fileName.substring(0, fileName.length() - ".dat".length()));
            } catch (IllegalArgumentException e) {
                LOGGER.warn("nologout: skipping unrecognized file in snapshot dir: {}", fileName);
                continue;
            }

            SnapshotMeta meta = loadSnapshotWithMeta(ownerUUID, server);
            if (meta == null) continue;

            cachedSnapshots.put(ownerUUID, meta.snapshot());

            if (meta.pendingCombatLogDeath()) {
                // The bot had already died inside the punishment window before
                // the restart. There's nothing to respawn — just remember that
                // the owner still owes a death the next time they log in.
                pendingCombatLogDeath.put(ownerUUID, Boolean.TRUE);
                LOGGER.info("nologout: restored pending combat-log punishment for {}",
                        meta.snapshot().ownerName);
            } else if (meta.botDied()) {
                // The bot died outside the window before the restart. Same
                // deal, softer penalty — do NOT respawn it, or it comes back
                // from the dead every time the server restarts.
                offlineDeathPending.put(ownerUUID, Boolean.TRUE);
                LOGGER.info("nologout: restored pending offline-death penalty for {}",
                        meta.snapshot().ownerName);
            } else {
                // The bot was alive when the server went down. Bring it back so
                // it keeps standing in for the offline player, continuing the
                // same combat-log window it originally started with (not a
                // fresh one) so a restart can't be used to dodge punishment.
                spawnBot(meta.snapshot(), server, meta.spawnTimestamp());
                LOGGER.info("nologout: respawned ghost bot for {} after restart",
                        meta.snapshot().ownerName);
            }
            restored++;
        }

        if (restored > 0) {
            LOGGER.info("nologout: restored {} offline player(s) after restart", restored);
        }
    }

    // -------------------------------------------------------------------------
    // Bot GameProfile construction
    // -------------------------------------------------------------------------

    // As of the 26.1 unobfuscated rework, a freshly constructed GameProfile's
    // properties() comes back as an immutable, empty PropertyMap — putAll()/
    // put() on it always throws UnsupportedOperationException, unlike older
    // versions where it was backed by a mutable HashMultimap. There's no
    // documented public constructor for pre-populating it either, so this
    // tries a 3-arg constructor first (in case one exists on this version)
    // and falls back to patching the private field via reflection. If both
    // fail, the bot still spawns — just with a default skin instead of the
    // owner's — rather than taking the whole server down.
    private static GameProfile createBotProfile(String ghostName, PropertyMap skinProperties) {
        UUID botId = UUID.randomUUID();

        // Same rule as PlayerSnapshot.capture(): PropertyMap's backing
        // multimap becomes immutable as soon as it's constructed, so build
        // the multimap fully first and construct the PropertyMap once.
        com.google.common.collect.Multimap<String, Property> props = com.google.common.collect.HashMultimap.create();
        props.putAll(skinProperties);
        PropertyMap mutableProps = new PropertyMap(props);

        try {
            java.lang.reflect.Constructor<GameProfile> ctor =
                    GameProfile.class.getConstructor(UUID.class, String.class, PropertyMap.class);
            return ctor.newInstance(botId, ghostName, mutableProps);
        } catch (NoSuchMethodException ignored) {
            // No such constructor on this version — fall through to the field patch below.
        } catch (ReflectiveOperationException e) {
            LOGGER.error("nologout: failed to build bot profile via 3-arg GameProfile constructor", e);
        }

        GameProfile profile = new GameProfile(botId, ghostName);
        try {
            java.lang.reflect.Field propertiesField = GameProfile.class.getDeclaredField("properties");
            propertiesField.setAccessible(true);
            propertiesField.set(profile, mutableProps);
        } catch (ReflectiveOperationException e) {
            LOGGER.error("nologout: could not apply skin properties to bot profile for {} — bot will spawn with a default skin",
                    ghostName, e);
        }
        return profile;
    }

    // -------------------------------------------------------------------------
    // Spawn bot from snapshot
    // -------------------------------------------------------------------------

    private static void spawnBot(PlayerSnapshot snap, MinecraftServer server, long spawnTimestamp) {
        ServerLevel level = server.getLevel(snap.dimensionKey);
        if (level == null) {
            LOGGER.warn("nologout: dimension {} not found for {}, falling back to overworld",
                    snap.dimensionKey.identifier(), snap.ownerName);
            level = server.overworld();
        }
        String ghostName = "-" + snap.ownerName;
        if (ghostName.length() > 16) {
            ghostName = ghostName.substring(0, 16);
        }
        // Copy the real player's skin/cape textures onto the fake profile so
        // the bot looks like the player it's standing in for.
        GameProfile profile = createBotProfile(ghostName, snap.skinProperties);
        EntityPlayerMPFake bot = EntityPlayerMPFake.respawnFake(server, level, profile,
                ClientInformation.createDefault());

        final ServerLevel finalLevel = level;
        bot.fixStartingPosition = () -> bot.snapTo(
                snap.position.x, snap.position.y, snap.position.z,
                snap.yaw, snap.pitch);

        server.getPlayerList().placeNewPlayer(
                new FakeClientConnection(PacketFlow.SERVERBOUND),
                bot,
                new CommonListenerCookie(profile, 0, bot.clientInformation(), false));

        bot.stopRiding();
        bot.teleportTo(finalLevel,
                snap.position.x, snap.position.y, snap.position.z,
                Set.of(), snap.yaw, snap.pitch, true);
        bot.unsetRemoved();

        applySnapshotToBot(bot, snap);

        bot.getAttribute(Attributes.STEP_HEIGHT).setBaseValue(0.6F);
        bot.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
//        bot.entityData.set(ServerPlayer.DATA_PLAYER_MODE_CUSTOMISATION, (byte) 0x7f);

        // Keep the bot off other players' locator bars entirely.
        bot.getAttribute(Attributes.WAYPOINT_TRANSMIT_RANGE).setBaseValue(0.0D);

        // Start (or resume, after a restart) the combat-log punishment window
        // and flag the bot red for the remainder of its duration.
        botSpawnTimestamps.put(snap.ownerUUID, spawnTimestamp);
        pendingCombatLogDeath.remove(snap.ownerUUID);
        if (System.currentTimeMillis() - spawnTimestamp > COMBAT_LOG_WINDOW_MS) {
            applyHiddenNameTag(bot, server);
            botSpawnTimestamps.remove(snap.ownerUUID);
        } else {
            applyDangerNameTag(bot, server);
        }

        server.getPlayerList().broadcastAll(
                new ClientboundRotateHeadPacket(bot, (byte) (bot.yHeadRot * 256 / 360)),
                snap.dimensionKey);
        server.getPlayerList().broadcastAll(
                ClientboundEntityPositionSyncPacket.of(bot),
                snap.dimensionKey);

        activeBots.put(snap.ownerUUID, bot);
        LOGGER.info("nologout: spawned ghost bot for {}", snap.ownerName);
    }

    // -------------------------------------------------------------------------
    // Apply snapshot state onto a bot
    // -------------------------------------------------------------------------

    private static void applySnapshotToBot(EntityPlayerMPFake bot, PlayerSnapshot snap) {
        bot.setHealth(snap.health);
        bot.setAbsorptionAmount(snap.absorption);
        bot.getFoodData().setFoodLevel(snap.foodLevel);
        bot.getFoodData().setSaturation(snap.saturation);
        bot.experienceLevel = snap.xpLevel;
        bot.experienceProgress = snap.xpProgress;
        bot.totalExperience = snap.totalXp;

        bot.getInventory().clearContent();
        for (int i = 0; i < snap.inventoryItems.size(); i++) {
            bot.getInventory().setItem(i, snap.inventoryItems.get(i).copy());
        }
        bot.getInventory().setSelectedSlot(snap.selectedSlot);

        bot.removeAllEffects();
        for (MobEffectInstance effect : snap.effects) {
            bot.addEffect(new MobEffectInstance(effect));
        }
    }

    // -------------------------------------------------------------------------
    // Restore real player from live bot
    // -------------------------------------------------------------------------

    private static void restoreFromBot(ServerPlayer player, EntityPlayerMPFake bot) {
        ServerLevel level = (ServerLevel) bot.level();
        player.teleportTo(level,
                bot.getX(), bot.getY(), bot.getZ(),
                Set.of(), bot.getYRot(), bot.getXRot(), false);

        player.setHealth(bot.getHealth());
        player.setAbsorptionAmount(bot.getAbsorptionAmount());
        player.getFoodData().setFoodLevel(bot.getFoodData().getFoodLevel());
        player.getFoodData().setSaturation(bot.getFoodData().getSaturationLevel());
        player.experienceLevel = bot.experienceLevel;
        player.experienceProgress = bot.experienceProgress;
        player.totalExperience = bot.totalExperience;
        player.setScore(bot.getScore());

        player.getInventory().clearContent();
        for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
            player.getInventory().setItem(i, bot.getInventory().getItem(i).copy());
        }
        player.getInventory().setSelectedSlot(bot.getInventory().getSelectedSlot());

        player.removeAllEffects();
        for (MobEffectInstance effect : bot.getActiveEffects()) {
            player.addEffect(new MobEffectInstance(effect));
        }

        String botScoreboardName = bot.getScoreboardName();
        killBotQuietly(bot, "Player reconnected");
        removeNologoutNameTag(botScoreboardName, player.level().getServer());
        LOGGER.info("nologout: restored {} from ghost bot", player.getGameProfile().name());
    }

    // -------------------------------------------------------------------------
    // Bot died while the player was offline — treat it as a real death
    // -------------------------------------------------------------------------

    private static void handleOfflineDeath(ServerPlayer player) {
        player.getInventory().clearContent();
        player.removeAllEffects();
        player.setHealth(player.getMaxHealth());
        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5.0F);
        player.experienceLevel = 0;
        player.experienceProgress = 0f;
        player.totalExperience = 0;

        // Reuses the same resolution vanilla uses on a real death: validates the player's bed/
        // respawn anchor is still standing and usable, consumes an anchor charge if so, and
        // falls back to world spawn (via TeleportTransition.missingRespawnBlock) if it's gone,
        // unset, or in an unloaded/invalid dimension.
        TeleportTransition respawnTransition = player.findRespawnPositionAndUseSpawnBlock(true, TeleportTransition.DO_NOTHING);
        player.teleport(respawnTransition);

        // The bot itself was already discarded back when it actually died
        // (see onBotDeath) — nothing left to clean up here.

        AttributeInstance attr = player.getAttribute(Attributes.MAX_HEALTH);
        if (attr == null) return;

        double newMax = attr.getBaseValue() - 2.0;
        attr.setBaseValue(Math.max(1.0, newMax));

        LOGGER.info("nologout: {} reconnected to find their ghost bot had died — respawned with an empty inventory",
                player.getGameProfile().name());
    }

    // -------------------------------------------------------------------------
    // Restore real player from disk snapshot (bot died before reconnect)
    // -------------------------------------------------------------------------

    private static void restoreFromSnapshot(ServerPlayer player, PlayerSnapshot snap,
                                            MinecraftServer server) {
        ServerLevel level = server.getLevel(snap.dimensionKey);
        if (level == null) level = server.overworld();

        player.teleportTo(level,
                snap.position.x, snap.position.y, snap.position.z,
                Set.of(), snap.yaw, snap.pitch, false);

        player.setHealth(snap.health);
        player.setAbsorptionAmount(snap.absorption);
        player.getFoodData().setFoodLevel(snap.foodLevel);
        player.getFoodData().setSaturation(snap.saturation);
        player.experienceLevel = snap.xpLevel;
        player.experienceProgress = snap.xpProgress;
        player.totalExperience = snap.totalXp;

        player.getInventory().clearContent();
        for (int i = 0; i < snap.inventoryItems.size(); i++) {
            player.getInventory().setItem(i, snap.inventoryItems.get(i).copy());
        }
        player.getInventory().setSelectedSlot(snap.selectedSlot);

        player.removeAllEffects();
        for (MobEffectInstance effect : snap.effects) {
            player.addEffect(new MobEffectInstance(effect));
        }

        LOGGER.info("nologout: restored {} from disk snapshot (bot had died)", snap.ownerName);
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    // Kills a bot on purpose (reconnect, manual removal, etc.) without letting
    // that death be mistaken for a real one by the die() mixin.
    private static void killBotQuietly(EntityPlayerMPFake bot, String reason) {
        suppressedDeaths.add(bot.getUUID());
        try {
            bot.kill(net.minecraft.network.chat.Component.literal(reason));
        } finally {
            suppressedDeaths.remove(bot.getUUID());
        }
    }

    public static void removeBotFor(UUID ownerUUID) {
        EntityPlayerMPFake bot = activeBots.remove(ownerUUID);
        if (bot != null) {
            killBotQuietly(bot, "Bot removed");
        }
        botSpawnTimestamps.remove(ownerUUID);
    }

    // Prefer this overload wherever a MinecraftServer is available so the
    // bot's scoreboard team membership (used for the red nametag) is cleaned up.
    public static void removeBotFor(UUID ownerUUID, MinecraftServer server) {
        EntityPlayerMPFake bot = activeBots.remove(ownerUUID);
        if (bot != null) {
            killBotQuietly(bot, "Bot removed");
            removeNologoutNameTag(bot.getScoreboardName(), server);
        }
        botSpawnTimestamps.remove(ownerUUID);
    }

    public static boolean hasBotFor(UUID ownerUUID) {
        return activeBots.containsKey(ownerUUID);
    }

    // -------------------------------------------------------------------------
    // Bot death hook — call this from the mixin that detects the fake player's
    // real death (fall damage, mobs, PvP, etc.), NOT from the manual kill()
    // calls above/in restoreFromBot, which represent the "safe" reconnect path.
    // -------------------------------------------------------------------------

    // Entry point for the mixin injected into ServerPlayer#die. Fires for every
    // player death, so it filters down to real, untracked bot deaths before
    // delegating to the UUID-keyed overload below.
    public static void onBotDeath(ServerPlayer player, MinecraftServer server) {
        if (!(player instanceof EntityPlayerMPFake bot)) return;
        if (suppressedDeaths.contains(bot.getUUID())) return; // one of our own kill() calls

        UUID ownerUUID = null;
        for (Map.Entry<UUID, EntityPlayerMPFake> entry : activeBots.entrySet()) {
            if (entry.getValue() == bot) {
                ownerUUID = entry.getKey();
                break;
            }
        }
        if (ownerUUID == null) return; // not a bot we're currently tracking

        onBotDeath(ownerUUID, server);
    }

    public static void onBotDeath(UUID ownerUUID, MinecraftServer server) {
        Long spawnTime = botSpawnTimestamps.remove(ownerUUID);
        boolean withinWindow = spawnTime != null
                && (System.currentTimeMillis() - spawnTime) <= COMBAT_LOG_WINDOW_MS;

        // Persist the outcome immediately, regardless of which case this is —
        // if the server goes down before the owner reconnects, this on-disk
        // flag (not the in-memory map, and NOT the bot entity) is what tells
        // both onPlayerJoin() and restoreAllOnStartup() what happened. This is
        // also what stops a dead bot from being respawned after a restart.
        if (withinWindow) {
            pendingCombatLogDeath.put(ownerUUID, Boolean.TRUE);
            persistDeathFlag(ownerUUID, META_PENDING_COMBAT_LOG_DEATH, server);
            LOGGER.info("nologout: bot for {} died within the combat-log window; " +
                    "owner will be struck down on rejoin", ownerUUID);
        } else {
            offlineDeathPending.put(ownerUUID, Boolean.TRUE);
            persistDeathFlag(ownerUUID, META_BOT_DIED, server);
            LOGGER.info("nologout: bot for {} died outside the combat-log window; " +
                    "owner will be respawned with a penalty on rejoin", ownerUUID);
        }

        // The bot has done its job — stop tracking it and get rid of the
        // entity now rather than leaving a dead body around for the owner's
        // eventual rejoin to clean up. discard() (not kill()) since it's
        // already dead; this just removes it from the world.
        EntityPlayerMPFake bot = activeBots.remove(ownerUUID);
        if (bot != null) {
            removeNologoutNameTag(bot.getScoreboardName(), server);
            if (!bot.isRemoved()) {
                bot.discard();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Periodic upkeep — call this once every second or so from an existing
    // server tick hook so bots' nametags turn back to normal once the
    // combat-log window has passed (punishment logic itself doesn't depend
    // on this being called, since onBotDeath checks elapsed time directly).
    // -------------------------------------------------------------------------

    public static void tick(MinecraftServer server) {
        if (botSpawnTimestamps.isEmpty()) return;

        long now = System.currentTimeMillis();
        for (UUID ownerUUID : new ArrayList<>(botSpawnTimestamps.keySet())) {
            Long spawnTime = botSpawnTimestamps.get(ownerUUID);
            if (spawnTime == null || now - spawnTime <= COMBAT_LOG_WINDOW_MS) continue;

            EntityPlayerMPFake bot = activeBots.get(ownerUUID);
            if (bot != null) {
                applyHiddenNameTag(bot, server); // adding to a new team implicitly moves it off the danger team
            }
            botSpawnTimestamps.remove(ownerUUID);
        }
        // make them swim
        for (EntityPlayerMPFake bot : activeBots.values()) {
            setJumping(bot, bot.isInWater());
        }
    }

    // -------------------------------------------------------------------------
    // Red nametag helpers (purely cosmetic — scoreboard team color)
    // -------------------------------------------------------------------------

    private static void applyDangerNameTag(EntityPlayerMPFake bot, MinecraftServer server) {
        // Danger window: stand normally so the red nametag is fully visible.
        setBotSneaking(bot, false);

        // make bot float if in water
        if (bot.isInWater()) setJumping(bot, true);

        Scoreboard scoreboard = server.getScoreboard();
        PlayerTeam team = scoreboard.getPlayerTeam(DANGER_TEAM_NAME);
        if (team == null) {
            team = scoreboard.addPlayerTeam(DANGER_TEAM_NAME);
            team.setColor(Optional.of(TeamColor.RED));
        }
        scoreboard.addPlayerToTeam(bot.getScoreboardName(), team);
    }

    // Applied once the combat-log window has passed. Rather than forcing the
    // nametag invisible via a scoreboard team (which looked artificial and
    // gave the bot away as fake), the bot is put into a permanent crouch.
    // That makes its nametag behave exactly like a real sneaking player's —
    // hidden/shortened visibility to other clients — using vanilla mechanics
    // instead of an unconditional override.
    private static void applyHiddenNameTag(EntityPlayerMPFake bot, MinecraftServer server) {
        removeNologoutNameTag(bot.getScoreboardName(), server); // drop off the red team, if present
        setBotSneaking(bot, true);
    }

    // Forces the bot's crouch/shift state (and pose, for an immediate visual
    // update rather than waiting on the next tick) so its nametag visibility
    // follows the same rules a real sneaking player's would.
    private static void setBotSneaking(EntityPlayerMPFake bot, boolean sneaking) {
        if (!bot.isInWater()) bot.setShiftKeyDown(sneaking);
        bot.setPose(sneaking ? Pose.CROUCHING : Pose.STANDING);
    }

    // Forces the bot to hold space
    private static void setJumping(EntityPlayerMPFake bot, boolean jumping) {
        bot.setJumping(jumping);
    }

    // Removes a bot's scoreboard name from whichever nologout team it's
    // currently in (danger or hidden) — used on cleanup so entries don't linger.
    private static void removeNologoutNameTag(String scoreboardName, MinecraftServer server) {
        Scoreboard scoreboard = server.getScoreboard();
        PlayerTeam team = scoreboard.getPlayersTeam(scoreboardName);
        if (team != null && (team.getName().equals(DANGER_TEAM_NAME) || team.getName().equals(HIDDEN_TEAM_NAME))) {
            scoreboard.removePlayerFromTeam(scoreboardName, team);
        }
    }

    // -------------------------------------------------------------------------
    // Combat-log punishment — instantly kills a returning player
    // -------------------------------------------------------------------------

    private static void killPlayerInstantly(ServerPlayer player) {
        player.setHealth(0.0F);
        player.die(player.damageSources().generic());
        LOGGER.info("nologout: {} logged back in after a combat-log death and was struck down",
                player.getGameProfile().name());
    }

    // -------------------------------------------------------------------------
    // Snapshot file path
    // -------------------------------------------------------------------------

    private static File getSnapshotDir(MinecraftServer server) {
        File dir = new File(server.getWorldPath(LevelResource.ROOT).toFile(), "nologout");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private static File getSnapshotFile(UUID uuid, MinecraftServer server) {
        return new File(getSnapshotDir(server), uuid + ".dat");
    }

    // -------------------------------------------------------------------------
    // Persisted metadata alongside each snapshot (spawn time + pending
    // combat-log death) so an active bot's punishment state survives a
    // server restart instead of resetting along with the static maps above.
    // -------------------------------------------------------------------------

    private static final String META_SPAWN_TIMESTAMP = "spawnTimestamp";
    private static final String META_PENDING_COMBAT_LOG_DEATH = "pendingCombatLogDeath";
    private static final String META_BOT_DIED = "botDied";

    private record SnapshotMeta(PlayerSnapshot snapshot, long spawnTimestamp,
                                boolean pendingCombatLogDeath, boolean botDied) {}

    private static void saveSnapshot(PlayerSnapshot snap, long spawnTimestamp,
                                     boolean pendingCombatLogDeath, MinecraftServer server) {
        try {
            CompoundTag tag = serializeSnapshot(snap, server);
            tag.putLong(META_SPAWN_TIMESTAMP, spawnTimestamp);
            tag.putBoolean(META_PENDING_COMBAT_LOG_DEATH, pendingCombatLogDeath);
            tag.putBoolean(META_BOT_DIED, false); // freshly spawned, definitionally alive
            NbtIo.writeCompressed(tag, getSnapshotFile(snap.ownerUUID, server).toPath());
        } catch (IOException e) {
            LOGGER.error("nologout: failed to save snapshot for {}", snap.ownerName, e);
        }
    }

    // Flips a single boolean flag on an already-saved snapshot file, without
    // re-deriving the rest of the NBT from a PlayerSnapshot object. Called
    // from onBotDeath so the outcome hits disk the moment it's known, rather
    // than only at the next full save (which may never come if the bot just
    // died and there's nothing left to save).
    private static void persistDeathFlag(UUID ownerUUID, String flagKey, MinecraftServer server) {
        File file = getSnapshotFile(ownerUUID, server);
        if (!file.exists()) {
            LOGGER.warn("nologout: no on-disk snapshot to mark {} for {}", flagKey, ownerUUID);
            return;
        }
        try {
            CompoundTag tag = NbtIo.readCompressed(file.toPath(),
                    net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            tag.putBoolean(flagKey, true);
            NbtIo.writeCompressed(tag, file.toPath());
        } catch (IOException e) {
            LOGGER.error("nologout: failed to persist {} for {}", flagKey, ownerUUID, e);
        }
    }

    private static SnapshotMeta loadSnapshotWithMeta(UUID uuid, MinecraftServer server) {
        File file = getSnapshotFile(uuid, server);
        if (!file.exists()) return null;
        try {
            CompoundTag tag = NbtIo.readCompressed(file.toPath(),
                    net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            PlayerSnapshot snap = deserializeSnapshot(tag, uuid, server);
            long spawnTimestamp = tag.getLong(META_SPAWN_TIMESTAMP).orElse(0L);
            boolean pendingCombatLogDeath = tag.getBoolean(META_PENDING_COMBAT_LOG_DEATH).orElse(false);
            boolean botDied = tag.getBoolean(META_BOT_DIED).orElse(false);
            return new SnapshotMeta(snap, spawnTimestamp, pendingCombatLogDeath, botDied);
        } catch (IOException e) {
            LOGGER.error("nologout: failed to load snapshot for {}", uuid, e);
            return null;
        }
    }

    private static PlayerSnapshot loadSnapshot(UUID uuid, MinecraftServer server) {
        SnapshotMeta meta = loadSnapshotWithMeta(uuid, server);
        return meta == null ? null : meta.snapshot();
    }

    private static void deleteSnapshot(UUID uuid, MinecraftServer server) {
        File file = getSnapshotFile(uuid, server);
        if (file.exists() && !file.delete()) {
            LOGGER.warn("nologout: could not delete snapshot file for {}", uuid);
        }
    }

    // -------------------------------------------------------------------------
    // NBT serialization
    // -------------------------------------------------------------------------

    private static CompoundTag serializeSnapshot(PlayerSnapshot snap, MinecraftServer server) {
        CompoundTag tag = new CompoundTag();
        RegistryAccess registries = server.registryAccess();
        // RegistryOps wraps NbtOps with registry lookup so items/effects serialize correctly
        DynamicOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);

        tag.putString("ownerUUID", snap.ownerUUID.toString());
        tag.putString("ownerName", snap.ownerName);
        // ResourceKey.location() gives the ResourceLocation (e.g. minecraft:overworld)
        tag.putString("dimension", snap.dimensionKey.identifier().toString());
        tag.putDouble("x", snap.position.x);
        tag.putDouble("y", snap.position.y);
        tag.putDouble("z", snap.position.z);
        tag.putFloat("yaw", snap.yaw);
        tag.putFloat("pitch", snap.pitch);
        tag.putFloat("health", snap.health);
        tag.putFloat("absorption", snap.absorption);
        tag.putInt("foodLevel", snap.foodLevel);
        tag.putFloat("saturation", snap.saturation);
        tag.putFloat("exhaustion", snap.exhaustion);
        tag.putInt("xpLevel", snap.xpLevel);
        tag.putFloat("xpProgress", snap.xpProgress);
        tag.putInt("totalXp", snap.totalXp);
        tag.putInt("score", snap.score);
        tag.putInt("selectedSlot", snap.selectedSlot);

        // Inventory — use ItemStack.CODEC with RegistryOps
        ListTag invList = new ListTag();
        for (int i = 0; i < snap.inventoryItems.size(); i++) {
            ItemStack stack = snap.inventoryItems.get(i);
            if (!stack.isEmpty()) {
                CompoundTag slotTag = new CompoundTag();
                slotTag.putInt("slot", i);
                // ItemStack.CODEC encodes to a Tag; cast is safe since NbtOps produces Tag
                int finalI = i;
                Tag itemTag = ItemStack.CODEC.encodeStart(ops, stack)
                        .resultOrPartial(e -> LOGGER.error("nologout: failed to encode item at slot {}: {}", finalI, e))
                        .orElse(null);
                if (itemTag != null) {
                    slotTag.put("item", itemTag);
                    invList.add(slotTag);
                }
            }
        }
        tag.put("inventory", invList);

        // Effects — MobEffectInstance.CODEC
        ListTag effectList = new ListTag();
        for (MobEffectInstance effect : snap.effects) {
            Tag effectTag = MobEffectInstance.CODEC.encodeStart(ops, effect)
                    .resultOrPartial(e -> LOGGER.error("nologout: failed to encode effect: {}", e))
                    .orElse(null);
            if (effectTag != null) {
                effectList.add(effectTag);
            }
        }
        tag.put("effects", effectList);

        // Skin/cape texture properties, so the fake bot's appearance survives
        // a server restart along with the rest of the snapshot.
        ListTag skinList = new ListTag();
        for (Property prop : snap.skinProperties.get("textures")) {
            CompoundTag propTag = new CompoundTag();
            propTag.putString("name", prop.name());
            propTag.putString("value", prop.value());
            if (prop.hasSignature()) {
                propTag.putString("signature", prop.signature());
            }
            skinList.add(propTag);
        }
        tag.put("skinProperties", skinList);

        return tag;
    }

    private static PlayerSnapshot deserializeSnapshot(CompoundTag tag, UUID uuid,
                                                      MinecraftServer server) {
        PlayerSnapshot snap = new PlayerSnapshot();
        RegistryAccess registries = server.registryAccess();
        DynamicOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);

        // getUUID is gone — parse from string
        snap.ownerUUID = UUID.fromString(tag.getString("ownerUUID").orElse(uuid.toString()));
        // getString returns Optional<String> in 26.x
        snap.ownerName = tag.getString("ownerName").orElse("unknown");

        String dimStr = tag.getString("dimension").orElse("minecraft:overworld");
        snap.dimensionKey = ResourceKey.create(Registries.DIMENSION,
                Identifier.parse(dimStr));

        snap.position = new Vec3(
                tag.getDouble("x").orElse(0.0),
                tag.getDouble("y").orElse(64.0),
                tag.getDouble("z").orElse(0.0));
        snap.yaw   = tag.getFloat("yaw").orElse(0f);
        snap.pitch = tag.getFloat("pitch").orElse(0f);

        snap.health     = tag.getFloat("health").orElse(20f);
        snap.absorption = tag.getFloat("absorption").orElse(0f);
        snap.foodLevel  = tag.getInt("foodLevel").orElse(20);
        snap.saturation = tag.getFloat("saturation").orElse(5f);
        snap.exhaustion = tag.getFloat("exhaustion").orElse(0f);
        snap.xpLevel    = tag.getInt("xpLevel").orElse(0);
        snap.xpProgress = tag.getFloat("xpProgress").orElse(0f);
        snap.totalXp    = tag.getInt("totalXp").orElse(0);
        snap.score      = tag.getInt("score").orElse(0);
        snap.selectedSlot = tag.getInt("selectedSlot").orElse(0);

        // Inventory
        snap.inventoryItems = new ArrayList<>();
        for (int i = 0; i < 41; i++) snap.inventoryItems.add(ItemStack.EMPTY);

        ListTag invList = tag.getList("inventory").orElse(new ListTag());
        for (int i = 0; i < invList.size(); i++) {
            CompoundTag slotTag = invList.getCompound(i).orElse(null);
            if (slotTag == null) continue;

            int slot = slotTag.getInt("slot").orElse(-1);
            if (slot < 0 || slot >= 41) continue;

            Tag itemTag = slotTag.get("item").asCompound().orElse(null);
            if (itemTag == null) continue;

            ItemStack.CODEC.parse(ops, itemTag)
                    .resultOrPartial(e -> LOGGER.error("nologout: failed to decode item at slot {}: {}", slot, e))
                    .ifPresent(stack -> snap.inventoryItems.set(slot, stack));
        }

        // Effects
        snap.effects = new ArrayList<>();
        ListTag effectList = tag.getList("effects").orElse(new ListTag());
        for (int i = 0; i < effectList.size(); i++) {
            effectList.getCompound(i).ifPresent(effectTag ->
                    MobEffectInstance.CODEC.parse(ops, effectTag)
                            .resultOrPartial(e -> LOGGER.error("nologout: failed to decode effect: {}", e))
                            .ifPresent(snap.effects::add)
            );
        }

        // Skin/cape texture properties
        com.google.common.collect.Multimap<String, Property> props = com.google.common.collect.HashMultimap.create();
        ListTag skinList = tag.getList("skinProperties").orElse(new ListTag());
        for (int i = 0; i < skinList.size(); i++) {
            skinList.getCompound(i).ifPresent(propTag -> {
                String value = propTag.getString("value").orElse(null);
                if (value == null) return;
                String name = propTag.getString("name").orElse("textures");
                String signature = propTag.getString("signature").orElse(null);
                Property prop = signature != null
                        ? new Property(name, value, signature)
                        : new Property(name, value);
                props.put(name, prop);
            });
        }
        snap.skinProperties = new com.mojang.authlib.properties.PropertyMap(props);

        return snap;
    }
}