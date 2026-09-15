@echo off
setlocal
cd /d "%~dp0"
where javac >nul 2>nul
if errorlevel 1 (
  echo Java JDK 17 or newer is required. Install a JDK, then run Build.bat again.
  exit /b 1
)
javac -encoding UTF-8 -cp ".;lib/*" *.java
if errorlevel 1 exit /b 1
javac -encoding UTF-8 -cp ".;lib/*" -d maps maps/*.java
if errorlevel 1 exit /b 1
echo TurboSnake built successfully.
