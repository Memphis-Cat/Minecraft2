@echo off
setlocal EnableExtensions

rem Minecraft2 branch downloader/updater
set "REPO_URL=https://github.com/Memphis-Cat/Minecraft2.git"
set "BRANCH=agent/d3d11-voxel-foundation"
set "DEST=%~1"
set "DO_BUILD="

rem Usage:
rem   download.bat
rem   download.bat MyFolder
rem   download.bat --build
rem   download.bat MyFolder --build

if /I "%~1"=="--build" (
    set "DEST=Minecraft2"
    set "DO_BUILD=1"
) else if /I "%~2"=="--build" (
    set "DO_BUILD=1"
)

if not defined DEST set "DEST=Minecraft2"

where git >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Git was not found in PATH.
    echo Install Git for Windows, then run this file again.
    exit /b 1
)

rem Keep relative destinations beside this batch file.
cd /d "%~dp0"

if not exist "%DEST%\.git\" (
    if exist "%DEST%\" (
        echo [ERROR] "%DEST%" already exists but is not a Git repository.
        echo Choose another destination folder or remove/rename the existing folder.
        exit /b 1
    )

    echo [INFO] Cloning %BRANCH% into "%DEST%"...
    git clone --branch "%BRANCH%" --single-branch "%REPO_URL%" "%DEST%"
    if errorlevel 1 (
        echo [ERROR] Clone failed.
        exit /b 1
    )
) else (
    echo [INFO] Updating "%DEST%" from %BRANCH%...
    pushd "%DEST%"

    git remote get-url origin >nul 2>&1
    if errorlevel 1 (
        git remote add origin "%REPO_URL%"
    ) else (
        git remote set-url origin "%REPO_URL%"
    )
    if errorlevel 1 goto :update_failed

    git fetch --prune origin "+refs/heads/%BRANCH%:refs/remotes/origin/%BRANCH%"
    if errorlevel 1 goto :update_failed

    git show-ref --verify --quiet "refs/heads/%BRANCH%"
    if errorlevel 1 (
        git checkout -b "%BRANCH%" --track "origin/%BRANCH%"
    ) else (
        git checkout "%BRANCH%"
    )
    if errorlevel 1 goto :update_failed

    rem Fast-forward only: never overwrite local commits or uncommitted work.
    git merge --ff-only "origin/%BRANCH%"
    if errorlevel 1 goto :update_failed

    popd
)

if defined DO_BUILD call :build_project
if errorlevel 1 exit /b 1

echo.
echo [SUCCESS] Minecraft2 is ready in "%DEST%".
if not defined DO_BUILD echo Run "%~nx0" "%DEST%" --build to compile it.
exit /b 0

:update_failed
echo [ERROR] Update failed. Local changes or commits may need attention.
popd
exit /b 1

:build_project
where cmake >nul 2>&1
if errorlevel 1 (
    echo [ERROR] CMake was not found in PATH.
    echo Install CMake 3.24+ and Visual Studio 2022 Desktop development with C++.
    exit /b 1
)

pushd "%DEST%"
echo [INFO] Configuring the x64 Release build...
cmake -S . -B build -A x64
if errorlevel 1 goto :build_failed

echo [INFO] Building Minecraft2...
cmake --build build --config Release
if errorlevel 1 goto :build_failed

popd
exit /b 0

:build_failed
echo [ERROR] Build failed.
popd
exit /b 1
