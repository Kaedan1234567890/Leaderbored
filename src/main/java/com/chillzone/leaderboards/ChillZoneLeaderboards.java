package com.chillzone.leaderboards;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

public final class ChillZoneLeaderboards implements ModInitializer {
    private static final LeaderboardConfig CONFIG = new LeaderboardConfig();
    private static final StatsStore STATS = new StatsStore();
    private static final DisplayManager DISPLAYS = new DisplayManager(CONFIG, STATS);
    private static int refreshTicks;
    private static int visibilityTicks;

    private static final SuggestionProvider<CommandSourceStack> BOARD_TYPES = (ctx, builder) -> {
        String typed = builder.getRemainingLowerCase();
        for (LeaderboardType type : LeaderboardType.values()) {
            if (type.id().startsWith(typed)) builder.suggest(type.id());
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> SCALES = (ctx, builder) -> {
        for (String value : new String[]{"0.50", "0.75", "1.00", "1.25", "1.50", "2.00"}) {
            if (value.startsWith(builder.getRemaining())) builder.suggest(value);
        }
        return builder.buildFuture();
    };

    @Override
    public void onInitialize() {
        CONFIG.load();
        STATS.load();

        registerCommands();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            STATS.bootstrap(server);
            DISPLAYS.cleanupAll(server);
            DISPLAYS.refreshAll(server);
            refreshTicks = 0;
            visibilityTicks = 0;
            System.out.println("[ChillZoneLeaderboards] Started. Refresh interval: "
                    + CONFIG.state().refreshSeconds + " seconds.");
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            STATS.save();
            CONFIG.save();
        });

        ServerPlayConnectionEvents.JOIN.register((listener, sender, server) -> {
            ServerPlayer player = listener.player;
            STATS.remember(player.getUUID(), player.getGameProfile().name());
            STATS.save();
            // Recreate personal lines so the joining player gets all three immediately.
            DISPLAYS.refreshAll(server);
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity instanceof ServerPlayer victim)) return;
            ServerPlayer killer = null;
            Entity attacker = source.getEntity();
            if (attacker instanceof ServerPlayer player && !player.getUUID().equals(victim.getUUID())) {
                killer = player;
            }
            STATS.recordDeath(victim, killer);
            MinecraftServer server = victim.level().getServer();
            if (server != null) {
                DISPLAYS.refreshType(server, LeaderboardType.KILL_STREAK);
                if (killer != null) DISPLAYS.refreshType(server, LeaderboardType.KILLS);
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // Re-hide foreign viewer-specific lines twice per second. This keeps
            // the personalized bottom line private even when players enter a
            // text-display entity's tracking range after the last refresh.
            if (++visibilityTicks >= 10) {
                visibilityTicks = 0;
                DISPLAYS.hideForeignPersonalLines(server);
            }

            int interval = Math.max(5, CONFIG.state().refreshSeconds) * 20;
            if (++refreshTicks >= interval) {
                refreshTicks = 0;
                STATS.bootstrap(server); // quietly reconcile names/vanilla kill history
                DISPLAYS.refreshAll(server);
            }
        });
    }

    private static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var root = Commands.literal("leaderboard")
                    .requires(Permissions::canAdmin);

            root.then(Commands.literal("set")
                    .then(Commands.argument("type", StringArgumentType.word()).suggests(BOARD_TYPES)
                            // No position = quick placement above your current position.
                            .executes(ctx -> setHere(ctx.getSource(),
                                    type(ctx.getSource(), StringArgumentType.getString(ctx, "type"))))
                            // Vanilla-style coordinate input. Press TAB just like /fill or /tp.
                            .then(Commands.argument("pos", Vec3Argument.vec3())
                                    .executes(ctx -> {
                                        Vec3 pos = Vec3Argument.getVec3(ctx, "pos");
                                        return setExact(ctx.getSource(),
                                                type(ctx.getSource(), StringArgumentType.getString(ctx, "type")),
                                                pos.x, pos.y, pos.z, null);
                                    })
                                    .then(Commands.argument("scale", DoubleArgumentType.doubleArg(0.25, 4.0))
                                            .suggests(SCALES)
                                            .executes(ctx -> {
                                                Vec3 pos = Vec3Argument.getVec3(ctx, "pos");
                                                return setExact(ctx.getSource(),
                                                        type(ctx.getSource(), StringArgumentType.getString(ctx, "type")),
                                                        pos.x, pos.y, pos.z,
                                                        DoubleArgumentType.getDouble(ctx, "scale"));
                                            })))));

            root.then(Commands.literal("move")
                    .then(Commands.argument("type", StringArgumentType.word()).suggests(BOARD_TYPES)
                            .then(Commands.argument("pos", Vec3Argument.vec3())
                                    .executes(ctx -> {
                                        Vec3 pos = Vec3Argument.getVec3(ctx, "pos");
                                        return move(ctx.getSource(),
                                                type(ctx.getSource(), StringArgumentType.getString(ctx, "type")),
                                                pos.x, pos.y, pos.z);
                                    }))));

            root.then(Commands.literal("scale")
                    .then(Commands.argument("type", StringArgumentType.word()).suggests(BOARD_TYPES)
                            .then(Commands.argument("scale", DoubleArgumentType.doubleArg(0.25, 4.0))
                                    .suggests(SCALES)
                                    .executes(ctx -> scale(
                                            ctx.getSource(),
                                            type(ctx.getSource(), StringArgumentType.getString(ctx, "type")),
                                            DoubleArgumentType.getDouble(ctx, "scale"))))));

            root.then(Commands.literal("personaloffset")
                    .then(Commands.argument("type", StringArgumentType.word()).suggests(BOARD_TYPES)
                            .then(Commands.argument("blocks", DoubleArgumentType.doubleArg(-10.0, 10.0))
                                    .executes(ctx -> personalOffset(
                                            ctx.getSource(),
                                            type(ctx.getSource(), StringArgumentType.getString(ctx, "type")),
                                            DoubleArgumentType.getDouble(ctx, "blocks"))))));

            root.then(Commands.literal("remove")
                    .then(Commands.argument("type", StringArgumentType.word()).suggests(BOARD_TYPES)
                            .executes(ctx -> remove(ctx.getSource(),
                                    type(ctx.getSource(), StringArgumentType.getString(ctx, "type"))))));

            root.then(Commands.literal("refresh")
                    .executes(ctx -> refreshAll(ctx.getSource()))
                    .then(Commands.argument("type", StringArgumentType.word()).suggests(BOARD_TYPES)
                            .executes(ctx -> refreshOne(ctx.getSource(),
                                    type(ctx.getSource(), StringArgumentType.getString(ctx, "type"))))));

            root.then(Commands.literal("list")
                    .executes(ctx -> list(ctx.getSource())));

            root.then(Commands.literal("test")
                    .then(Commands.literal("on")
                            .executes(ctx -> testMode(ctx.getSource(), true)))
                    .then(Commands.literal("off")
                            .executes(ctx -> testMode(ctx.getSource(), false))));

            root.then(Commands.literal("cleanup")
                    .executes(ctx -> cleanup(ctx.getSource())));

            dispatcher.register(root);
        });
    }

    private static LeaderboardType type(CommandSourceStack source, String raw) {
        try {
            return LeaderboardType.fromId(raw);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("Unknown leaderboard type. Use: pvprank, kills, or killstreak."));
            return null;
        }
    }

    private static int setHere(CommandSourceStack source, LeaderboardType type) {
        if (type == null) return 0;
        Vec3 pos = source.getPosition();
        double y = pos.y + 2.5;
        return setExact(source, type, pos.x, y, pos.z, null);
    }

    private static int setExact(CommandSourceStack source, LeaderboardType type,
                                double x, double y, double z, Double requestedScale) {
        if (type == null) return 0;
        LeaderboardConfig.BoardPlacement old = CONFIG.get(type);
        double scale = requestedScale != null ? requestedScale
                : old != null ? old.scale : CONFIG.state().defaultScale;
        LeaderboardConfig.BoardPlacement placement = new LeaderboardConfig.BoardPlacement(
                source.getLevel().dimension().identifier().toString(), x, y, z, scale);
        if (old != null) placement.personalOffset = old.personalOffset;
        CONFIG.put(type, placement);
        DISPLAYS.refreshType(source.getServer(), type);
        source.sendSuccess(() -> Component.literal("Placed " + type.id() + " leaderboard at "
                + fmt(x) + ", " + fmt(y) + ", " + fmt(z) + " (scale " + fmt(scale) + ")."), false);
        return 1;
    }

    private static int move(CommandSourceStack source, LeaderboardType type, double x, double y, double z) {
        if (type == null) return 0;
        LeaderboardConfig.BoardPlacement placement = CONFIG.get(type);
        if (placement == null) {
            source.sendFailure(Component.literal("That leaderboard has not been placed yet."));
            return 0;
        }
        placement.dimension = source.getLevel().dimension().identifier().toString();
        placement.x = x;
        placement.y = y;
        placement.z = z;
        CONFIG.put(type, placement);
        DISPLAYS.refreshType(source.getServer(), type);
        source.sendSuccess(() -> Component.literal("Moved " + type.id() + " leaderboard."), false);
        return 1;
    }

    private static int scale(CommandSourceStack source, LeaderboardType type, double scale) {
        if (type == null) return 0;
        LeaderboardConfig.BoardPlacement placement = CONFIG.get(type);
        if (placement == null) {
            source.sendFailure(Component.literal("That leaderboard has not been placed yet."));
            return 0;
        }
        placement.scale = scale;
        CONFIG.put(type, placement);
        DISPLAYS.refreshType(source.getServer(), type);
        source.sendSuccess(() -> Component.literal("Set " + type.id() + " scale to " + fmt(scale) + "."), false);
        return 1;
    }

    private static int personalOffset(CommandSourceStack source, LeaderboardType type, double offset) {
        if (type == null) return 0;
        LeaderboardConfig.BoardPlacement placement = CONFIG.get(type);
        if (placement == null) {
            source.sendFailure(Component.literal("That leaderboard has not been placed yet."));
            return 0;
        }
        placement.personalOffset = offset;
        CONFIG.put(type, placement);
        DISPLAYS.refreshType(source.getServer(), type);
        source.sendSuccess(() -> Component.literal("Set " + type.id() + " personal-line offset to " + fmt(offset) + "."), false);
        return 1;
    }

    private static int remove(CommandSourceStack source, LeaderboardType type) {
        if (type == null) return 0;
        CONFIG.remove(type);
        DISPLAYS.cleanupType(source.getServer(), type);
        source.sendSuccess(() -> Component.literal("Removed " + type.id() + " leaderboard."), false);
        return 1;
    }

    private static int refreshAll(CommandSourceStack source) {
        STATS.bootstrap(source.getServer());
        DISPLAYS.refreshAll(source.getServer());
        source.sendSuccess(() -> Component.literal("Refreshed all Chill Zone leaderboards."), false);
        return 1;
    }

    private static int refreshOne(CommandSourceStack source, LeaderboardType type) {
        if (type == null) return 0;
        STATS.bootstrap(source.getServer());
        DISPLAYS.refreshType(source.getServer(), type);
        source.sendSuccess(() -> Component.literal("Refreshed " + type.id() + " leaderboard."), false);
        return 1;
    }

    private static int list(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Chill Zone Leaderboards:"), false);
        for (LeaderboardType type : LeaderboardType.values()) {
            LeaderboardConfig.BoardPlacement p = CONFIG.get(type);
            String text = p == null
                    ? "- " + type.id() + ": not placed"
                    : "- " + type.id() + ": " + p.dimension + " @ "
                    + fmt(p.x) + ", " + fmt(p.y) + ", " + fmt(p.z)
                    + " | scale " + fmt(p.scale) + " | personal offset " + fmt(p.personalOffset);
            source.sendSuccess(() -> Component.literal(text), false);
        }
        source.sendSuccess(() -> Component.literal("Test mode: " + (CONFIG.state().testMode ? "ON" : "OFF")), false);
        return 1;
    }

    private static int testMode(CommandSourceStack source, boolean enabled) {
        CONFIG.setTestMode(enabled);
        DISPLAYS.refreshAll(source.getServer());
        source.sendSuccess(() -> Component.literal("Leaderboard test mode " + (enabled ? "enabled" : "disabled") + "."), false);
        return 1;
    }

    private static int cleanup(CommandSourceStack source) {
        DISPLAYS.cleanupAll(source.getServer());
        source.sendSuccess(() -> Component.literal("Removed all Chill Zone leaderboard display entities. Saved placements were kept."), false);
        return 1;
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
