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

rem Refuse to compile unresolved Git merge markers. They create hundreds of fake C++ errors.
set "HAS_CONFLICTS="
for %%F in (CMakeLists.txt src\*.cpp src\*.h) do (
    findstr /C:"<<<<<<<" /C:"=======" /C:">>>>>>>" "%%F" >nul 2>nul && (
        echo ERROR: Unresolved Git conflict markers in %%F
        set "HAS_CONFLICTS=1"
    )
)
if defined HAS_CONFLICTS goto :merge_conflict
if exist ".git\MERGE_HEAD" goto :merge_conflict

rem Ninja is only a build runner; load MSVC when this is a normal cmd.exe.
where cl >nul 2>nul
if errorlevel 1 (
    call :load_msvc
    if errorlevel 1 exit /b 1
)

where cl >nul 2>nul
if errorlevel 1 (
    echo ERROR: cl.exe is unavailable after loading Visual Studio.
    echo Repair the Desktop development with C++ workload.
    echo.
    pause
    exit /b 1
)

echo MSVC compiler found:
where cl
echo.

rem Stop the prior game instance so the linker can replace Minecraft2.exe.
taskkill /F /IM Minecraft2.exe >nul 2>nul

if /I "%~1"=="clean" (
    if exist "build" rmdir /s /q "build"
)

rem A CMake build directory cannot switch between Visual Studio and Ninja generators.
if exist "build\CMakeCache.txt" (
    findstr /C:"CMAKE_GENERATOR:INTERNAL=Ninja" "build\CMakeCache.txt" >nul 2>nul
    if errorlevel 1 (
        echo Removing build directory created with a different CMake generator...
        rmdir /s /q "build"
    )
)

rem Remove a failed cache created before the compiler environment was loaded.
if exist "build\CMakeCache.txt" (
    findstr /C:"CMAKE_CXX_COMPILER:FILEPATH=CMAKE_CXX_COMPILER-NOTFOUND" "build\CMakeCache.txt" >nul 2>nul
    if not errorlevel 1 rmdir /s /q "build"
)

if exist "build" if not exist "build\CMakeCache.txt" rmdir /s /q "build"

echo [1/2] Configuring Ninja Release build...
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release || goto :failed

echo.
echo [2/2] Building...
cmake --build build --parallel || goto :failed

if not exist "build\Minecraft2.exe" (
    echo ERROR: The compiler reported success but build\Minecraft2.exe is missing.
    goto :failed
)

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
echo Starting Minecraft2...
start "" "build\Minecraft2.exe"

:success
pause
exit /b 0

:merge_conflict
echo.
echo ========================================
echo SOURCE REPAIR REQUIRED
echo ========================================
echo The repository contains an unfinished Git merge.
echo Run the repair commands supplied with this build, then try again.
echo Your assets folder does not need to be deleted.
echo.
pause
exit /b 2

:failed
echo.
echo ========================================
echo BUILD FAILED
echo ========================================
echo Check the first CMake, compiler, or linker error above.
echo.
pause
exit /b 1

:load_msvc
echo Loading the Visual Studio x64 C++ compiler environment...
set "VSINSTALL="
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"

if exist "%VSWHERE%" (
    for /f "usebackq tokens=*" %%I in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VSINSTALL=%%I"
)

rem Fallback for installations that are too new for an older vswhere database.
if not defined VSINSTALL (
    for %%P in (
        "%ProgramFiles%\Microsoft Visual Studio\18\Community"
        "%ProgramFiles%\Microsoft Visual Studio\18\Professional"
        "%ProgramFiles%\Microsoft Visual Studio\18\Enterprise"
        "%ProgramFiles%\Microsoft Visual Studio\18\BuildTools"
        "%ProgramFiles%\Microsoft Visual Studio\18\Preview"
        "%ProgramFiles%\Microsoft Visual Studio\17\Community"
        "%ProgramFiles%\Microsoft Visual Studio\17\Professional"
        "%ProgramFiles%\Microsoft Visual Studio\17\Enterprise"
        "%ProgramFiles%\Microsoft Visual Studio\17\BuildTools"
        "%ProgramFiles%\Microsoft Visual Studio\17\Preview"
    ) do if not defined VSINSTALL if exist "%%~P\VC\Auxiliary\Build\vcvars64.bat" set "VSINSTALL=%%~P"
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

echo Using Visual Studio: %VSINSTALL%

if exist "%VSINSTALL%\VC\Auxiliary\Build\vcvars64.bat" (
    call "%VSINSTALL%\VC\Auxiliary\Build\vcvars64.bat" >nul
) else (
    if exist "%VSINSTALL%\Common7\Tools\VsDevCmd.bat" (
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
    echo ERROR: Visual Studio was found, but cl.exe was not configured.
    echo Repair the Desktop development with C++ workload.
    echo.
    pause
    exit /b 1
)

exit /b 0
