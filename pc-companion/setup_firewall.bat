@echo off
rem Lets the PadForge app reach this PC from your home network and from Tailscale (100.64.0.0/10),
rem and from nowhere else. Run once; it asks for administrator rights.
net session >nul 2>&1 || (
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)
netsh advfirewall firewall delete rule name="PadForge" >nul 2>&1
netsh advfirewall firewall add rule name="PadForge" dir=in action=allow protocol=TCP localport=47800 profile=any remoteip=localsubnet,100.64.0.0/10
netsh advfirewall firewall add rule name="PadForge" dir=in action=allow protocol=UDP localport=47801 profile=any remoteip=localsubnet
echo.
echo Firewall rules added: PadForge can be reached from your home network and Tailscale only.
pause
