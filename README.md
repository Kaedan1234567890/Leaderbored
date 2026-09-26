
## 0.1.1-alpha — integrated single-player + dedicated server loading fix

This build changes the Fabric environment from dedicated-server-only (`"server"`) to universal (`"*"`). That is required for the mod initializer to load when Minecraft is running an integrated server inside a normal single-player/creative world. The `/leaderboard` command remains admin-only on dedicated servers, while local single-player worlds can use it for testing.

First local test: `/leaderboard set kills`

# Chill Zone Leaderboards 0.1.0-alpha

Server-side Fabric 26.2 leaderboard/hologram mod for Chill Zone SMP.

## First version

Three leaderboard types:

- `pvprank` — current Chill Zone Combat Top 10 ranks.
- `kills` — most PvP player kills.
- `killstreak` — highest all-time PvP kill streak.

Every board has:

- A shared Top 10.
- A viewer-specific line underneath showing **only that viewer's** placement/value.
- Automatic refresh after relevant PvP deaths/kills.
- A 30-second safety refresh by default.
- Persistent board locations, scale, player kills, current streak and best streak.

The PvP-rank integration is optional. If the Combat mod is not present, the PvP board says that Combat is not loaded. No client mod is required.

## Commands

All setup commands are OP-only.

```text
/leaderboard set <pvprank|kills|killstreak>
```
Places a board at your current position, 2.5 blocks above your feet.

```text
/leaderboard set <type> <x> <y> <z>
/leaderboard set <type> <x> <y> <z> <scale>
```
Places it at exact coordinates. Scale may be 0.25–4.0.

```text
/leaderboard move <type> <x> <y> <z>
/leaderboard scale <type> <scale>
/leaderboard personaloffset <type> <blocks>
/leaderboard refresh [type]
/leaderboard remove <type>
/leaderboard list
/leaderboard cleanup
```

### Creative-world layout testing

```text
/leaderboard test on
```
Shows 10 dummy players on all boards so you can test spacing/size while alone.

```text
/leaderboard test off
```
Returns to real data.

## Refresh behavior

- Player kill: Kills + Kill Streak boards refresh immediately.
- Any player death: Kill Streak refreshes immediately because the victim's current streak resets.
- PvP ranks: refreshed by the 30-second safety refresh or `/leaderboard refresh pvprank`.
- Player joins: all displays refresh so the new viewer receives their personalized lines.

The leaderboard **data does not reset every 30 seconds**. Only the floating display is refreshed.

## Existing kill history

On startup the mod imports the existing vanilla `minecraft:player_kills` values from the world's `stats` folder. This means the live server's Most Player Kills board does not have to begin from zero when the mod is first installed.

Highest Kill Streak begins being tracked by this mod because vanilla Minecraft does not store an all-time PvP streak statistic.

## Files

```text
config/chillzone-leaderboards.json
config/chillzone-leaderboard-stats.json
```

`chillzone-leaderboards.json` contains display locations/settings.
`chillzone-leaderboard-stats.json` contains persistent kill/streak data and remembered names.

## Personalized bottom line

The Top 10 text is a normal shared text-display entity. The final player line is one text-display entity per online player. The server hides every other player's personal line from each client, so two players standing at the same board can see different bottom lines without needing a client mod.

This is intentionally an alpha because Java/Geyser rendering/spacing should be tested in the creative world before installing it at live spawn.


## 0.1.2-alpha
- Fixes text displays rendering raw JSON instead of formatted leaderboard text.
- Replaces separate x/y/z arguments with vanilla `vec3` coordinate input for `/leaderboard set` and `/leaderboard move`, giving the same TAB/relative-coordinate helper style used by vanilla commands.
- Adds scale suggestions (0.50, 0.75, 1.00, 1.25, 1.50, 2.00).
- Keeps quick `/leaderboard set <type>` placement.


## 0.1.5-alpha
- PvP Rank personalized line no longer shows the player name.
- Ranked viewers see only their rank number (for example `#8`).
- Viewers outside the Top 10 see only `Unranked`.
- Kills and Kill Streak personalized lines are unchanged.


## 0.1.6-alpha — final creative-world spacing/PvP personal-line polish
- Adds a deliberate blank line between each leaderboard title and the #1 entry to match the approved reference spacing.
- Moves the viewer-specific line to the polished default position below #10 and migrates earlier default offsets automatically.
- PvP personal line now shows `#<rank> | PlayerName` when ranked.
- PvP personal line now shows `Unranked | PlayerName` when not ranked or when Combat has no rank for that player.
- The viewer's name remains yellow; the PvP rank/status marker uses the PvP board gold/orange color.
- Player joins already trigger an immediate full display refresh, so their personalized lines are created as soon as they connect.
