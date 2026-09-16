import { mkdirSync, mkdtempSync, rmSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const triple =
  os.platform() === "darwin" && os.arch() === "arm64"
    ? "aarch64-apple-darwin"
    : os.platform() === "darwin"
      ? "x86_64-apple-darwin"
      : os.platform() === "linux" && os.arch() === "arm64"
        ? "aarch64-unknown-linux-gnu"
        : os.platform() === "linux"
          ? "x86_64-unknown-linux-gnu"
          : "x86_64-pc-windows-msvc";
const suffix = os.platform() === "win32" ? ".exe" : "";
const ffmpeg = path.join(root, "src-tauri", "binaries", `ffmpeg-${triple}${suffix}`);
const ffprobe = path.join(root, "src-tauri", "binaries", `ffprobe-${triple}${suffix}`);
const tmp = mkdtempSync(path.join(os.tmpdir(), "video-converter-smoke-"));
const sample = path.join(tmp, "sample.mp4");
const outDir = path.join(tmp, "out");
mkdirSync(outDir);

function run(bin, args) {
  const result = spawnSync(bin, args, {
    encoding: "utf8",
    env: { ...process.env, PATH: os.platform() === "win32" ? r("C:\\Windows\\System32") : "/usr/bin:/bin" },
  });
  if (result.status !== 0) {
    throw new Error(`${bin} ${args.join(" ")}\n${result.stderr || result.stdout}`);
  }
  return result;
}

function r(value) {
  return value;
}

try {
  run(ffmpeg, [
    "-hide_banner",
    "-y",
    "-f",
    "lavfi",
    "-i",
    "testsrc=duration=1:size=320x240:rate=15",
    "-f",
    "lavfi",
    "-i",
    "sine=frequency=880:duration=1",
    "-shortest",
    "-c:v",
    "libx264",
    "-c:a",
    "aac",
    sample,
  ]);
  run(ffprobe, ["-v", "error", "-show_format", "-show_streams", "-of", "json", sample]);
  const output = path.join(outDir, "sample_mp4-h264.mp4");
  run(ffmpeg, [
    "-hide_banner",
    "-y",
    "-i",
    sample,
    "-c:v",
    "libx264",
    "-preset",
    "ultrafast",
    "-c:a",
    "aac",
    output,
  ]);
  console.log("烟雾测试通过：受限 PATH 下 sidecar 可探测并转码");
  console.log(output);
} finally {
  rmSync(tmp, { recursive: true, force: true });
}
