@echo off
setlocal
cd /d "%~dp0"
chcp 65001 >nul

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0mp4_to_aes_m3u8.ps1"
echo.
pause

