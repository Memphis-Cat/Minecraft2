@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "TOOLS=%CD%\.build-tools"
set "JDK_DIR=%TOOLS%\jdk-21"
set "GRADLE_HOME=%TOOLS%\gradle-9.5.1"

where java >nul 2>nul
if errorlevel 1 goto :download_java
for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set JAVA_VER=%%~v
set JAVA_VER=%JAVA_VER:"=%
for /f "tokens=1 delims=." %%m in ("%JAVA_VER%") do set JAVA_MAJOR=%%m
if "%JAVA_MAJOR%"=="1" for /f "tokens=2 delims=." %%m in ("%JAVA_VER%") do set JAVA_MAJOR=%%m
if %JAVA_MAJOR% GEQ 21 goto :java_ok

:download_java
echo Java 21 is required to run the modern Legacy Loom build. Downloading a private Temurin JDK 21 copy...
if not exist "%TOOLS%" mkdir "%TOOLS%"
if not exist "%JDK_DIR%\bin\java.exe" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Invoke-WebRequest -UseBasicParsing 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse' -OutFile '%TOOLS%\jdk21.zip'; Expand-Archive -Force '%TOOLS%\jdk21.zip' '%TOOLS%\jdk-temp'; $d=Get-ChildItem '%TOOLS%\jdk-temp' -Directory | Select-Object -First 1; Move-Item $d.FullName '%JDK_DIR%'; Remove-Item -Recurse -Force '%TOOLS%\jdk-temp'; Remove-Item -Force '%TOOLS%\jdk21.zip'"
  if errorlevel 1 goto :fail
)
set "JAVA_HOME=%JDK_DIR%"
set "PATH=%JAVA_HOME%\bin;%PATH%"

:java_ok
if not exist "%GRADLE_HOME%\bin\gradle.bat" (
  echo Downloading Gradle 9.5.1...
  if not exist "%TOOLS%" mkdir "%TOOLS%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Invoke-WebRequest -UseBasicParsing 'https://services.gradle.org/distributions/gradle-9.5.1-bin.zip' -OutFile '%TOOLS%\gradle.zip'; Expand-Archive -Force '%TOOLS%\gradle.zip' '%TOOLS%'; Remove-Item -Force '%TOOLS%\gradle.zip'"
  if errorlevel 1 goto :fail
)

echo Building Legacy Culling for Minecraft 1.8.9...
call "%GRADLE_HOME%\bin\gradle.bat" clean build --stacktrace
if errorlevel 1 goto :fail

echo.
echo Build complete. Install only the jar without developer-sources in its name.
explorer "%CD%\build\libs"
exit /b 0

:fail
echo.
echo Build failed. Read the error above.
pause
exit /b 1
