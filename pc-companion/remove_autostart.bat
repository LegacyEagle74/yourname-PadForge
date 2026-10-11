@echo off
rem Stops PadForge from starting at sign-in and ends the running background copy.
net session >nul 2>&1 || (
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)
schtasks /end /tn "PadForge" >nul 2>&1
schtasks /delete /f /tn "PadForge"
echo PadForge will no longer start automatically.
pause
