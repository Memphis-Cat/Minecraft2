# Legacy Culling 1.8.9 test checklist

Use the same world position, camera direction, video settings, and server lobby for before/after comparisons. Let chunks finish loading before recording FPS or frame time.

## Startup and settings

1. Install Legacy Fabric Loader, Legacy Fabric API, and the runtime Legacy Culling jar.
2. Confirm the main menu opens without a mixin error.
3. Open Options → Video Settings → Legacy Culling…
4. Join a world and confirm the F3 panel shows `[Legacy Culling 0.1.0] ACTIVE`.
5. Restart and confirm changed settings persist.

## Competitive/Hypixel safety

- Test players through full blocks and around wall edges.
- Test NPC and hologram armor stands in several Hypixel lobbies or game modes.
- Confirm player, entity, and armor-stand names remain available with their safety options enabled.
- Test Ender Dragons and Withers when available.
- Set Entity Culling Interval to 0, 5, 10, and 50 ms and check for delayed reappearance.
- Test grounded arrows, attached arrows, item frames, maps, falling blocks, and invisible or semitransparent players before enabling their explicit disable switches permanently.

## Occlusion and HZB

- Put multiple entities behind a solid wall and watch the entity counter increase.
- Move sideways past a wall edge and confirm entities appear before becoming directly visible.
- Turn the camera rapidly, teleport, change FOV, enter third person, and resize the window.
- Test fences, glass, slabs, stairs, open doors, pistons, water, and leaves as partial occluders.
- Confirm nearby entities are never hidden.
- Compare HZB on and off using the same scene and watch `HZB hits/captures` in F3.

## OptiFine

- Launch without OptiFine.
- Launch with the intended OptiFine 1.8.9 version.
- Enable and disable a shader pack.
- Confirm F3 reports shader detection.
- With Smart Entity Culling enabled, confirm shader mode does not use entity ray/HZB occlusion.
- Check entity shadows, nametags, translucent players, beacon beams, end portals, clouds, and weather.

## Block entities and decorative objects

- Chests, trapped chests, ender chests, signs, skulls, banners, beacons, enchanting tables, and end portals.
- Paintings and item frames viewed from the front, edge, and rear.
- Mapped item frames with the separate map-only switch.
- Beacon beams from below, beside, and far away.
- Sign text at sharp viewing angles and from the rear.

## Particles, weather, leaves, and animation

- Explosions, potion particles, critical-hit particles, firework particles, portal particles, and dense lobby particles.
- Rain and snow at short and long fog distances.
- Fancy leaves and fast leaves with connected leaf canopies.
- Water, lava, fire, portal, clock, and compass texture animation.
- Confirm particle and leaf counters rise when applicable.

## Font and GUI

- Chat, scoreboard, tab list, action bar, nametags, signs, debug screen, Unicode text, formatting codes, shadows, and scaled GUI.
- Change language and reload resource packs to verify font display-list caches clear correctly.
- Test very frequently changing scoreboard text and chat scrolling.

## World and chunk work

- Join and leave servers repeatedly.
- Switch single-player worlds repeatedly.
- Fly or sprint through newly loaded chunks.
- Break and place many blocks while varying Chunk Update Limit.
- A very low chunk-update limit can intentionally delay mesh rebuilding; confirm chunks eventually update.

## Report useful failures

Include:

- `logs/latest.log`
- Crash report, when present
- Legacy Fabric Loader/API versions
- OptiFine version and shader name
- GPU and driver
- The relevant section of `config/legacy-culling.properties`
- Exact steps that reproduce the missing object, flicker, or crash
