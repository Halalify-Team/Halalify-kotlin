---
name: fix-youtube-download-android15
description: Guidelines and documentation on fixing YouTube downloads and chunking on Android 15 with 16KB page alignment constraints.
---

# YouTube Download & Chunking on Android 15 (16KB Page Size)

This skill documents the architecture and constraints for downloading and processing YouTube video and audio chunks on Android 15 devices with 16KB page size.

## The 16KB Page Size Constraint
Starting with Android 15, devices can run with a 16KB system page size rather than the traditional 4KB. Any precompiled native executables or shared libraries (`.so` files) that are not compiled with 16KB alignment (e.g. `align-to-16kb` flags) will fail to load or execute, producing dynamic linker errors like:
`program alignment (4096) cannot be smaller than system page size (16384)`

## Architectural Solution

### 1. Avoid Subprocess executables (youtubedl-android:ffmpeg)
The `youtubedl-android:ffmpeg` package bundles precompiled `ffmpeg`/`ffprobe` command-line binaries. Because these binaries and their dependent libraries (like `libwebp.so`, `libsharpyuv.so`, etc.) are compiled with 4KB alignment, they crash immediately on 16KB-page-size emulators or devices.
* **Rule:** Do NOT use `yt-dlp` features that call `ffmpeg` internally, such as `--download-sections` or post-processing merges. They will fail with: `ERROR: You have requested downloading the video partially, but ffmpeg is not installed. Aborting`.

### 2. Stream chunks via LocalMediaProxy + FFmpegKit
Instead of running subprocesses or downloading full streams to disk (which violates chunk-only requirements), use a local proxy and JNI-based FFmpegKit (compiled with 16KB alignment, such as `io.github.maxrave-dev:ffmpeg-kit-audio:6.0.1`):
* **LocalMediaProxy**: Acts as a bridge. It runs a local HTTP server (`http://127.0.0.1:PORT/media`) and tunnels media stream requests from FFmpeg Kit using OkHttp (handling SSL/HTTPS). It supports **HTTP Range requests** to enable seeking.
* **FFmpegKit**: Execute dynamic command strings via JNI to seek (`-ss`) and extract the chunk (`-t`) from the local proxy URL:
  * For **Audio Chunks**: Re-encode to AAC format (`-c:a aac -b:a 128k -vn`).
  * For **Video Chunks**: Copy the video stream directly without re-encoding to preserve speed and quality (`-c:v copy -an`).

### 3. Ensure Fresh Signed URLs
YouTube stream URLs resolved via InnerTube directly (or via fast player API) can expire quickly or trigger `403 Forbidden` if requested with an incorrect client User-Agent or if signatures are not resolved.
* **Rule:** Always call `resolveFreshForDownload(activity, url)` before starting download tasks to bypass cached/unsigned InnerTube URLs and get fresh, signed stream URLs using `yt-dlp`.
* **Rule:** Verify and clean HTTP request headers case-insensitively to prevent duplicate `User-Agent` headers.
