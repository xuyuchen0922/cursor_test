MP4 批量转 AES 加密 m3u8（10s 分片）

使用：
1) 把 `mp4_to_aes_m3u8.bat`、`mp4_to_aes_m3u8.ps1` 放到含 MP4 的同一文件夹
2) 双击运行 `mp4_to_aes_m3u8.bat`

输出：
- 当前文件夹生成 `转换输出_YYYYMMDD\视频名\`（m3u8 + ts + `详细转换日志.txt`）
- 当前文件夹追加写入 `视频转换全局记录.txt`（filename.mp4,YYYYMMDD_HHMMSS,成功/失败）

重转：
- 删除 `视频转换全局记录.txt` 里该视频对应的“成功”行，下次运行会重新处理

说明：
- FFmpeg 路径固定：`F:\cms\视频转化\ffmpeg-8.0.1-full_build\bin\ffmpeg.exe`
- AES_KEY=`1234567890abcdef`，AES_IV=`fedcba0987654321`（按 ASCII 16 字节转为 32 位 hex 传给 FFmpeg）
- m3u8 的 KEY URL 固定为 `enc.key`（需由你的服务/Worker 提供对应 key 内容）
