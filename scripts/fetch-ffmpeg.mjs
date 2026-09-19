import { createRequire } from "node:module";
import { chmodSync, copyFileSync, createWriteStream, mkdirSync } from "node:fs";
import { pipeline } from "node:stream/promises";
import { createGunzip } from "node:zlib";
import https from "node:https";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { assertSidecarArch } from "./sidecar-arch.mjs";

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

function ffprobeReleaseTag() {
  try {
    const pkg = require("ffmpeg-static/package.json");
    const tag = pkg?.["ffmpeg-static"]?.["binary-release-tag"];
    if (typeof tag === "string" && tag.length > 0) return tag;
  } catch {
    // fall through
  }
  return "b6.1.1";
}

function ffprobeDownloadUrl() {
  const platform = os.platform();
  const arch = os.arch();
  const release = ffprobeReleaseTag();
  return `https://github.com/eugeneware/ffmpeg-static/releases/download/${release}/ffprobe-${platform}-${arch}.gz`;
}

function downloadGunzip(url, dest) {
  return new Promise((resolve, reject) => {
    const request = (current) => {
      https
        .get(current, { headers: { "User-Agent": "video-converter" } }, (res) => {
          if (res.statusCode && res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
            res.resume();
            request(res.headers.location);
            return;
          }
          if (res.statusCode !== 200) {
            reject(new Error(`下载 ffprobe 失败：HTTP ${res.statusCode} (${current})`));
            res.resume();
            return;
          }
          const out = createWriteStream(dest, { mode: 0o755 });
          pipeline(res, createGunzip(), out).then(resolve).catch(reject);
        })
        .on("error", reject);
    };
    request(url);
  });
}

const ffmpeg = require("ffmpeg-static");
if (!ffmpeg) {
  throw new Error("未能解析 ffmpeg-static 路径");
}

const destDir = path.join(root, "src-tauri", "binaries");
mkdirSync(destDir, { recursive: true });
const triple = targetTriple();
const ffmpegDest = path.join(destDir, sidecarName("ffmpeg", triple));
const ffprobeDest = path.join(destDir, sidecarName("ffprobe", triple));
copyFileSync(ffmpeg, ffmpegDest);
if (os.platform() !== "win32") {
  chmodSync(ffmpegDest, 0o755);
}
assertSidecarArch(ffmpegDest);

const url = ffprobeDownloadUrl();
console.log(`下载 ffprobe: ${url}`);
await downloadGunzip(url, ffprobeDest);
if (os.platform() !== "win32") {
  chmodSync(ffprobeDest, 0o755);
}
assertSidecarArch(ffprobeDest);

console.log(`已复制 sidecar:\n  ${ffmpegDest}\n  ${ffprobeDest}`);
