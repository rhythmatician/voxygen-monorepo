@rem Monorepo delegation wrapper -- forwards to mod\gradlew.bat with -p mod
@rem Preferred invocation from repo root: gradlew.bat :compileJava  or  rtk proxy gradlew.bat :compileJava
@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"
call "%SCRIPT_DIR%\mod\gradlew.bat" -p "%SCRIPT_DIR%\mod" %*
endlocal & exit /b %ERRORLEVEL%
