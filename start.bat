@echo off
chcp 936 >nul
cd /d "%~dp0"

echo ============================================
echo   DwgMind - Starting services
echo ============================================
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start.ps1"

echo.
echo Press any key to close this window (services keep running)...
pause >nul
