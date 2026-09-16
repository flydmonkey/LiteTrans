## 1. Project scaffolding

- [x] 1.1 Initialize a Tauri 2 + Vite + React + TypeScript app in the repo root
- [x] 1.2 Configure Chinese default UI copy, app name, and window title for the desktop shell
- [x] 1.3 Add README notes for LGPL FFmpeg attribution and local-only processing

## 2. FFmpeg sidecar and native commands

- [x] 2.1 Bundle FFmpeg and FFprobe sidecar binaries for the current host platform and wire Tauri sidecar config
- [x] 2.2 Implement `probe_media` to run FFprobe JSON and map duration, container, codecs, resolution, and frame rate
- [x] 2.3 Implement native file and folder pickers for sources and output directory
- [x] 2.4 Implement whitelist-based FFmpeg argument builder for presets and custom options (no shell-concatenated commands)

## 3. Source import UI

- [x] 3.1 Build the source list with picker import, drag-and-drop, and duplicate-path suppression
- [x] 3.2 Probe each added file and show media info or a readable not-transcodable error
- [x] 3.3 Allow removing sources that are not currently transcoding without deleting original files

## 4. Output presets and custom settings

- [x] 4.1 Add built-in presets: `mp4-h264` (default), `mp4-h265`, `webm-vp9`, `mkv-copy-friendly`, `audio-mp3`
- [x] 4.2 Add custom controls for container, video encoder, max resolution, video bitrate, frame rate, audio encoder, and audio bitrate
- [x] 4.3 Implement output naming `{stem}_{preset}.{ext}` with numeric suffix and refuse overwrite
- [x] 4.4 Reject `copy` when the destination container cannot hold the source codec; reject `audio-mp3` when the source has no audio

## 5. Transcode engine and job queue

- [x] 5.1 Implement enqueue of one job per importable source, skipping non-importable sources with a report
- [x] 5.2 Run at most one FFmpeg process; write `*.partial` then rename on success; delete partials on fail or cancel
- [x] 5.3 Stream progress events to the UI for the running job
- [x] 5.4 Support cancel of the active job (kill process, mark `cancelled`) and retry of failed jobs
- [x] 5.5 Show job status (`queued` / `running` / `completed` / `failed` / `cancelled`) plus output path or error text

## 6. Desktop shell polish and packaging

- [x] 6.1 Remember the selected output directory for the current session
- [x] 6.2 Verify probe and transcode work when FFmpeg is not on the system PATH
- [x] 6.3 Add host-platform package/build script and document how to produce macOS, Windows, and Linux installers
- [x] 6.4 Smoke-test import → preset → queue → cancel/retry on the development OS with at least one real video file
