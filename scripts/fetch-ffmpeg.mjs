import { createRequire } from "node:module";
import { chmodSync, copyFileSync, mkdirSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const root = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");

function targetTriple() {
  const platform = os.platform();
  const arch = os.arch();
  if (platform === "darwin" && arch === "arm64") return "aarch64-apple-darwin";
  if (platform === "darwin" && arch === "x64") return "x86_64-apple-darwin";
  if (platform === "linux" && arch === "x64") return "x86_64-unknown-linux-gnu";
  if (platform === "linux" && arch === "arm64") return "aarch64-unknown-linux-gnu";
  if (platform === "win32" && arch === "x64") return "x86_64-pc-windows-msvc";
  if (platform === "win32" && arch === "arm64") return "aarch64-pc-windows-msvc";
  throw new Error(`暂不支持 ${platform}-${arch} 的 FFmpeg sidecar`);
}

function sidecarName(base, triple) {
  return os.platform() === "win32" ? `${base}-${triple}.exe` : `${base}-${triple}`;
}

const ffmpeg = require("ffmpeg-static");
const ffprobe = require("ffprobe-static").path;
if (!ffmpeg || !ffprobe) {
  throw new Error("未能解析 ffmpeg-static / ffprobe-static 路径");
}

const destDir = path.join(root, "src-tauri", "binaries");
mkdirSync(destDir, { recursive: true });
const triple = targetTriple();
const ffmpegDest = path.join(destDir, sidecarName("ffmpeg", triple));
const ffprobeDest = path.join(destDir, sidecarName("ffprobe", triple));
copyFileSync(ffmpeg, ffmpegDest);
copyFileSync(ffprobe, ffprobeDest);
if (os.platform() !== "win32") {
  chmodSync(ffmpegDest, 0o755);
  chmodSync(ffprobeDest, 0o755);
}
console.log(`已复制 sidecar:\n  ${ffmpegDest}\n  ${ffprobeDest}`);
