cmake_minimum_required(VERSION 3.24)
project(Minecraft2 LANGUAGES CXX)

if(NOT WIN32)
    message(FATAL_ERROR "Minecraft2 uses Direct3D 11 and currently builds on Windows only.")
endif()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_executable(Minecraft2 WIN32
    src/main.cpp
    src/App.cpp
    src/Assets.cpp
    src/Blocks.cpp
    src/World.cpp
    src/Player.cpp
    src/RendererCore.cpp
    src/RendererDraw.cpp
)

target_include_directories(Minecraft2 PRIVATE src)
target_compile_definitions(Minecraft2 PRIVATE UNICODE _UNICODE NOMINMAX WIN32_LEAN_AND_MEAN)

target_compile_options(Minecraft2 PRIVATE
    $<$<CXX_COMPILER_ID:MSVC>:/W4 /permissive- /EHsc /Zc:__cplusplus>
)

target_link_libraries(Minecraft2 PRIVATE
    d3d11
    dxgi
    d3dcompiler
    windowscodecs
    ole32
    user32
    gdi32
    shell32
)

# Prefer the normal repository layout used by the current asset pack:
#   assets/textures/blocks/*.png
# Fall back to the older nested layout only when the normal one is absent:
#   assets/assets/textures/blocks/*.png
if(EXISTS "${CMAKE_SOURCE_DIR}/assets/textures/blocks")
    set(MINECRAFT_ASSET_SOURCE "${CMAKE_SOURCE_DIR}/assets")
elseif(EXISTS "${CMAKE_SOURCE_DIR}/assets/assets/textures/blocks")
    set(MINECRAFT_ASSET_SOURCE "${CMAKE_SOURCE_DIR}/assets/assets")
else()
    set(MINECRAFT_ASSET_SOURCE "${CMAKE_SOURCE_DIR}/assets")
    message(WARNING "No block texture directory was found under assets/textures/blocks or assets/assets/textures/blocks")
endif()

message(STATUS "Minecraft asset source: ${MINECRAFT_ASSET_SOURCE}")

# Keep one unambiguous runtime layout:
#   Minecraft2.exe
#   assets/textures
#   assets/sounds
#   assets/enviroment
#   assets/blocks      (optional metadata)
#   assets/worldgen    (optional terrain override)
add_custom_command(TARGET Minecraft2 POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E rm -rf
        "$<TARGET_FILE_DIR:Minecraft2>/assets"
    COMMAND ${CMAKE_COMMAND} -E copy_directory
        "${MINECRAFT_ASSET_SOURCE}"
        "$<TARGET_FILE_DIR:Minecraft2>/assets"
    COMMENT "Copying Minecraft assets beside the executable"
)
