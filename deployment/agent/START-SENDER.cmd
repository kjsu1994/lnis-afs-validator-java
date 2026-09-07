@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-agent.ps1" -Role sender
if errorlevel 1 pause
