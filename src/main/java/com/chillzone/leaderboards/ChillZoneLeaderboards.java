package com.chillzone.leaderboards;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.IntConsumer;

/** Server-only leaderboard UI. Stats are snapshotted from vanilla's authoritative player stats. */
public final class ChillZoneLeaderboards implements ModInitializer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String[] LABELS = {"Player Kills", "Deaths", "Blocks Mined", "Blocks Placed (Estimate)", "Lifesteal Hearts", "Highest Kill Streak (Pending)", "Playtime", "Mobs Killed", "Jumps"};
    private static final Item[] ICONS = {Items.IRON_SWORD, Items.SKELETON_SKULL, Items.IRON_PICKAXE, Items.BRICKS, Items.NETHER_STAR, Items.BLAZE_POWDER, Items.CLOCK, Items.ZOMBIE_HEAD, Items.LEATHER_BOOTS};
    private static final int[] CATEGORY_SLOTS = {9,10,11,12,13,14,15,16,17};
    private static MinecraftServer server;
    private static Path file;
    private static State state = new State();
    private static int tick;
    private static class State { Map<String, Snapshot> players = new HashMap<>(); }
    private static class Snapshot {
        String name = "Unknown";
        long kills, deaths, mined, placedEstimate, hearts, playtime, mobs, jumps;
    }
    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            file = s.getWorldPath(LevelResource.ROOT).resolve("data/chillzone-leaderboards.json");
            try { if (Files.isRegularFile(file)) state = GSON.fromJson(Files.readString(file), State.class); }
            catch (Exception e) { System.err.println("[ChillZoneLeaderboards] Failed reading: " + e); }
            if (state == null || state.players == null) state = new State();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> { for (ServerPlayer p : s.getPlayerList().getPlayers()) snapshot(p); save(); });
        ServerPlayConnectionEvents.JOIN.register((h, sender, s) -> snapshot(h.player));
        ServerPlayConnectionEvents.DISCONNECT.register((h, s) -> { snapshot(h.player); save(); });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (++tick % 400 != 0) return;
            for (ServerPlayer p : s.getPlayerList().getPlayers()) snapshot(p);
            save();
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, reg, environment) ->
            dispatcher.register(Commands.literal("leaderboards").executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                snapshot(player);
                openCategories(player);
                return 1;
            })));
    }
    private static void save() {
        if (file == null) return;
        try { Files.createDirectories(file.getParent()); Files.writeString(file, GSON.toJson(state)); }
        catch (Exception e) { System.err.println("[ChillZoneLeaderboards] Failed saving: " + e); }
    }
    private static void snapshot(ServerPlayer p) {
        if (state == null || state.players == null) return;
        Snapshot row = state.players.computeIfAbsent(p.getUUID().toString(), id -> new Snapshot());
        row.name = p.getGameProfile().name();
        row.kills = p.getStats().getValue(Stats.CUSTOM.get(Stats.PLAYER_KILLS));
        row.deaths = p.getStats().getValue(Stats.CUSTOM.get(Stats.DEATHS));
        row.playtime = p.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME));
        row.mobs = p.getStats().getValue(Stats.CUSTOM.get(Stats.MOB_KILLS));
        row.jumps = p.getStats().getValue(Stats.CUSTOM.get(Stats.JUMP));
        long mined = 0, placed = 0;
        for (var block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            mined += p.getStats().getValue(Stats.BLOCK_MINED.get(block));
            Item item = block.asItem();
            if (item != Items.AIR) placed += p.getStats().getValue(Stats.ITEM_USED.get(item));
        }
        row.mined = mined;
        row.placedEstimate = placed; // Item use is NOT authoritative block placements.
        row.hearts = readHearts(p.getUUID().toString(), row.hearts);
    }
    private static long readHearts(String uuid, long fallback) {
        // Read Lifesteal's saved data without linking its Java classes (independent mod).
        try {
            Path ls = server.getWorldPath(LevelResource.ROOT).resolve("data/chillzone-lifesteal.json");
            if (!Files.isRegularFile(ls)) return fallback;
            var root = com.google.gson.JsonParser.parseString(Files.readString(ls)).getAsJsonObject();
            if (!root.has("hearts") || !root.getAsJsonObject("hearts").has(uuid)) return fallback;
            return root.getAsJsonObject("hearts").get(uuid).getAsLong();
        } catch (Exception e) { return fallback; }
    }
    private static ItemStack named(Item item, String name) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name).withStyle(ChatFormatting.GOLD));
        return stack;
    }
    private static void openCategories(ServerPlayer p) {
        SimpleContainer chest = new SimpleContainer(27);
        for (int i = 0; i < ICONS.length; i++) chest.setItem(CATEGORY_SLOTS[i], named(ICONS[i], LABELS[i]));
        p.openMenu(provider("Chill Zone Leaderboards", 3, chest, slot -> {
            for (int i = 0; i < CATEGORY_SLOTS.length; i++) if (slot == CATEGORY_SLOTS[i]) { openRanking(p,i,0); return; }
        }));
    }
    private static long value(Snapshot row, int category) {
        return switch(category) {
            case 0 -> row.kills; case 1 -> row.deaths; case 2 -> row.mined;
            case 3 -> row.placedEstimate; case 4 -> row.hearts; case 5 -> 0;
            case 6 -> row.playtime / 20; case 7 -> row.mobs; default -> row.jumps;
        };
    }
    private static void openRanking(ServerPlayer player, int category, int requestedPage) {
        snapshot(player);
        List<Map.Entry<String, Snapshot>> records = new ArrayList<>(state.players.entrySet());
        records.sort(Comparator.<Map.Entry<String, Snapshot>>comparingLong(e -> value(e.getValue(), category))
            .reversed().thenComparing(e -> e.getValue().name.toLowerCase(Locale.ROOT)));
        int pages = Math.max(1,(records.size()+44)/45);
        int page = Math.max(0, Math.min(requestedPage,pages-1));
        SimpleContainer chest = new SimpleContainer(54);
        for (int i = page*45; i < Math.min(records.size(),(page+1)*45); i++) {
            var entry = records.get(i);
            Snapshot row = entry.getValue();
            ItemStack head = named(Items.PLAYER_HEAD,"#"+(i+1)+" "+row.name+" | "+value(row,category));
            try {
                UUID uuid = UUID.fromString(entry.getKey());
                head.set(DataComponents.PROFILE, new ResolvableProfile(new GameProfile(uuid,row.name)));
            } catch (Exception ignored) {}
            chest.setItem(i-page*45,head);
        }
        if (page > 0) chest.setItem(45,named(Items.ARROW,"Previous Page"));
        chest.setItem(49,named(Items.BOOK,"Page "+(page+1)+" / "+pages));
        chest.setItem(48,named(Items.BARRIER,"Back to Categories"));
        if (page+1 < pages) chest.setItem(53,named(Items.ARROW,"Next Page"));
        pOpen(player, category, page, chest);
    }
    private static void pOpen(ServerPlayer p, int category, int page, SimpleContainer chest) {
        p.openMenu(provider(LABELS[category],6,chest,slot -> {
            if (slot == 45) openRanking(p,category,page-1);
            if (slot == 48) openCategories(p);
            if (slot == 53) openRanking(p,category,page+1);
        }));
    }
    private static MenuProvider provider(String title,int rows,SimpleContainer container,IntConsumer click) {
        return new MenuProvider() {
            @Override public Component getDisplayName() {return Component.literal(title);}
            @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player player) {
                return new LockedMenu(rows == 3 ? MenuType.GENERIC_9x3 : MenuType.GENERIC_9x6,id,inv,container,rows,click);
            }
        };
    }
    private static final class LockedMenu extends ChestMenu {
        private final IntConsumer click;
        private final int guiSize;
        LockedMenu(MenuType<?> type,int id,Inventory inv,Container chest,int rows,IntConsumer click) {
            super(type,id,inv,chest,rows); this.click=click; this.guiSize=rows*9;
        }
        @Override public void clicked(int slot,int button,ContainerInput input,Player player) {
            if (slot >= 0 && slot < guiSize) {
                if (input == ContainerInput.PICKUP && button == 0) click.accept(slot);
                return;
            }
            super.clicked(slot,button,input,player);
        }
        @Override public ItemStack quickMoveStack(Player player,int slot) {return ItemStack.EMPTY;}
    }
}
