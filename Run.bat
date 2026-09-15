@echo off
setlocal
cd /d "%~dp0"
call Build.bat
if errorlevel 1 (
  pause
  exit /b 1
)
java -cp ".;lib/*" SnakeApp
if errorlevel 1 pause
