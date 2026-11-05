@echo off
chcp 65001 >nul
title DwgMind 自检
cd /d "%~dp0"

set "JAVA_HOME=C:\Users\86180\.jdks\corretto-17.0.20.1"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo ============ DwgMind 端到端自检 ============
echo.

echo [1] 健康检查
curl -s http://127.0.0.1:8080/api/v1/health
echo.
echo.

echo [2] 上传示例图纸并解析
curl -s -X POST http://127.0.0.1:8080/api/v1/drawings/upload -F "file=@frontend/samples/sample.dxf"
echo.
echo.

echo [3] 等待解析完成（5 秒）
timeout /t 5 /nobreak >nul
echo.

echo [4] 图纸列表
curl -s http://127.0.0.1:8080/api/v1/drawings
echo.
echo.

echo ============ 自检结束 ============
echo 打开前端: http://127.0.0.1:5173
pause >nul
