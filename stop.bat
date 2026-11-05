@echo off
chcp 936 >nul
cd /d "%~dp0"

echo ============================================
echo   DwgMind - Stopping services
echo ============================================
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stop.ps1"

echo.
echo Press any key to exit...
pause >nul
