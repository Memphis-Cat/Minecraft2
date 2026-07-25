@echo off
setlocal EnableExtensions

cd /d "%~dp0"

echo ========================================
echo   Minecraft2 - Ninja Release Build
echo ========================================
echo.

where cmake >nul 2>nul
if errorlevel 1 (
    echo ERROR: CMake was not found in PATH.
    echo Install CMake or Visual Studio C++ CMake tools.
    echo.
    pause
    exit /b 1
)

where ninja >nul 2>nul
if errorlevel 1 (
    echo ERROR: Ninja was not found in PATH.
    echo Install Ninja or enable the Visual Studio CMake/Ninja tools.
    echo.
    pause
    exit /b 1
)

if not exist "CMakeLists.txt" (
    echo ERROR: CMakeLists.txt was not found.
    echo Keep build.bat in the Minecraft2 repository root.
    echo.
    pause
    exit /b 1
)

if /I "%~1"=="clean" (
    if exist "build" rmdir /s /q "build"
)

echo [1/2] Configuring Ninja Release build...
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release
if errorlevel 1 goto :failed

echo.
echo [2/2] Building...
cmake --build build --parallel
if errorlevel 1 goto :failed

echo.
echo ========================================
echo BUILD SUCCEEDED
echo EXE: build\Minecraft2.exe
echo ========================================
echo.

if /I "%~1"=="run" goto :run
if /I "%~2"=="run" goto :run
goto :success

:run
if not exist "build\Minecraft2.exe" (
    echo ERROR: build\Minecraft2.exe was not created.
    goto :failed
)
echo Starting Minecraft2...
start "" "build\Minecraft2.exe"

:success
pause
exit /b 0

:failed
echo.
echo ========================================
echo BUILD FAILED
echo ========================================
echo Check the first CMake or compiler error above.
echo.
pause
exit /b 1
