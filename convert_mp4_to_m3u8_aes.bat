@echo off
chcp 65001 >nul
setlocal EnableExtensions

title MP4 批量转 AES m3u8

rem ===== 配置 =====
set "FFMPEG=F:\cms\视频转化\ffmpeg-8.0.1-full_build\bin\ffmpeg.exe"
set "AES_KEY=1234567890abcdef"
set "AES_IV=fedcba0987654321"
set "LOG_FILE=转换记录.txt"

if not exist "%FFMPEG%" (
  echo [错误] 未找到 FFmpeg: %FFMPEG%
  echo 请确认路径是否正确。
  echo.
  pause
  exit /b 1
)

for /f "usebackq delims=" %%i in (`powershell -NoProfile -Command "Get-Date -Format yyyyMMdd"`) do set "TODAY=%%i"
set "OUTPUT_ROOT=转换输出_%TODAY%"
if not exist "%OUTPUT_ROOT%" mkdir "%OUTPUT_ROOT%"

for /f "usebackq delims=" %%i in (`powershell -NoProfile -Command "[System.BitConverter]::ToString([Text.Encoding]::ASCII.GetBytes('%AES_IV%')).Replace('-','')"`) do set "AES_IV_HEX=%%i"

set "FOUND=0"
for %%F in (*.mp4) do (
  set "FOUND=1"
  call :ProcessOne "%%~fF"
)

if "%FOUND%"=="0" (
  echo 未找到 MP4 文件。
)

echo.
echo 全部处理完成。
pause
exit /b 0

:ProcessOne
set "INPUT=%~1"
set "FILENAME=%~nx1"
set "BASENAME=%~n1"

set "SKIP=0"
if exist "%LOG_FILE%" (
  findstr /i /c:"%FILENAME%," "%LOG_FILE%" | findstr /c:",成功" >nul
  if not errorlevel 1 set "SKIP=1"
)

if "%SKIP%"=="1" (
  echo [跳过] %FILENAME%（日志中已成功）
  goto :eof
)

set "OUTDIR=%OUTPUT_ROOT%\%BASENAME%"
if not exist "%OUTDIR%" mkdir "%OUTDIR%"

set "KEY_FILE=%OUTDIR%\key.key"
set "KEY_INFO=%OUTDIR%\key_info.txt"
set "DETAIL_LOG=%OUTDIR%\ffmpeg.log"
set "M3U8=%OUTDIR%\%BASENAME%.m3u8"
set "SEGMENT_PATTERN=%OUTDIR%\%BASENAME%_%%03d.ts"

<nul set /p="%AES_KEY%" > "%KEY_FILE%"
(
  echo key.key
  echo %KEY_FILE%
  echo %AES_IV_HEX%
) > "%KEY_INFO%"

for /f "usebackq delims=" %%t in (`powershell -NoProfile -Command "Get-Date -Format yyyy-MM-dd\ HH:mm:ss"`) do set "TS=%%t"

echo.
echo [开始] %FILENAME%
echo 输出目录: %OUTDIR%
> "%DETAIL_LOG%" echo ===== %TS% =====
>> "%DETAIL_LOG%" echo 命令: "%FFMPEG%" -y -i "%INPUT%" -c:v copy -c:a copy -hls_time 10 -hls_playlist_type vod -hls_key_info_file "%KEY_INFO%" -hls_segment_filename "%SEGMENT_PATTERN%" "%M3U8%"
echo 命令: "%FFMPEG%" -y -i "%INPUT%" -c:v copy -c:a copy -hls_time 10 -hls_playlist_type vod -hls_key_info_file "%KEY_INFO%" -hls_segment_filename "%SEGMENT_PATTERN%" "%M3U8%"

powershell -NoProfile -Command ^
  "& '%FFMPEG%' -y -i '%INPUT%' -c:v copy -c:a copy -hls_time 10 -hls_playlist_type vod -hls_key_info_file '%KEY_INFO%' -hls_segment_filename '%SEGMENT_PATTERN%' '%M3U8%' 2>&1 | Tee-Object -FilePath '%DETAIL_LOG%' -Append; exit $LASTEXITCODE"

set "EXITCODE=%ERRORLEVEL%"

if "%EXITCODE%"=="0" (
  echo %FILENAME%,%TS%,成功>> "%LOG_FILE%"
  echo [成功] %FILENAME%
) else (
  echo %FILENAME%,%TS%,失败>> "%LOG_FILE%"
  echo [失败] %FILENAME%（详见：%DETAIL_LOG%）
)

goto :eof
