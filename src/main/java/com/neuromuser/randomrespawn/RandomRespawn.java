package com.neuromuser.randomrespawn;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.advancement.Advancement;
import net.minecraft.advancement.AdvancementProgress;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkStatus;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class RandomRespawn implements ModInitializer {
    private static final int MAX_SEARCH_ITERATIONS = 50;
    private static final int CANDIDATE_TIMEOUT_TICKS = 100;
    private static final int LOADING_TIMEOUT_TICKS = 400;
    private static final int MIN_LOADING_TICKS = 30;
    private static final int POST_TELEPORT_TICKS = 60;
    private static final Set<Block> HAZARDOUS_BLOCKS = Set.of(
            Blocks.MAGMA_BLOCK,
            Blocks.CACTUS,
            Blocks.CAMPFIRE,
            Blocks.SOUL_CAMPFIRE,
            Blocks.POWDER_SNOW,
            Blocks.SWEET_BERRY_BUSH,
            Blocks.WITHER_ROSE,
            Blocks.POINTED_DRIPSTONE
    );

    private static Path configPath;
    private final Map<UUID, Integer> playerRetryCount = new ConcurrentHashMap<>();
    private final Map<UUID, RespawnSession> sessions = new ConcurrentHashMap<>();

    private enum Phase {
        SEARCHING,
        LOADING,
        TELEPORTED
    }

    private static final class RespawnSession {
        final ServerWorld world;
        Phase phase = Phase.SEARCHING;
        ChunkPos candidate;
        int searchIteration;
        int candidateTicks;
        boolean searchInFlight;
        boolean searchExhausted;
        BlockPos targetPos;
        final Set<ChunkPos> chunksToLoad = new HashSet<>();
        final Set<ChunkPos> loadedChunks = new HashSet<>();
        int phaseTicks;

        RespawnSession(ServerWorld world) {
            this.world = world;
        }

        void setTarget(BlockPos pos) {
            this.targetPos = pos;
            this.phase = Phase.LOADING;
            this.phaseTicks = 0;
            ChunkPos center = new ChunkPos(pos);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    chunksToLoad.add(new ChunkPos(center.x + dx, center.z + dz));
                }
            }
        }
    }

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            Path worldConfigPath = server.getSavePath(WorldSavePath.ROOT).resolve("randomrespawn.json");
            ConfigManager.load(worldConfigPath);
            configPath = worldConfigPath;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (sessions.isEmpty()) return;

            Iterator<Map.Entry<UUID, RespawnSession>> it = sessions.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, RespawnSession> entry = it.next();
                UUID uuid = entry.getKey();
                RespawnSession session = entry.getValue();

                ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
                if (player == null || player.isRemoved()) {
                    it.remove();
                    continue;
                }

                try {
                    session.phaseTicks++;
                    boolean done = switch (session.phase) {
                        case SEARCHING -> tickSearching(uuid, session, player);
                        case LOADING -> tickLoading(uuid, session, player);
                        case TELEPORTED -> tickTeleported(session, player);
                    };
                    if (done) {
                        it.remove();
                    }
                } catch (Exception e) {
                    System.err.println("Random respawn failed for " + uuid + ": " + e);
                    finishSession(player);
                    it.remove();
                }
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.player;
            String uuidStr = player.getUuidAsString();
            Config config = ConfigManager.get();

            if (config.pendingRespawns.contains(uuidStr)) {
                removeInvulnerability(player);
            }

            if (!config.playerSettings.containsKey(uuidStr)) {
                config.playerSettings.put(uuidStr, config.defaultEnabled);
                if (configPath != null) {
                    ConfigManager.save(configPath);
                }
                if (config.defaultEnabled) {
                    startSession(player.getServerWorld(), player);
                }
            }
        });

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (alive) return;

            Config config = ConfigManager.get();
            boolean enabled = config.playerSettings.getOrDefault(
                    newPlayer.getUuidAsString(),
                    config.defaultEnabled
            );
            if (!enabled) return;

            ServerWorld world = newPlayer.getServerWorld();
            if (config.setDayTimeOnPlayerDeath) {
                world.setTimeOfDay(1000L);
            }
            resetPlayerProgress(newPlayer);
            startSession(world, newPlayer);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID uuid = handler.player.getUuid();
            playerRetryCount.remove(uuid);
            sessions.remove(uuid);
            if (ConfigManager.get().pendingRespawns.contains(handler.player.getUuidAsString())) {
                removeInvulnerability(handler.player);
            }
        });

        registerCommands();
    }

    private boolean tickSearching(UUID uuid, RespawnSession session, ServerPlayerEntity player) {
        if (session.searchExhausted) {
            finishSession(player);
            return true;
        }

        if (session.candidate == null) {
            if (!session.searchInFlight) {
                requestSearch(session, uuid, session.searchIteration);
            }
            return false;
        }

        ChunkPos cp = session.candidate;
        session.world.getChunkManager().addTicket(ChunkTicketType.POST_TELEPORT, cp, 0, player.getId());

        if (session.world.getChunk(cp.x, cp.z, ChunkStatus.FULL, false) != null) {
            BlockPos spawnPos = findSurfaceSpawn(session.world, cp);
            if (spawnPos != null) {
                session.setTarget(spawnPos);
                ConfigNetworking.sendProgress(player, "randomrespawn.generating", 0);
            } else {
                rejectCandidate(session, uuid);
            }
        } else if (++session.candidateTicks > CANDIDATE_TIMEOUT_TICKS) {
            rejectCandidate(session, uuid);
        }

        return false;
    }

    private void rejectCandidate(RespawnSession session, UUID uuid) {
        session.candidate = null;
        session.candidateTicks = 0;
        requestSearch(session, uuid, session.searchIteration + 1);
    }

    private boolean tickLoading(UUID uuid, RespawnSession session, ServerPlayerEntity player) {
        for (ChunkPos cp : session.chunksToLoad) {
            session.world.getChunkManager().addTicket(ChunkTicketType.POST_TELEPORT, cp, 0, player.getId());
            if (session.world.getChunk(cp.x, cp.z, ChunkStatus.FULL, false) != null) {
                session.loadedChunks.add(cp);
            }
        }

        int actualProgress = (int) ((session.loadedChunks.size() / (float) session.chunksToLoad.size()) * 100);
        int displayProgress = Math.min(actualProgress, Math.min(95, session.phaseTicks * 3));
        ConfigNetworking.sendProgress(player, "randomrespawn.generating", displayProgress);

        if (session.loadedChunks.size() >= session.chunksToLoad.size() && session.phaseTicks >= MIN_LOADING_TICKS) {
            player.teleport(session.world, session.targetPos.getX() + 0.5, session.targetPos.getY(), session.targetPos.getZ() + 0.5,
                    player.getYaw(), player.getPitch());
            player.setVelocity(0, 0, 0);
            player.fallDistance = 0;
            session.phase = Phase.TELEPORTED;
            session.phaseTicks = 0;
            return false;
        }

        if (session.phaseTicks > LOADING_TIMEOUT_TICKS) {
            int retries = playerRetryCount.merge(uuid, 1, Integer::sum);
            removeInvulnerability(player);
            ConfigNetworking.sendProgress(player, "randomrespawn.searching", 0);
            player.getServer().execute(() -> startSession(player.getServerWorld(), player, retries));
            return true;
        }

        return false;
    }

    private boolean tickTeleported(RespawnSession session, ServerPlayerEntity player) {
        int finalProgress = Math.min(99, 95 + (session.phaseTicks / 8));
        ConfigNetworking.sendProgress(player, "randomrespawn.generating", finalProgress);

        if (session.phaseTicks >= POST_TELEPORT_TICKS) {
            finishSession(player);
            return true;
        }

        return false;
    }

    private void finishSession(ServerPlayerEntity player) {
        removeInvulnerability(player);
        playerRetryCount.remove(player.getUuid());
        ConfigNetworking.sendProgress(player, "randomrespawn.ready", 100);
    }

    private void startSession(ServerWorld world, ServerPlayerEntity player) {
        startSession(world, player, playerRetryCount.getOrDefault(player.getUuid(), 0));
    }

    private void startSession(ServerWorld world, ServerPlayerEntity player, int startIteration) {
        UUID uuid = player.getUuid();
        makePlayerInvulnerable(player);
        ConfigNetworking.sendProgress(player, "randomrespawn.searching", 0);
        RespawnSession session = new RespawnSession(world);
        sessions.put(uuid, session);
        requestSearch(session, uuid, startIteration);
    }

    private void requestSearch(RespawnSession session, UUID uuid, int iteration) {
        session.searchInFlight = true;
        findCandidate(session, uuid, iteration);
    }

    private void findCandidate(RespawnSession session, UUID uuid, int iteration) {
        ServerPlayerEntity player = session.world.getServer().getPlayerManager().getPlayer(uuid);

        if (iteration > MAX_SEARCH_ITERATIONS) {
            session.searchExhausted = true;
            session.searchInFlight = false;
            return;
        }

        if (player == null || player.isRemoved()) {
            session.searchInFlight = false;
            return;
        }

        int rangeExpansion = (iteration / 5) * 200;
        double rx = getRandomCoordinate(rangeExpansion);
        double rz = getRandomCoordinate(rangeExpansion);
        ChunkPos cp = new ChunkPos(((int) Math.floor(rx)) >> 4, ((int) Math.floor(rz)) >> 4);

        session.world.getChunkManager().threadedAnvilChunkStorage.getNbt(cp).thenAcceptAsync(nbtOpt -> {
            boolean isUnvisited = true;

            if (nbtOpt.isPresent()) {
                NbtCompound nbt = nbtOpt.get();
                if (nbt.contains("InhabitedTime", NbtElement.LONG_TYPE)) {
                    isUnvisited = nbt.getLong("InhabitedTime") == 0L;
                }
            }

            if (isUnvisited) {
                session.candidate = cp;
                session.searchIteration = iteration;
                session.searchInFlight = false;
            } else {
                findCandidate(session, uuid, iteration + 1);
            }
        }, session.world.getServer()).exceptionally(ex -> {
            session.world.getServer().execute(() -> findCandidate(session, uuid, iteration + 1));
            return null;
        });
    }

    private void makePlayerInvulnerable(ServerPlayerEntity player) {
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 999999, 4, false, false));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, 999999, 0, false, false));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.WATER_BREATHING, 999999, 0, false, false));
        player.setInvulnerable(true);
        if (ConfigManager.get().pendingRespawns.add(player.getUuidAsString()) && configPath != null) {
            ConfigManager.save(configPath);
        }
    }

    private void removeInvulnerability(ServerPlayerEntity player) {
        player.removeStatusEffect(StatusEffects.RESISTANCE);
        player.removeStatusEffect(StatusEffects.FIRE_RESISTANCE);
        player.removeStatusEffect(StatusEffects.WATER_BREATHING);
        player.setInvulnerable(false);
        if (ConfigManager.get().pendingRespawns.remove(player.getUuidAsString()) && configPath != null) {
            ConfigManager.save(configPath);
        }
    }

    private void resetPlayerProgress(ServerPlayerEntity player) {
        player.setExperienceLevel(0);
        player.setExperiencePoints(0);

        if (player.getServer() == null) return;

        var playerAdvancements = player.getAdvancementTracker();
        for (Map.Entry<Advancement, AdvancementProgress> entry : new ArrayList<>(playerAdvancements.progress.entrySet())) {
            Advancement advancement = entry.getKey();
            AdvancementProgress progress = entry.getValue();

            if (progress.isAnyObtained()) {
                for (String criterion : progress.getObtainedCriteria()) {
                    playerAdvancements.revokeCriterion(advancement, criterion);
                }
            }
        }
    }

    private BlockPos findSurfaceSpawn(ServerWorld world, ChunkPos cp) {
        int baseX = cp.getStartX();
        int baseZ = cp.getStartZ();

        int[][] checkOffsets = {
                {8, 8}, {4, 4}, {12, 4}, {4, 12}, {12, 12},
                {8, 4}, {4, 8}, {12, 8}, {8, 12},
                {6, 6}, {10, 10}, {6, 10}, {10, 6}
        };

        for (int[] offset : checkOffsets) {
            int x = baseX + offset[0];
            int z = baseZ + offset[1];

            int surfaceY = world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
            if (surfaceY <= world.getBottomY()) continue;

            BlockPos checkPos = new BlockPos(x, surfaceY, z);

            if (!world.isSkyVisible(checkPos)) continue;

            BlockPos groundPos = checkPos.down();
            BlockState ground = world.getBlockState(groundPos);
            BlockState feet = world.getBlockState(checkPos);
            BlockState head = world.getBlockState(checkPos.up());

            if (!ground.isSolidBlock(world, groundPos)) continue;
            if (isHazardous(ground)) continue;
            if (!feet.isAir() || !head.isAir()) continue;

            return checkPos;
        }

        return null;
    }

    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("randomrespawn")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.literal("set")
                                .then(CommandManager.argument("player", EntityArgumentType.player())
                                        .then(CommandManager.argument("enabled", BoolArgumentType.bool())
                                                .executes(this::setPlayerSetting))))
                        .then(CommandManager.literal("default")
                                .then(CommandManager.argument("enabled", BoolArgumentType.bool())
                                        .executes(this::setDefaultSetting)))
                        .then(CommandManager.literal("range")
                                .then(CommandManager.argument("distance", IntegerArgumentType.integer(100, 1000000))
                                        .executes(this::setRange)))
                        .then(CommandManager.literal("setDayTimeOnPlayerDeath")
                                .then(CommandManager.argument("enabled", BoolArgumentType.bool())
                                        .executes(this::setDayTimeOnPlayerDeath)))
                        .then(CommandManager.literal("info")
                                .executes(this::showInfo))
                ));
    }

    private int setPlayerSetting(CommandContext<ServerCommandSource> context) {
        try {
            ServerPlayerEntity targetPlayer = EntityArgumentType.getPlayer(context, "player");
            boolean enabled = BoolArgumentType.getBool(context, "enabled");

            ConfigManager.get().playerSettings.put(targetPlayer.getUuidAsString(), enabled);
            if (configPath != null) {
                ConfigManager.save(configPath);
            }

            context.getSource().sendFeedback(() -> Text.literal("Random respawn for " +
                    targetPlayer.getName().getString() + " is now " + (enabled ? "enabled" : "disabled")), true);
            return 1;
        } catch (Exception e) {
            context.getSource().sendError(Text.literal("Error: " + e.getMessage()));
            return 0;
        }
    }

    private int setDefaultSetting(CommandContext<ServerCommandSource> context) {
        ConfigManager.get().defaultEnabled = BoolArgumentType.getBool(context, "enabled");
        if (configPath != null) {
            ConfigManager.save(configPath);
        }

        context.getSource().sendFeedback(() -> Text.literal("Default random respawn is now " +
                (ConfigManager.get().defaultEnabled ? "enabled" : "disabled")), true);
        return 1;
    }

    private int setRange(CommandContext<ServerCommandSource> context) {
        ConfigManager.get().respawnRange = IntegerArgumentType.getInteger(context, "distance");
        if (configPath != null) {
            ConfigManager.save(configPath);
        }

        context.getSource().sendFeedback(() -> Text.literal("Range set to " +
                ConfigManager.get().respawnRange), true);
        return 1;
    }

    private int setDayTimeOnPlayerDeath(CommandContext<ServerCommandSource> context) {
        ConfigManager.get().setDayTimeOnPlayerDeath = BoolArgumentType.getBool(context, "enabled");
        if (configPath != null) {
            ConfigManager.save(configPath);
        }

        context.getSource().sendFeedback(() -> Text.literal("Set day time on player death is now " +
                (ConfigManager.get().setDayTimeOnPlayerDeath ? "enabled" : "disabled")), true);
        return 1;
    }

    private int showInfo(CommandContext<ServerCommandSource> context) {
        Config config = ConfigManager.get();
        context.getSource().sendFeedback(() -> Text.literal(
                "=== Random Respawn Settings ===\n" +
                        "Default: " + config.defaultEnabled + "\n" +
                        "Range: " + config.respawnRange + "\n" +
                        "Set day time on death: " + config.setDayTimeOnPlayerDeath + "\n" +
                        "Tracked Players: " + config.playerSettings.size()), false);
        return 1;
    }

    public double getRandomCoordinate(int rangeExpansion) {
        int range = ConfigManager.get().respawnRange + rangeExpansion;
        return (Math.random() * (range * 2)) - range;
    }

    private boolean isHazardous(BlockState state) {
        return state.getFluidState().isIn(FluidTags.LAVA)
                || state.isIn(BlockTags.FIRE)
                || HAZARDOUS_BLOCKS.contains(state.getBlock());
    }
}
