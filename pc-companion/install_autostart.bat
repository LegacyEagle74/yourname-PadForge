@echo off
rem Starts the PadForge companion automatically (hidden, as administrator) every time you sign in,
rem so it's already running when you connect from outside with Parsec.
rem Output goes to padforge.log in this folder. Run remove_autostart.bat to undo.
net session >nul 2>&1 || (
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)
cd /d "%~dp0"
set PYW=
for /f "delims=" %%i in ('where pyw 2^>nul') do if not defined PYW set "PYW=%%i"
if not defined PYW for /f "delims=" %%i in ('where pythonw 2^>nul') do if not defined PYW set "PYW=%%i"
if not defined PYW (
  echo Could not find pythonw.exe. Install Python 3 from python.org and tick "Add python.exe to PATH".
  pause
  exit /b 1
)
"%PYW%" -c "import vgamepad" 2>nul || (
  echo Installing vgamepad for controller support...
  py -3 -m pip install -r requirements.txt 2>nul || python -m pip install -r requirements.txt
)
schtasks /create /f /tn "PadForge" /sc onlogon /rl highest /tr "\"%PYW%\" \"%~dp0padforge_server.py\""
if errorlevel 1 (
  echo Could not create the scheduled task.
  pause
  exit /b 1
)
schtasks /run /tn "PadForge" >nul
echo.
echo PadForge now starts automatically when you sign in to Windows (it's running now too).
where py >nul 2>nul && (py -3 "%~dp0padforge_server.py" --show-code) || (python "%~dp0padforge_server.py" --show-code)
echo Logs: %~dp0padforge.log
pause
