# Legacy Culling for Minecraft 1.8.9

A clean-room, client-only rendering optimization mod for **Legacy Fabric** on Minecraft **1.8.9**. It does not contain Sodium, Iris, OptiFine, or code copied from another culling mod.

## Requirements

- Minecraft 1.8.9
- Legacy Fabric Loader 0.18.3 or newer
- Legacy Fabric API 1.13.2+1.8.9 or newer
- Java 8 or newer to run Minecraft

## Build on Windows

Double-click `build.bat`. The script downloads private copies of Java 21 and Gradle 9.5.1 when required, compiles Java 8-compatible bytecode, and opens `build\libs`.

Install only `legacy-culling-0.1.0.jar`. Files containing `developer-sources` are not mods.

## Implemented visibility systems

- Cached entity occlusion using a center and eight conservative block rays.
- Configurable entity occlusion interval in milliseconds.
- Previous-frame asynchronous Hierarchical-Z depth pyramid with camera-motion rejection.
- HZB is only a confirmation layer; uncertain objects fall through to full CPU ray sampling.
- OptiFine shader detection and Smart Entity Culling safety behavior.
- Boss, player-name, entity-name, and armor-stand safety rules.
- Entity, player, hostile, passive, global, and block-entity distance controls.
- Entity and player back-face controls.
- Painting and item-frame rear-face rejection.
- Block-entity distance and occlusion checks with off-screen-renderer and beacon exclusions.
- Particle distance, fog, cell-density, global-count, CPU ray, and HZB checks.
- Fog-aware entity, block-entity, particle, and weather rejection using actual OpenGL fog distance.
- Internal leaf-face removal.
- Hidden-side sign-text removal without deleting the sign model.
- F3 statistics showing real culling and work counters.

## Optional renderer controls

The options screen contains explicit switches for armor stands, semitransparent players, enchanting-table books, item frames, mapped item frames, grounded arrows, attached arrows, skulls, falling blocks, end portals, enchantment glint, nametag boxes, and duplicate dropped-item models.

These controls are disabled by default unless they are conservative optimizations. They intentionally remove the selected visual category and are not described as occlusion culling.

## Other optimizations

- Static particle-light caching.
- Global and per-cell particle admission limits.
- Bounded font width, trimming, and wrapping caches.
- Rendered-string OpenGL display-list cache.
- Forced-GC and loading-screen redraw removal during world changes.
- Configurable chunk rebuild-submission limit.
- Configurable animated-texture update rate.
- Resource-pack icon downscaling to 64x64.

## Already provided by Minecraft 1.8.9

The mod deliberately does not duplicate:

- Entity frustum testing and vanilla base render-distance testing.
- Chunk frustum testing.
- `ChunkOcclusionDataBuilder` and visible-chunk graph traversal.
- Ordinary block-face checks and OpenGL back-face state.
- Threaded `ChunkBuilder` rebuild workers.
- Chunk-layer and particle-layer batching.
- Entity model-part display-list compilation.

There is no separate “Batch Model Rendering” switch because 1.8.9 already batches the applicable vanilla paths. A switch without a new implementation would be misleading.

## Settings

Open **Options → Video Settings → Legacy Culling…**. Settings are stored in `config/legacy-culling.properties`.

Smart Entity Culling pauses entity occlusion when an OptiFine shader pack is detected. The explicit category-disable controls still behave as configured.

## Scope and testing

The automated build verifies Java 8 compilation, remapping, runtime-jar contents, every required mixin class, and client startup. In-world testing remains necessary for server resource packs, OptiFine versions, GPU drivers, and unusual entity models. See `TESTING.md`.
