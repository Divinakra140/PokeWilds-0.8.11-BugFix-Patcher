@echo off
rem Drag the patched pokewilds.jar onto this file to put your original back.
cd /d "%~dp0"
if "%~1"=="" (
  echo Drag the patched pokewilds.jar onto this file to restore your original.
  pause
  exit /b 1
)
java -jar bugfix.jar --restore "%~1"
pause
