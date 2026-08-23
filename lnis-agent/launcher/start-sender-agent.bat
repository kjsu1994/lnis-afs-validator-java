@echo off
setlocal

rem Use only the Java 21 runtime bundled with this LNIS Agent distribution.
rem No system PATH or system JAVA_HOME value is changed.
set "AGENT_HOME=%~dp0"
set "JAVA_HOME=%AGENT_HOME%runtime\jdk-21"
set "LNIS_AGENT_CONFIG=%AGENT_HOME%conf\agent-sender.properties"
set "LNIS_NATIVE_DIR=%AGENT_HOME%native"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo ERROR: Bundled LNIS Java 21 runtime was not found.
    echo Run: powershell -ExecutionPolicy Bypass -File "%AGENT_HOME%runtime\install-java21.ps1"
    exit /b 1
)

echo Starting LNIS Sender Agent with bundled Java 21.
echo JAVA_HOME=%JAVA_HOME%
call "%AGENT_HOME%bin\lnis-agent.bat"
