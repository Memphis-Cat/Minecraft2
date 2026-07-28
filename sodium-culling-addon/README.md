# Sodium Culling Addon

A clean-room Fabric client optimization addon for **Minecraft Java 26.2**. Sodium 0.9.1 or newer is required. Iris 1.11.2 or newer is supported through conservative shader-aware fallbacks.

No other mod is bundled in this jar and no source code was copied from another culling mod.

## Build on Windows

Double-click `build.bat`. It downloads private copies of Java 25 and Gradle 9.5.1 when required, runs the Fabric build, and opens `build\libs`.

## Implemented by this addon

- Entity, mob, dropped-item, item-frame and painting distance/frustum/camera-angle culling.
- Cached CPU block-ray occlusion for entities, block entities and particle cells.
- A real reversed-depth Hierarchical-Z pyramid captured after the world pass on OpenGL. HZB is used only with a current collision-ray confirmation and falls back safely if uncertain.
- Painting and item-frame rear-face culling through vanilla hanging-entity facing.
- Block-entity distance, frustum and occlusion culling, with safe exclusions for globally rendered/off-screen renderers.
- Beacon-beam visibility bounds extending to world height; beacon sources are not wall-occluded incorrectly.
- Particle frustum, camera-angle, distance, fog, occlusion and per-cell density admission culling.
- Actual vanilla `FogData` end-distance culling for entities, particles, block entities and weather columns.
- Rain/snow column frustum and fog culling.
- Internal leaf-face culling.
- Front/back sign-text culling with an edge-on safety overlap.
- Entity nametags, model extraction, visual animation calculation and normal entity shadows are skipped when the owning entity is conclusively culled.
- Portal-related entities, block entities and particles use their respective visibility paths.

## Already supplied by Minecraft, Sodium or Iris

These are deliberately not implemented a second time:

- Terrain/block frustum and occlusion culling.
- Whole chunk and chunk-section culling.
- Section-graph/portal visibility traversal.
- Normal block-face, back-face and block-model-part removal.
- Sodium chunk building, task scheduling and loading optimizations.
- Vanilla cloud empty-cell, internal-face and distance mesh reduction. Shader-pack clouds remain owned by Iris/the shader pack.
- Shader-specific shadow-camera, fog and cloud culling.

## Compatibility behavior

When an Iris shader pack is active, this addon keeps safe distance/frustum tests but disables main-camera CPU ray and HZB decisions. This prevents the main camera from deleting geometry needed by a shader shadow pass. Bliss, Complementary Unbound and Mellow therefore use their own shader-specific fog, cloud and shadow behavior.

Continuity, LambDynamicLights, Zoomify, Shulker Box Tooltip, FancyMenu, Drippy Loading Screen and Not Enough Animations are not patched or bundled. The addon only touches vanilla render extraction points. FancyMenu/Drippy GUI optimization is outside this build, as agreed.

## Configuration

The first launch creates `config/sodium-culling.properties`. Every added system can be disabled independently. HZB capture cadence and base pyramid width are configurable for slower GPUs.

## Validation completed

The CI workflow compiles with Java 25 against Minecraft 26.2, starts the client with Sodium, then starts it again with Sodium and Iris. Both startup tests reject mixin application, dependency-resolution and initialization failures. In-world visual/performance testing is still recommended because hardware, shaders and resource packs can expose scene-specific issues.
