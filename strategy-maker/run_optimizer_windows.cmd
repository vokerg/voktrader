@echo off
REM run_optimizer_windows.cmd
REM Convenience wrapper around run_optimizer_windows.ps1.
REM Put this file next to strategy-maker\voktrader_optimizer.py.

set SCRIPT_DIR=%~dp0
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%run_optimizer_windows.ps1" %*
exit /b %ERRORLEVEL%
