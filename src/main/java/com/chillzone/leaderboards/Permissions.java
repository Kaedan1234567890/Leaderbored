package com.chillzone.leaderboards;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

final class Permissions {
    private Permissions() {}

    static boolean canAdmin(CommandSourceStack source) {
        // Console / command blocks / non-player sources are allowed.
        if (!(source.getEntity() instanceof ServerPlayer player)) return true;

        // Allow the owner of a normal single-player / integrated-server world to
        // use the leaderboard admin commands while testing locally. This does
        // not weaken the dedicated-server rule below.
        if (source.getServer().isSingleplayer()) return true;

        // Dedicated/multiplayer servers remain OP-only.
        return source.getServer().getPlayerList().isOp(player.nameAndId());
    }
}
