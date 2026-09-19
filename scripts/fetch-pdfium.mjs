import { chmodSync, copyFileSync, createWriteStream, mkdirSync, rmSync } from "node:fs";
import { mkdtemp } from "node:fs/promises";
import { pipeline } from "node:stream/promises";
import { execFileSync } from "node:child_process";
import https from "node:https";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

// Matches pdfium-render 0.8 default feature `pdfium_latest` = 7543.
const PDFIUM_VERSION = "7543";

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
  throw new Error(`暂不支持 ${platform}-${arch} 的 pdfium`);
}

function archiveName() {
  const platform = os.platform();
  const arch = os.arch();
  if (platform === "darwin" && arch === "arm64") return "pdfium-mac-arm64.tgz";
  if (platform === "darwin" && arch === "x64") return "pdfium-mac-x64.tgz";
  if (platform === "linux" && arch === "x64") return "pdfium-linux-x64.tgz";
  if (platform === "linux" && arch === "arm64") return "pdfium-linux-arm64.tgz";
  if (platform === "win32" && arch === "x64") return "pdfium-win-x64.tgz";
  if (platform === "win32" && arch === "arm64") return "pdfium-win-arm64.tgz";
  throw new Error(`暂不支持 ${platform}-${arch} 的 pdfium`);
}

function libPathInArchive() {
  return os.platform() === "win32" ? "bin/pdfium.dll" : os.platform() === "darwin" ? "lib/libpdfium.dylib" : "lib/libpdfium.so";
}

function destName(triple) {
  return os.platform() === "win32" ? `pdfium-${triple}.dll` : `pdfium-${triple}`;
}

function download(url, dest) {
  return new Promise((resolve, reject) => {
    const request = (current) => {
      https
        .get(current, (res) => {
          if (res.statusCode && res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
            res.resume();
            request(res.headers.location);
            return;
          }
          if (res.statusCode !== 200) {
            reject(new Error(`下载 pdfium 失败：HTTP ${res.statusCode}`));
            res.resume();
            return;
          }
          const out = createWriteStream(dest);
          pipeline(res, out).then(resolve).catch(reject);
        })
        .on("error", reject);
    };
    request(url);
  });
}

const destDir = path.join(root, "src-tauri", "binaries");
mkdirSync(destDir, { recursive: true });
const triple = targetTriple();
const archive = archiveName();
const url = `https://github.com/bblanchon/pdfium-binaries/releases/download/chromium%2F${PDFIUM_VERSION}/${archive}`;
const tmp = await mkdtemp(path.join(os.tmpdir(), "pdfium-"));
const tgz = path.join(tmp, archive);

try {
  console.log(`下载 pdfium ${PDFIUM_VERSION}: ${url}`);
  await download(url, tgz);
  execFileSync("tar", ["-xzf", tgz, "-C", tmp]);
  const extracted = path.join(tmp, libPathInArchive());
  const dest = path.join(destDir, destName(triple));
  copyFileSync(extracted, dest);
  if (os.platform() !== "win32") {
    chmodSync(dest, 0o755);
  }
  console.log(`已复制 pdfium:\n  ${dest}`);
} finally {
  rmSync(tmp, { recursive: true, force: true });
}
