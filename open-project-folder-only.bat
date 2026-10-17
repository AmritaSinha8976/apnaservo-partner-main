@echo off
set "PROJECT_DIR=%~dp0"
set "STUDIO_EXE=%ProgramFiles%\Android\Android Studio\bin\studio64.exe"

if "%PROJECT_DIR:~-1%"=="\" set "PROJECT_DIR=%PROJECT_DIR:~0,-1%"

if not exist "%STUDIO_EXE%" (
  echo Android Studio was not found at:
  echo %STUDIO_EXE%
  echo.
  echo Open Android Studio manually and choose this exact folder:
  echo %PROJECT_DIR%
  pause
  exit /b 1
)

echo Opening only this Android Studio project:
echo %PROJECT_DIR%
echo.
echo Important: do not open MainActivity.java directly.
echo Important: do not choose the parent "New project" folder.
start "" "%STUDIO_EXE%" "%PROJECT_DIR%"
