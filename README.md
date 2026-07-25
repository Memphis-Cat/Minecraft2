# Minecraft2 — Direct3D 11 voxel foundation

A Windows C++20/Direct3D 11 voxel prototype built around the assets already in this repository.

## Current milestone

- Asset-driven block discovery. No C++ list of block names is required.
- Optional `.block` metadata files supply hardness, exact hand-breaking ticks, face aliases, and tinting.
- Missing or unreadable PNGs are reported once at startup and replaced with `fallback.png`.
- If `fallback.png` is also unavailable, an in-memory magenta/black checker is used.
- Fixed 20 TPS game simulation.
- Minecraft-style 0.6 × 1.8 player collision, 1.62 eye height, walking, sprinting, sneaking, jumping, gravity, air drag, and ground friction.
- 4.5-block voxel DDA reach.
- Tick-accurate progressive breaking and `destroy_stage_0.png` through `destroy_stage_9.png` overlays.
- 16 × 64 × 16 chunks, streamed around the player.
- Greedy exposed-face meshing, back-face culling, chunk frustum culling, and a bounded mesh rebuild budget.
- Selection outline on the targeted block.
- Layer profile: grass, three dirt, five stone, bedrock.
- Grass climate tint coordinate calculation follows the supplied temperature/humidity formula.

## Build

Requirements:

- Windows 10 or 11
- Visual Studio 2022 with **Desktop development with C++**
- CMake 3.24+

```powershell
cmake -S . -B build -A x64
cmake --build build --config Release
.\build\Release\Minecraft2.exe
```

The build copies the `assets` folder beside the executable.

## Controls

- `WASD`: move
- `Space`: jump
- `Left Shift`: sneak
- `Left Control` or double-tap `W`: sprint
- Mouse: look
- Hold left mouse: break targeted block
- `Escape`: release/capture mouse

## Adding blocks without editing C++

Put PNG files in `assets/assets/textures/blocks`.

Supported automatic naming:

- `cobblestone.png`: all six faces
- `log_top.png` + `log_side.png`: top/bottom and sides
- `<name>_top`, `_bottom`, `_side`, `_north`, `_south`, `_east`, `_west`

An optional `assets/assets/blocks/<name>.block` file can override inferred values. Unknown properties are ignored so the format can grow without breaking old packs.
