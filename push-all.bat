@echo off
chcp 936 >nul
cd /d "%~dp0"

echo ============================================
echo   DwgMind - Push to GitHub + Gitee
echo ============================================
echo.

set "GIT_SSH=C:\Program Files\Git\usr\bin\ssh.exe"

echo [1/3] Staging changes ...
git add -A
echo.

echo [2/3] Commit (skip if nothing to commit) ...
git diff --cached --quiet
if errorlevel 1 (
    git commit -m "chore: update %DATE% %TIME%"
) else (
    echo   Nothing to commit.
)
echo.

echo [3/3] Pushing ...
echo   --^> GitHub
git push origin main
if errorlevel 1 (
    echo   [WARN] GitHub push failed.
)
echo   --^> Gitee
git push gitee main
if errorlevel 1 (
    echo   [WARN] Gitee push failed.
)

echo.
echo Done. Verify both:
echo   https://github.com/gxhyj666-png/dwgmind
echo   https://gitee.com/gxhyj66/dwgmind
echo.
pause >nul
