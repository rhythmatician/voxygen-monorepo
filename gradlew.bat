@rem Monorepo delegation wrapper -- forwards to java\gradlew.bat with -p java
@rem Preferred invocation from repo root: gradlew.bat :compileJava  or  rtk proxy gradlew.bat :compileJava
@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"
call "%SCRIPT_DIR%\java\gradlew.bat" -p "%SCRIPT_DIR%\java" %*
endlocal & exit /b %ERRORLEVEL%
