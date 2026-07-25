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
    echo Install CMake or Visual Studio CMake tools.
    echo.
    pause
    exit /b 1
)

where ninja >nul 2>nul
if errorlevel 1 (
    echo ERROR: Ninja was not found in PATH.
    echo Install Ninja, then reopen Command Prompt.
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

rem Ninja does not provide a C++ compiler. Load the latest installed
rem Visual Studio MSVC x64 environment automatically when cl.exe is not
rem already available in this terminal.
where cl >nul 2>nul
if errorlevel 1 (
    echo Loading the Visual Studio x64 C++ compiler environment...
    set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
    set "VSINSTALL="

    if exist "%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" (
        for /f "usebackq tokens=*" %%I in (`"%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VSINSTALL=%%I"
    )

    if not defined VSINSTALL (
        echo ERROR: Visual Studio C++ build tools were not found.
        echo Open Visual Studio Installer and install:
        echo   Desktop development with C++
        echo   MSVC x64/x86 build tools
        echo   Windows 10 or 11 SDK
        echo.
        pause
        exit /b 1
    )

    if exist "%VSINSTALL%\VC\Auxiliary\Build\vcvars64.bat" (
        call "%VSINSTALL%\VC\Auxiliary\Build\vcvars64.bat" >nul
    ) else if exist "%VSINSTALL%\Common7\Tools\VsDevCmd.bat" (
        call "%VSINSTALL%\Common7\Tools\VsDevCmd.bat" -arch=x64 -host_arch=x64 >nul
    ) else (
        echo ERROR: The Visual Studio compiler setup script was not found.
        echo Visual Studio path: %VSINSTALL%
        echo.
        pause
        exit /b 1
    )
)

where cl >nul 2>nul
if errorlevel 1 (
    echo ERROR: cl.exe is still unavailable after loading Visual Studio.
    echo Repair the Desktop development with C++ workload.
    echo.
    pause
    exit /b 1
)

for /f "tokens=1" %%V in ('cl 2^>^&1 ^| findstr /C:"Version"') do set "CL_FOUND=1"
echo MSVC compiler found.
echo.

if /I "%~1"=="clean" (
    if exist "build" rmdir /s /q "build"
)

rem Remove a cache produced by an earlier configure where no compiler existed.
if exist "build\CMakeCache.txt" (
    findstr /C:"CMAKE_CXX_COMPILER:FILEPATH=CMAKE_CXX_COMPILER-NOTFOUND" "build\CMakeCache.txt" >nul 2>nul
    if not errorlevel 1 rmdir /s /q "build"
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
