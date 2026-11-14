@echo off
setlocal
cd /d "%~dp0"
py -3.13 --version >nul 2>&1
if errorlevel 1 (
  echo Install Python 3.13 64-bit from python.org with the Python launcher first.
  pause
  exit /b 1
)
py -3.13 -m venv .venv-win
if errorlevel 1 goto :failed
".venv-win\Scripts\python.exe" -m pip install --upgrade pip
if errorlevel 1 goto :failed
".venv-win\Scripts\python.exe" -m pip install -r requirements-ios26.txt
if errorlevel 1 goto :failed
".venv-win\Scripts\python.exe" src\main.py --help
if errorlevel 1 goto :failed
echo Installation complete. Run START-WINDOWS.bat.
pause
exit /b 0
:failed
echo Installation failed. Save the error output before closing this window.
pause
exit /b 1
