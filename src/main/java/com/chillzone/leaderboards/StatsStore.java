package com.chillzone.leaderboards;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class StatsStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path path = FabricLoader.getInstance().getConfigDir().resolve("chillzone-leaderboard-stats.json");
    private State state = new State();
    // Test-only counters are intentionally memory-only. Mannequin kills can
    // exercise the UI without ever contaminating real server statistics.
    private final Map<UUID, Integer> testKills = new LinkedHashMap<>();
    private final Map<UUID, Integer> testCurrentStreak = new LinkedHashMap<>();
    private final Map<UUID, Integer> testBestStreak = new LinkedHashMap<>();

    static final class State {
        Map<String, PlayerStats> players = new LinkedHashMap<>();
    }

    static final class PlayerStats {
        String name = "Unknown";
        int kills;
        int currentStreak;
        int bestStreak;
    }

    record ScoreEntry(UUID uuid, String name, int value) {}

    void load() {
        try {
            Files.createDirectories(path.getParent());
            if (Files.exists(path)) {
                State loaded = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), State.class);
                if (loaded != null) state = loaded;
            }
            if (state.players == null) state.players = new LinkedHashMap<>();
        } catch (Exception e) {
            System.err.println("[ChillZoneLeaderboards] Failed to load stats: " + e.getMessage());
            state = new State();
        }
    }

    void bootstrap(MinecraftServer server) {
        boolean changed = false;

        // Combat already remembers the server community; seed our name list from it.
        for (Map.Entry<UUID, String> entry : CombatRankSource.knownPlayers().entrySet()) {
            changed |= remember(entry.getKey(), entry.getValue());
        }

        // usercache.json catches players that Combat may not currently remember.
        changed |= importUserCache();

        // Import existing vanilla player-kill statistics so the live server does
        // not have to start the Most Kills leaderboard from zero.
        changed |= importVanillaKills(server);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            changed |= remember(player.getUUID(), player.getGameProfile().name());
        }

        if (changed) save();
    }

    boolean remember(UUID uuid, String name) {
        if (name == null || name.isBlank()) return false;
        PlayerStats stats = state.players.computeIfAbsent(uuid.toString(), key -> new PlayerStats());
        if (!name.equals(stats.name)) {
            stats.name = name;
            return true;
        }
        return false;
    }

    void recordDeath(ServerPlayer victim, ServerPlayer killer) {
        boolean changed = remember(victim.getUUID(), victim.getGameProfile().name());
        PlayerStats victimStats = get(victim.getUUID());
        if (victimStats.currentStreak != 0) {
            victimStats.currentStreak = 0;
            changed = true;
        }

        if (killer != null && !killer.getUUID().equals(victim.getUUID())) {
            changed |= remember(killer.getUUID(), killer.getGameProfile().name());
            PlayerStats killerStats = get(killer.getUUID());
            killerStats.kills++;
            killerStats.currentStreak++;
            if (killerStats.currentStreak > killerStats.bestStreak) {
                killerStats.bestStreak = killerStats.currentStreak;
            }
            changed = true;
        }

        if (changed) save();
    }


    void recordTestMannequinKill(ServerPlayer killer) {
        UUID uuid = killer.getUUID();
        testKills.merge(uuid, 1, Integer::sum);
        int current = testCurrentStreak.merge(uuid, 1, Integer::sum);
        testBestStreak.merge(uuid, current, Math::max);
    }

    void resetTestStreak(ServerPlayer player) {
        testCurrentStreak.put(player.getUUID(), 0);
    }

    int testKills(UUID uuid) { return testKills.getOrDefault(uuid, 0); }
    int testBestStreak(UUID uuid) { return testBestStreak.getOrDefault(uuid, 0); }

    void resetTestStats() {
        testKills.clear();
        testCurrentStreak.clear();
        testBestStreak.clear();
    }

    PlayerStats get(UUID uuid) {
        return state.players.computeIfAbsent(uuid.toString(), key -> new PlayerStats());
    }

    List<ScoreEntry> topKills() { return sorted(false); }
    List<ScoreEntry> topBestStreak() { return sorted(true); }

    int placement(UUID uuid, boolean streak) {
        List<ScoreEntry> list = sorted(streak);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).uuid().equals(uuid)) return i + 1;
        }
        return list.size() + 1;
    }

    int value(UUID uuid, boolean streak) {
        PlayerStats stats = state.players.get(uuid.toString());
        if (stats == null) return 0;
        return streak ? stats.bestStreak : stats.kills;
    }

    String name(UUID uuid) {
        PlayerStats stats = state.players.get(uuid.toString());
        return stats == null || stats.name == null ? uuid.toString().substring(0, 8) : stats.name;
    }

    void save() {
        try {
            Files.createDirectories(path.getParent());
            atomicWrite(path, GSON.toJson(state));
        } catch (Exception e) {
            System.err.println("[ChillZoneLeaderboards] Failed to save stats: " + e.getMessage());
        }
    }

    private List<ScoreEntry> sorted(boolean streak) {
        List<ScoreEntry> list = new ArrayList<>();
        for (Map.Entry<String, PlayerStats> entry : state.players.entrySet()) {
            try {
                UUID uuid = UUID.fromString(entry.getKey());
                PlayerStats stats = entry.getValue();
                String name = stats.name == null ? uuid.toString().substring(0, 8) : stats.name;
                int value = streak ? stats.bestStreak : stats.kills;
                list.add(new ScoreEntry(uuid, name, value));
            } catch (IllegalArgumentException ignored) {}
        }
        list.sort(Comparator.comparingInt(ScoreEntry::value).reversed()
                .thenComparing(ScoreEntry::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(e -> e.uuid().toString()));
        return list;
    }

    private boolean importUserCache() {
        Path userCache = FabricLoader.getInstance().getGameDir().resolve("usercache.json");
        if (!Files.exists(userCache)) return false;
        boolean changed = false;
        try {
            JsonElement root = JsonParser.parseString(Files.readString(userCache, StandardCharsets.UTF_8));
            if (root.isJsonArray()) {
                for (JsonElement element : root.getAsJsonArray()) {
                    if (!element.isJsonObject()) continue;
                    JsonObject object = element.getAsJsonObject();
                    if (!object.has("uuid") || !object.has("name")) continue;
                    try {
                        UUID uuid = UUID.fromString(object.get("uuid").getAsString());
                        changed |= remember(uuid, object.get("name").getAsString());
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            System.err.println("[ChillZoneLeaderboards] Could not import usercache.json: " + e.getMessage());
        }
        return changed;
    }

    private boolean importVanillaKills(MinecraftServer server) {
        Path statsDir = server.getWorldPath(LevelResource.PLAYER_STATS_DIR);
        if (!Files.isDirectory(statsDir)) return false;
        boolean changed = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(statsDir, "*.json")) {
            for (Path file : stream) {
                String filename = file.getFileName().toString();
                if (!filename.endsWith(".json")) continue;
                String uuidText = filename.substring(0, filename.length() - 5);
                UUID uuid;
                try { uuid = UUID.fromString(uuidText); }
                catch (IllegalArgumentException ignored) { continue; }

                try {
                    JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                    JsonObject stats = object(root, "stats");
                    JsonObject custom = stats == null ? null : object(stats, "minecraft:custom");
                    if (custom == null || !custom.has("minecraft:player_kills")) continue;
                    int vanillaKills = custom.get("minecraft:player_kills").getAsInt();
                    PlayerStats player = get(uuid);
                    if (vanillaKills > player.kills) {
                        player.kills = vanillaKills;
                        changed = true;
                    }
                } catch (Exception ignored) {}
            }
        } catch (IOException e) {
            System.err.println("[ChillZoneLeaderboards] Could not import vanilla kill stats: " + e.getMessage());
        }
        return changed;
    }

    private static JsonObject object(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static void atomicWrite(Path destination, String content) throws IOException {
        Path tmp = destination.resolveSibling(destination.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
