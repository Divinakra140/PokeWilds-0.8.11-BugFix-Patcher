@echo off
rem Drag your official pokewilds.jar onto this file.
rem It is replaced by the patched game; your original is kept as pokewilds-original.jar.bak in the same folder.
cd /d "%~dp0"
if "%~1"=="" (
  echo Drag your official pokewilds.jar onto this file to patch it.
  pause
  exit /b 1
)
echo Patching "%~1" ... this can take a minute or two.
java -jar bugfix.jar "%~1"
if errorlevel 1 (
  echo.
  echo Patching failed. See the message above. Your jar was not changed.
)
pause
