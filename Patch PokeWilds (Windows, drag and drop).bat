@echo off
rem Drag your official pokewilds.jar onto this file. It writes pokewilds-bugfix.jar next to it.
cd /d "%~dp0"
if "%~1"=="" (
  echo Drag your official pokewilds.jar onto this file to patch it.
  pause
  exit /b 1
)
echo Patching "%~1" ...
java -jar bugfix.jar "%~1" "%~dpn1-bugfix.jar"
if errorlevel 1 (
  echo.
  echo Patching failed. See the message above. Nothing was written.
) else (
  echo.
  echo Done. Use "%~dpn1-bugfix.jar" in place of pokewilds.jar.
)
pause
