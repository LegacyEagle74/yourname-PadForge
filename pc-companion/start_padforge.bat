@echo off
rem Double-click to start the PadForge companion.
rem First run installs vgamepad (virtual Xbox controller). Accept the ViGEmBus driver prompt if it appears.
cd /d "%~dp0"
where py >nul 2>nul && (set PY=py -3) || (set PY=python)
%PY% -c "import vgamepad" 2>nul || (
  echo Installing vgamepad for controller support...
  %PY% -m pip install -r requirements.txt
)
%PY% padforge_server.py %*
pause
