@echo off
setlocal
cd /d "%~dp0"
set PYTHONUTF8=1
if not exist ".venv-win\Scripts\python.exe" (
  echo Run INSTALL-WINDOWS.bat first.
  pause
  exit /b 1
)
echo Open http://127.0.0.1:54321 in your browser after the server starts.
echo Clear location and disconnect before closing this window.
".venv-win\Scripts\python.exe" src\main.py --no-browser --port 54321
pause
