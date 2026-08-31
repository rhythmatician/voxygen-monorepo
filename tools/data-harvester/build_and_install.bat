@echo off
setlocal enabledelayedexpansion

REM ======================================================================
REM  data-harvester — Build and Install
REM
REM  Builds the DataHarvester Fabric mod and copies the JAR to the
REM  Modrinth "LODiffusion dependencies" profile's mods folder.
REM
REM  Prerequisites:
REM    - Java 25 on PATH (Gradle 9.7.1 / Loom 1.14.10 / MC 1.21.11)
REM    - Gradle wrapper is self-contained in this directory
REM ======================================================================

set "SCRIPT_DIR=%~dp0"
set "MOD_DIR=%SCRIPT_DIR%"
set "MODRINTH_MODS=%APPDATA%\ModrinthApp\profiles\LODiffusion dependencies\mods"

echo.
echo  ================================================
echo   DataHarvester — Build ^& Install
echo  ================================================

REM --- Step 1: Build the mod ---------------------------------------------
echo.
echo  Building data-harvester mod...
pushd "%MOD_DIR%"
call gradlew.bat build
if errorlevel 1 (
    echo.
    echo  ERROR: Build failed! Check the output above.
    popd
    exit /b 1
)
popd
echo  Build succeeded.

REM --- Step 2: Find the JAR ----------------------------------------------
set "JAR_DIR=%MOD_DIR%build\libs"
set "JAR_FILE="
for %%f in ("%JAR_DIR%\data-harvester-*.jar") do (
    echo %%~nf | findstr /v "sources" >nul 2>&1
    if not errorlevel 1 (
        set "JAR_FILE=%%f"
    )
)

if "%JAR_FILE%"=="" (
    echo  ERROR: No JAR file found in %JAR_DIR%
    exit /b 1
)
echo  Built: %JAR_FILE%

REM --- Step 3: Install to Modrinth profile --------------------------------
if not exist "%MODRINTH_MODS%" (
    echo.
    echo  WARNING: Modrinth mods folder not found:
    echo    %MODRINTH_MODS%
    echo.
    echo  The JAR is at: %JAR_FILE%
    echo  Copy it to your Minecraft client mods folder manually.
    exit /b 0
)

REM Remove old versions first
del /q "%MODRINTH_MODS%\data-harvester-*.jar" 2>nul

copy "%JAR_FILE%" "%MODRINTH_MODS%\" >nul
echo.
echo  Installed to: %MODRINTH_MODS%
echo.
echo  ================================================
echo   DONE — Launch the Modrinth profile to use it
echo  ================================================
echo.

exit /b 0
