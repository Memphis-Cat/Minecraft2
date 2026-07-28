# Sodium Culling Addon

A clean-room Fabric client optimization addon for Minecraft 26.2. Sodium is required; Iris is supported through conservative fallbacks.

## Build on Windows

Double-click `build.bat`. It downloads private copies of Java 25 and Gradle 9.5.1 when needed, then places the jar in `build\libs`.

## Design rule

The addon does not replace Sodium terrain section/chunk culling or Iris shader-pipeline culling. It adds conservative culling for render categories not already owned by those renderers.
