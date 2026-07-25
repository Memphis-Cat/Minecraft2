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
    echo Install Visual Studio 2022 with "Desktop development with C++"
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

echo [1/2] Configuring Visual Studio 2022 x64...
cmake -S . -B build -G "Visual Studio 17 2022" -A x64
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
echo Check the error messages above.
echo.
pause
exit /b 1
