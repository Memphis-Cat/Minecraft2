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

add_custom_command(TARGET Minecraft2 POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_directory
        "${CMAKE_SOURCE_DIR}/assets"
        "$<TARGET_FILE_DIR:Minecraft2>/assets"
    COMMENT "Copying Minecraft assets"
)
