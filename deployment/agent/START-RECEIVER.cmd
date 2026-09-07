@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-agent.ps1" -Role receiver
if errorlevel 1 pause
