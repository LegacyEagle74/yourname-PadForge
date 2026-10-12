@echo off
rem Lets the PadForge app reach this PC from your home network and from Tailscale (100.64.0.0/10),
rem and from nowhere else. Run once; it asks for administrator rights.
rem It also removes "block Python" rules Windows creates when a firewall prompt is dismissed,
rem because those override the allow rule (common reason it works at home but not away).
net session >nul 2>&1 || (
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)
echo Updating Windows Firewall for PadForge...
netsh advfirewall firewall delete rule name="PadForge" >nul 2>&1
netsh advfirewall firewall add rule name="PadForge" dir=in action=allow protocol=TCP localport=47800 profile=any remoteip=localsubnet,100.64.0.0/10 >nul
netsh advfirewall firewall add rule name="PadForge" dir=in action=allow protocol=UDP localport=47801 profile=any remoteip=localsubnet >nul
powershell -NoProfile -Command "$r = Get-NetFirewallApplicationFilter | Where-Object { $_.Program -match '\\pythonw?\.exe$' } | Get-NetFirewallRule | Where-Object { $_.Direction -eq 'Inbound' -and $_.Action -eq 'Block' }; if ($r) { $r | Remove-NetFirewallRule; Write-Host ('Removed ' + @($r).Count + ' rule(s) that were blocking Python.') }"
echo Done: PadForge can be reached from your home network and Tailscale only.
if /i not "%~1"=="nopause" pause
