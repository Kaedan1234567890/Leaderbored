package com.chillzone.leaderboards;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reflection keeps Combat optional at runtime. The Chill Zone Combat fork keeps
 * the original com.combat.DataManager / PlayerData API, so this can read ranks
 * without creating a hard Fabric dependency between the two mods.
 */
final class CombatRankSource {
    record RankEntry(UUID uuid, String name, int rank) {}

    private CombatRankSource() {}

    static boolean available() {
        return FabricLoader.getInstance().isModLoaded("combat");
    }

    static List<RankEntry> top10() {
        List<RankEntry> result = new ArrayList<>();
        if (!available()) return result;
        try {
            for (Map.Entry<UUID, Object> entry : rawPlayers().entrySet()) {
                Object data = entry.getValue();
                int rank = intField(data, "rankPosition");
                if (rank < 1 || rank > 10) continue;
                String name = stringField(data, "playerName");
                if (name == null || name.isBlank()) name = entry.getKey().toString().substring(0, 8);
                result.add(new RankEntry(entry.getKey(), name, rank));
            }
            result.sort(Comparator.comparingInt(RankEntry::rank));
        } catch (Throwable t) {
            System.err.println("[ChillZoneLeaderboards] Could not read Combat ranks: " + t.getMessage());
        }
        return result;
    }

    static int rankOf(UUID uuid) {
        if (!available()) return 0;
        try {
            Object data = rawPlayers().get(uuid);
            if (data == null) return 0;
            int rank = intField(data, "rankPosition");
            return rank >= 1 && rank <= 10 ? rank : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    static Map<UUID, String> knownPlayers() {
        Map<UUID, String> result = new LinkedHashMap<>();
        if (!available()) return result;
        try {
            for (Map.Entry<UUID, Object> entry : rawPlayers().entrySet()) {
                String name = stringField(entry.getValue(), "playerName");
                if (name != null && !name.isBlank()) result.put(entry.getKey(), name);
            }
        } catch (Throwable ignored) {}
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> rawPlayers() throws Exception {
        Class<?> manager = Class.forName("com.combat.DataManager");
        Method method = manager.getMethod("getAllPlayersData");
        Object value = method.invoke(null);
        Map<UUID, Object> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof UUID uuid && entry.getValue() != null) {
                    result.put(uuid, entry.getValue());
                }
            }
        }
        return result;
    }

    private static int intField(Object object, String name) throws Exception {
        Field field = object.getClass().getField(name);
        return field.getInt(object);
    }

    private static String stringField(Object object, String name) throws Exception {
        Field field = object.getClass().getField(name);
        Object value = field.get(object);
        return value instanceof String s ? s : null;
    }
}
