@echo off
cd /d "%~dp0"

if not exist "local.properties" (
  echo sdk.dir=C\:\\Users\\Admin\\AppData\\Local\\Android\\Sdk> local.properties
)

echo Building Apna Servo Partner only...
call gradlew.bat assembleDebug
pause
