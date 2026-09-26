package com.chillzone.leaderboards;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class DisplayManager {
    private static final Gson GSON = new Gson();
    private static final String ROOT_TAG = "czlb";
    private static final String OWNER_PREFIX = "czlb_owner_";

    private final LeaderboardConfig config;
    private final StatsStore stats;

    DisplayManager(LeaderboardConfig config, StatsStore stats) {
        this.config = config;
        this.stats = stats;
    }

    void cleanupAll(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            run(level, 0, 0, 0, "kill @e[type=minecraft:text_display,tag=" + ROOT_TAG + "]");
        }
    }

    void cleanupType(MinecraftServer server, LeaderboardType type) {
        for (ServerLevel level : server.getAllLevels()) {
            run(level, 0, 0, 0, "kill @e[type=minecraft:text_display,tag=" + typeTag(type) + "]");
        }
    }

    void refreshAll(MinecraftServer server) {
        for (LeaderboardType type : LeaderboardType.values()) refreshType(server, type);
    }

    void refreshType(MinecraftServer server, LeaderboardType type) {
        LeaderboardConfig.BoardPlacement placement = config.get(type);
        if (placement == null) {
            cleanupType(server, type);
            return;
        }

        ServerLevel level = findLevel(server, placement.dimension);
        if (level == null) return;

        cleanupType(server, type);

        String shared = sharedText(server, type);
        summonText(level, placement.x, placement.y, placement.z, placement.scale,
                List.of(ROOT_TAG, typeTag(type), "czlb_shared_" + type.id()), shared);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String owner = compactUuid(player.getUUID());
            String personal = personalText(type, player);
            double py = placement.y + (placement.personalOffset * placement.scale);
            summonText(level, placement.x, py, placement.z, placement.scale,
                    List.of(ROOT_TAG, typeTag(type), "czlb_personal", OWNER_PREFIX + owner,
                            "czlb_personal_" + type.id() + "_" + owner), personal);
        }

        hideForeignPersonalLines(server);
    }

    /**
     * Personal lines are real text-display entities so they survive normal
     * tracking/range changes. Every viewer is repeatedly told to remove all
     * personal lines except their own, producing a per-viewer line under each
     * otherwise shared Top 10 board without requiring a client mod.
     */
    void hideForeignPersonalLines(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            List<PersonalEntity> personal = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                Set<String> tags = commandTags(entity);
                if (!tags.contains("czlb_personal")) continue;
                UUID owner = ownerFromTags(tags);
                if (owner != null) personal.add(new PersonalEntity(entity.getId(), owner));
            }
            if (personal.isEmpty()) continue;

            for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
                if (!viewer.level().dimension().equals(level.dimension())) continue;
                int[] ids = personal.stream()
                        .filter(line -> !line.owner.equals(viewer.getUUID()))
                        .mapToInt(line -> line.entityId)
                        .toArray();
                if (ids.length > 0) {
                    viewer.connection.send(new ClientboundRemoveEntitiesPacket(ids));
                }
            }
        }
    }

    private String sharedText(MinecraftServer server, LeaderboardType type) {
        if (config.state().testMode) return testSharedText(type);

        JsonArray parts = new JsonArray();
        addPart(parts, type.title() + "\n", type.color(), true);

        switch (type) {
            case PVP_RANK -> {
                if (!CombatRankSource.available()) {
                    addPart(parts, "Combat mod not loaded", "gray", false);
                } else {
                    List<CombatRankSource.RankEntry> entries = CombatRankSource.top10();
                    if (entries.isEmpty()) {
                        addPart(parts, "No ranked players yet", "gray", false);
                    } else {
                        for (CombatRankSource.RankEntry entry : entries) {
                            // Category color for the rank marker; player name stays white.
                            addPart(parts, "#" + entry.rank() + " | ", type.color(), false);
                            addPart(parts, entry.name() + "\n", "white", false);
                        }
                    }
                }
            }
            case KILLS -> appendScores(parts, stats.topKills(), type);
            case KILL_STREAK -> appendScores(parts, stats.topBestStreak(), type);
        }

        return component(parts);
    }

    private void appendScores(JsonArray parts, List<StatsStore.ScoreEntry> scores, LeaderboardType type) {
        int shown = Math.min(10, scores.size());
        if (shown == 0) {
            addPart(parts, "No data yet", "gray", false);
            return;
        }
        for (int i = 0; i < shown; i++) {
            StatsStore.ScoreEntry entry = scores.get(i);
            // Rank number and stat value use the board color; player name stays white.
            addPart(parts, "#" + (i + 1) + " | ", type.color(), false);
            addPart(parts, entry.name(), "white", false);
            addPart(parts, ": " + entry.value() + (i + 1 < shown ? "\n" : ""), type.color(), false);
        }
    }

    private String personalText(LeaderboardType type, ServerPlayer player) {
        String name = player.getGameProfile().name();
        if (config.state().testMode) {
            return switch (type) {
                case PVP_RANK -> personalRankComponent(type, 8);
                case KILLS -> personalScoreComponent(type, 37, name, 42);
                case KILL_STREAK -> personalScoreComponent(type, 12, name, 6);
            };
        }

        return switch (type) {
            case PVP_RANK -> {
                int rank = CombatRankSource.rankOf(player.getUUID());
                yield rank > 0
                        ? personalRankComponent(type, rank)
                        : unrankedPersonalComponent();
            }
            case KILLS -> {
                int place = stats.placement(player.getUUID(), false);
                int value = stats.value(player.getUUID(), false);
                yield personalScoreComponent(type, place, name, value);
            }
            case KILL_STREAK -> {
                int place = stats.placement(player.getUUID(), true);
                int value = stats.value(player.getUUID(), true);
                yield personalScoreComponent(type, place, name, value);
            }
        };
    }

    private String personalRankComponent(LeaderboardType type, int place) {
        JsonArray parts = new JsonArray();
        addPart(parts, "#" + place, type.color(), true);
        return component(parts);
    }

    private String personalScoreComponent(LeaderboardType type, int place, String name, int value) {
        JsonArray parts = new JsonArray();
        addPart(parts, "#" + place + " | ", type.color(), true);
        addPart(parts, name, "yellow", true);
        addPart(parts, ": " + value, type.color(), true);
        return component(parts);
    }

    private String unrankedPersonalComponent() {
        JsonArray parts = new JsonArray();
        addPart(parts, "Unranked", "gray", true);
        return component(parts);
    }

    private String testSharedText(LeaderboardType type) {
        JsonArray parts = new JsonArray();
        addPart(parts, type.title() + "\n", type.color(), true);
        for (int i = 1; i <= 10; i++) {
            addPart(parts, "#" + i + " | ", type.color(), false);
            addPart(parts, "TestPlayer" + i, "white", false);
            if (type == LeaderboardType.KILLS) {
                addPart(parts, ": " + (110 - i * 7), type.color(), false);
            } else if (type == LeaderboardType.KILL_STREAK) {
                addPart(parts, ": " + (31 - i * 2), type.color(), false);
            }
            if (i < 10) addPart(parts, "\n", "white", false);
        }
        return component(parts);
    }

    private void summonText(ServerLevel level, double x, double y, double z, double scale,
                            List<String> tags, String textJson) {
        StringBuilder tagList = new StringBuilder("[");
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) tagList.append(',');
            tagList.append('"').append(tags.get(i)).append('"');
        }
        tagList.append(']');

        // In 26.2 the text field accepts a structured text component directly.
        // Quoting the JSON turns it into a literal string, which is why older
        // builds visibly printed {\"text\":...,\"extra\":[...]} in-world.
        String command = "summon minecraft:text_display ~ ~ ~ {Tags:" + tagList
                + ",billboard:\"center\",alignment:\"center\",background:0,shadow:1b,see_through:0b"
                + ",line_width:400,view_range:2.0f,text:" + textJson
                + ",transformation:{scale:[" + f(scale) + "f," + f(scale) + "f," + f(scale) + "f]}}";
        run(level, x, y, z, command);
    }

    private void run(ServerLevel level, double x, double y, double z, String command) {
        MinecraftServer server = level.getServer();
        if (server == null) return;
        try {
            CommandSourceStack source = server.createCommandSourceStack()
                    .withSuppressedOutput()
                    .withLevel(level)
                    .withPosition(new Vec3(x, y, z));
            server.getCommands().performPrefixedCommand(source, command);
        } catch (Exception e) {
            System.err.println("[ChillZoneLeaderboards] Command failed: " + command + " -> " + e.getMessage());
        }
    }

    private static ServerLevel findLevel(MinecraftServer server, String dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimension().identifier().toString().equals(dimension)) return level;
        }
        return null;
    }

    private static String typeTag(LeaderboardType type) {
        return "czlb_" + type.id();
    }

    private static String compactUuid(UUID uuid) {
        return uuid.toString().replace("-", "");
    }


    /**
     * Minecraft 26.2 changed/removed the mapped public accessor for entity command tags.
     * Resolve the backing tag set reflectively so this remains compatible with the
     * runtime mapping without depending on getTags()/getCommandTags().
     */
    private static Set<String> commandTags(Entity entity) {
        Class<?> type = entity.getClass();
        while (type != null) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (!Set.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(entity);
                    if (!(value instanceof Set<?> set)) continue;

                    boolean stringsOnly = true;
                    Set<String> result = new HashSet<>();
                    for (Object entry : set) {
                        if (!(entry instanceof String str)) {
                            stringsOnly = false;
                            break;
                        }
                        result.add(str);
                    }
                    if (!stringsOnly) continue;

                    if (result.contains(ROOT_TAG)
                            || result.contains("czlb_personal")
                            || result.stream().anyMatch(tag -> tag.startsWith(OWNER_PREFIX))) {
                        return result;
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Try the next Set field.
                }
            }
            type = type.getSuperclass();
        }
        return Set.of();
    }

    private static UUID ownerFromTags(Set<String> tags) {
        for (String tag : tags) {
            if (!tag.startsWith(OWNER_PREFIX)) continue;
            String compact = tag.substring(OWNER_PREFIX.length());
            if (compact.length() != 32) return null;
            String normal = compact.substring(0, 8) + "-" + compact.substring(8, 12) + "-"
                    + compact.substring(12, 16) + "-" + compact.substring(16, 20) + "-"
                    + compact.substring(20);
            try { return UUID.fromString(normal); }
            catch (IllegalArgumentException ignored) { return null; }
        }
        return null;
    }

    private static void addPart(JsonArray array, String text, String color, boolean bold) {
        JsonObject object = new JsonObject();
        object.addProperty("text", text);
        object.addProperty("color", color);
        if (bold) object.addProperty("bold", true);
        array.add(object);
    }

    private static String component(JsonArray extra) {
        JsonObject root = new JsonObject();
        root.addProperty("text", "");
        root.add("extra", extra);
        return GSON.toJson(root);
    }

    private static String simpleComponent(String text, String color, boolean bold) {
        JsonObject root = new JsonObject();
        root.addProperty("text", text);
        root.addProperty("color", color);
        if (bold) root.addProperty("bold", true);
        return GSON.toJson(root);
    }

    private static String f(double value) {
        if (!Double.isFinite(value)) return "1.0";
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private record PersonalEntity(int entityId, UUID owner) {}
}
