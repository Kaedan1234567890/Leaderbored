package com.chillzone.leaderboards;

import java.util.Locale;

public enum LeaderboardType {
    PVP_RANK("pvprank", "PVP RANKS", "gold"),
    KILLS("kills", "MOST PLAYER KILLS", "red"),
    KILL_STREAK("killstreak", "HIGHEST KILL STREAK", "aqua");

    private final String id;
    private final String title;
    private final String color;

    LeaderboardType(String id, String title, String color) {
        this.id = id;
        this.title = title;
        this.color = color;
    }

    public String id() { return id; }
    public String title() { return title; }
    public String color() { return color; }

    public static LeaderboardType fromId(String raw) {
        String id = raw.toLowerCase(Locale.ROOT);
        for (LeaderboardType type : values()) {
            if (type.id.equals(id)) return type;
        }
        throw new IllegalArgumentException("Unknown leaderboard type: " + raw);
    }
}
