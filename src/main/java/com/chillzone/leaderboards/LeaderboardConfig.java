package com.chillzone.leaderboards;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

final class LeaderboardConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path path = FabricLoader.getInstance().getConfigDir().resolve("chillzone-leaderboards.json");
    private State state = new State();

    static final class State {
        int refreshSeconds = 30;
        double defaultScale = 1.0;
        boolean testMode = false;
        Map<String, BoardPlacement> boards = new LinkedHashMap<>();
    }

    static final class BoardPlacement {
        String dimension;
        double x;
        double y;
        double z;
        double scale = 1.0;
        double personalOffset = -1.85;

        BoardPlacement() {}

        BoardPlacement(String dimension, double x, double y, double z, double scale) {
            this.dimension = dimension;
            this.x = x;
            this.y = y;
            this.z = z;
            this.scale = scale;
        }
    }

    void load() {
        try {
            Files.createDirectories(path.getParent());
            if (Files.exists(path)) {
                State loaded = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), State.class);
                if (loaded != null) state = loaded;
            }
            if (state.boards == null) state.boards = new LinkedHashMap<>();
            if (state.refreshSeconds < 5) state.refreshSeconds = 30;
            if (state.defaultScale <= 0) state.defaultScale = 1.0;

            // Migrate old alpha defaults to the polished board spacing.
            // Exact custom offsets chosen by admins are otherwise preserved.
            for (BoardPlacement placement : state.boards.values()) {
                if (placement == null) continue;
                if (Math.abs(placement.personalOffset - (-2.75)) < 0.000001
                        || Math.abs(placement.personalOffset - (-1.55)) < 0.000001) {
                    placement.personalOffset = -1.85;
                }
            }
            save();
        } catch (Exception e) {
            System.err.println("[ChillZoneLeaderboards] Failed to load config: " + e.getMessage());
            state = new State();
        }
    }

    State state() { return state; }

    BoardPlacement get(LeaderboardType type) {
        return state.boards.get(type.id());
    }

    void put(LeaderboardType type, BoardPlacement placement) {
        state.boards.put(type.id(), placement);
        save();
    }

    boolean remove(LeaderboardType type) {
        boolean changed = state.boards.remove(type.id()) != null;
        if (changed) save();
        return changed;
    }

    void setTestMode(boolean enabled) {
        state.testMode = enabled;
        save();
    }

    void save() {
        try {
            Files.createDirectories(path.getParent());
            String json = GSON.toJson(state);
            atomicWrite(path, json);
        } catch (Exception e) {
            System.err.println("[ChillZoneLeaderboards] Failed to save config: " + e.getMessage());
        }
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
