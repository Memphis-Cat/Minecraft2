@echo off
setlocal EnableExtensions

cd /d "%~dp0"

echo ========================================
echo   Minecraft2 - Release x64 Build
echo ========================================
echo.

where cmake >nul 2>nul
if errorlevel 1 (
    echo ERROR: CMake was not found in PATH.
    echo Install Visual Studio with "Desktop development with C++"
    echo and the CMake tools for Windows component.
    echo.
    pause
    exit /b 1
)

if not exist "CMakeLists.txt" (
    echo ERROR: CMakeLists.txt was not found.
    echo Keep build.bat in the repository root.
    echo.
    pause
    exit /b 1
)

set "GENERATOR="
cmake --help | findstr /C:"Visual Studio 18 2026" >nul
if not errorlevel 1 set "GENERATOR=Visual Studio 18 2026"

if not defined GENERATOR (
    cmake --help | findstr /C:"Visual Studio 17 2022" >nul
    if not errorlevel 1 set "GENERATOR=Visual Studio 17 2022"
)

if not defined GENERATOR (
    echo ERROR: No supported Visual Studio CMake generator was found.
    echo Install Visual Studio 2026 or Visual Studio 2022 with:
    echo   Desktop development with C++
    echo   MSVC x64/x86 build tools
    echo   Windows SDK
    echo   CMake tools for Windows
    echo.
    pause
    exit /b 1
)

echo Using: %GENERATOR%
echo.

if exist "build" (
    echo Removing previous build directory...
    rmdir /s /q "build"
    if exist "build" (
        echo ERROR: Could not remove the build directory.
        echo Close Visual Studio and any running Minecraft2.exe process.
        echo.
        pause
        exit /b 1
    )
)

echo [1/2] Configuring x64 Release project...
cmake -S . -B build -G "%GENERATOR%" -A x64
if errorlevel 1 goto :failed

echo.
echo [2/2] Building Release...
cmake --build build --config Release --parallel
if errorlevel 1 goto :failed

echo.
echo ========================================
echo BUILD SUCCEEDED
echo EXE: build\Release\Minecraft2.exe
echo ========================================
echo.

if /I "%~1"=="run" (
    echo Starting Minecraft2...
    start "" "build\Release\Minecraft2.exe"
)

pause
exit /b 0

:failed
echo.
echo ========================================
echo BUILD FAILED
echo ========================================
echo Check the first compiler error above.
echo.
pause
exit /b 1
