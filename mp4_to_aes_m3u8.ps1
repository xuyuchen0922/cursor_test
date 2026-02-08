$ErrorActionPreference = 'Continue'

try {
  [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
  $OutputEncoding = [Console]::OutputEncoding
} catch {}

$FFMPEG = 'F:\cms\视频转化\ffmpeg-8.0.1-full_build\bin\ffmpeg.exe'
$AES_KEY = '1234567890abcdef'
$AES_IV  = 'fedcba0987654321'
$HLS_TIME_SECONDS = 10
$KEY_URL_IN_M3U8 = 'enc.key'

function ToHexAscii16([string]$s) {
  $bytes = [System.Text.Encoding]::ASCII.GetBytes($s)
  if ($bytes.Length -ne 16) { throw "KEY/IV 必须为16字符(16字节ASCII): $s" }
  -join ($bytes | ForEach-Object { $_.ToString('x2') })
}

$WorkDir = $PSScriptRoot
Set-Location -LiteralPath $WorkDir

$globalLog = Join-Path $WorkDir '视频转换全局记录.txt'
$today = Get-Date -Format 'yyyyMMdd'
$outRoot = Join-Path $WorkDir ("转换输出_{0}" -f $today)

$done = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::OrdinalIgnoreCase)
if (Test-Path -LiteralPath $globalLog) {
  foreach ($line in (Get-Content -LiteralPath $globalLog -ErrorAction SilentlyContinue)) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $parts = $line.Split(',', 3)
    if ($parts.Count -ne 3) { continue }
    if ($parts[2].Trim() -eq '成功') { [void]$done.Add($parts[0].Trim()) }
  }
}

if (-not (Test-Path -LiteralPath $FFMPEG)) {
  Write-Host "FFmpeg 不存在: $FFMPEG"
  exit 2
}

$mp4s = Get-ChildItem -LiteralPath $WorkDir -File -Filter '*.mp4' -ErrorAction SilentlyContinue
if (-not $mp4s -or $mp4s.Count -eq 0) {
  Write-Host '当前文件夹未找到 MP4。'
  exit 0
}

New-Item -ItemType Directory -Path $outRoot -Force | Out-Null

$hexKey = ToHexAscii16 $AES_KEY
$hexIv  = ToHexAscii16 $AES_IV

$anyFailed = $false
$total = $mp4s.Count
$idx = 0

foreach ($f in $mp4s) {
  $idx++
  if ($done.Contains($f.Name)) {
    Write-Host ("[{0}/{1}] 跳过: {2}" -f $idx, $total, $f.Name)
    continue
  }

  $base = $f.BaseName
  $outDir = Join-Path $outRoot $base
  New-Item -ItemType Directory -Path $outDir -Force | Out-Null

  $detailLog = Join-Path $outDir '详细转换日志.txt'
  $tsStamp = Get-Date -Format 'yyyyMMdd_HHmmss'

  $m3u8Path = Join-Path $outDir ($base + '.m3u8')
  $segPattern = Join-Path $outDir ($base + '_%05d.ts')

  $args = @(
    '-hide_banner',
    '-y',
    '-i', $f.FullName,
    '-map', '0:v:0',
    '-map', '0:a?',
    '-sn',
    '-c', 'copy',
    '-hls_time', $HLS_TIME_SECONDS.ToString(),
    '-hls_playlist_type', 'vod',
    '-hls_flags', 'independent_segments',
    '-hls_segment_filename', $segPattern,
    '-hls_enc', '1',
    '-hls_enc_key', $hexKey,
    '-hls_enc_iv', $hexIv,
    '-hls_enc_key_url', $KEY_URL_IN_M3U8,
    $m3u8Path
  )

  $cmdLine = '"' + $FFMPEG + '" ' + (($args | ForEach-Object {
    if ($_ -match '\s') { '"' + ($_ -replace '"','\"') + '"' } else { $_ }
  }) -join ' ')

  Set-Content -LiteralPath $detailLog -Encoding UTF8 -Value @(
    "视频文件名: $($f.Name)"
    "转换时间: $tsStamp"
    "FFmpeg: $FFMPEG"
    "命令: $cmdLine"
    ''
    '=== FFmpeg 输出 ==='
  )

  Write-Host ("[{0}/{1}] 转换: {2}" -f $idx, $total, $f.Name)

  & $FFMPEG @args 2>&1 | Out-File -FilePath $detailLog -Append -Encoding UTF8
  $exitCode = $LASTEXITCODE

  $status = '成功'
  if ($exitCode -ne 0 -or -not (Test-Path -LiteralPath $m3u8Path)) {
    $status = '失败'
    $anyFailed = $true
  }

  Add-Content -LiteralPath $globalLog -Encoding UTF8 -Value ("{0},{1},{2}" -f $f.Name, $tsStamp, $status)
  Add-Content -LiteralPath $detailLog -Encoding UTF8 -Value @(
    ''
    "=== 结果 ==="
    "状态: $status"
    "ExitCode: $exitCode"
  )

  Write-Host ("[{0}/{1}] 结果: {2} - {3}" -f $idx, $total, $status, $f.Name)
}

if ($anyFailed) { exit 1 } else { exit 0 }

