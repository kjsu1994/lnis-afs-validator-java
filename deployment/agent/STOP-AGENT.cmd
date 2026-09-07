@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-agent.ps1" -Role all
if errorlevel 1 pause
