# Chill Zone Leaderboards — GitHub-buildable Fabric source, 0.1 alpha

This is a REAL Fabric source project, unlike the earlier design-only ZIP. Minecraft 26.3 / Java 25. Its `/leaderboards` command opens a 27-slot category GUI and a 54-slot paginated player-head ranking GUI. Menus prevent taking the displayed items.

## GitHub build
1. Create a new GitHub repository, e.g. `chillzone-leaderboards`.
2. Upload the **contents of this ZIP**, not the ZIP itself, into the root of the repository. Include the hidden `.github/workflows/build.yml` folder.
3. Commit the files, then choose **Actions > Build Chill Zone Leaderboards > Run workflow** (the workflow also starts on push).
4. If the build succeeds, download the `chillzone-leaderboards-jar` artifact and extract the compiled `.jar` from it. Install alongside Fabric API on a *test* server only.
5. If the build fails, copy the full GitHub Actions error log for the next fix. Compilation has NOT been verified locally.

## Data and limitations
- Main categories: PvP kills, deaths, mined blocks, estimated placed blocks, Lifesteal hearts, kill streak placeholder, playtime in seconds, mobs killed, jumps.
- Vanilla statistics are read when players are online and snapshotted in `world/data/chillzone-leaderboards.json` at intervals and disconnect. Old offline players are **not** imported until they log in. Values preserve progress seen by this mod.
- Hearts: optional best-effort reading from the Lifesteal JSON save; can lag behind live changes.
- Block placements are approximated from block-item use statistics, not guaranteed actual block placements; this is deliberately labeled.
- Highest kill streak is NOT tracked in this alpha; it displays zero. Team rankings and precise placement history are future integrations that need the Teams/source event hooks.
- Player heads use vanilla player profiles. Historical player name and skin resolution may need improvement on a test server.
- This is uncompiled development source, not a verified deployable mod. Don't replace a working leaderboard until GitHub build and in-game tests pass.
