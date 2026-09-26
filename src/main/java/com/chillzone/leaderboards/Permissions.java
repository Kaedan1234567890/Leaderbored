package com.chillzone.leaderboards;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

final class Permissions {
    private Permissions() {}

    static boolean canAdmin(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return true;
        return source.getServer().getPlayerList().isOp(player.nameAndId());
    }
}
