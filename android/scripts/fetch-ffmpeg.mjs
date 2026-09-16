import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import {
  chmodSync,
  copyFileSync,
  createReadStream,
  createWriteStream,
  mkdirSync,
  readdirSync,
  readFileSync,
  renameSync,
  rmSync,
  statSync,
} from "node:fs";
import { Writable } from "node:stream";
import { pipeline } from "node:stream/promises";
import { fileURLToPath } from "node:url";
import https from "node:https";
import { createGunzip } from "node:zlib";
import path from "node:path";

const ARCHIVE_URL =
  "https://github.com/fazi-gondal/ffmpeg/releases/download/latest/ffmpeg-android-arm64-v8a.tar.gz";
const EXPECTED_SHA256 =
  "7dbe009d53ae6bc6eddd7dc8a025726b4a41d5b1fd6933f6b1811328b0ed9e39";
const REQUIRED_CONFIGURATION_FLAGS = [
  "--enable-libx264",
  "--enable-libx265",
  "--enable-libvpx",
  "--enable-libmp3lame",
  "--enable-libopus",
  "--enable-mediacodec",
];

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const androidDir = path.resolve(scriptDir, "..");
const cacheDir = path.join(androidDir, ".ffmpeg-cache");
const archivePath = path.join(cacheDir, "ffmpeg-android-arm64-v8a.tar.gz");
const extractDir = path.join(cacheDir, "arm64-v8a");
const outputDir = path.join(
  androidDir,
  "app",
  "src",
  "main",
  "jniLibs",
  "arm64-v8a",
);

function download(url, destination, redirectsLeft = 5) {
  return new Promise((resolve, reject) => {
    const request = https.get(url, (response) => {
      if (
        response.statusCode >= 300 &&
        response.statusCode < 400 &&
        response.headers.location
      ) {
        response.resume();
        if (redirectsLeft === 0) {
          reject(new Error("下载重定向次数过多"));
          return;
        }
        const redirected = new URL(response.headers.location, url).href;
        download(redirected, destination, redirectsLeft - 1).then(resolve, reject);
        return;
      }

      if (response.statusCode !== 200) {
        response.resume();
        reject(new Error(`下载失败：HTTP ${response.statusCode}`));
        return;
      }

      const partial = `${destination}.partial`;
      const output = createWriteStream(partial);
      pipeline(response, output)
        .then(() => {
          renameSync(partial, destination);
          resolve();
        })
        .catch((error) => {
          rmSync(partial, { force: true });
          reject(error);
        });
    });
    request.on("error", reject);
  });
}

function sha256(file) {
  return createHash("sha256").update(readFileSync(file)).digest("hex");
}

async function verifyGzip(file) {
  await pipeline(
    createReadStream(file),
    createGunzip(),
    new Writable({ write(_chunk, _encoding, callback) { callback(); } }),
  );
}

function findNamedFile(directory, targetName) {
  for (const entry of readdirSync(directory, { withFileTypes: true }).sort((a, b) =>
    a.name.localeCompare(b.name),
  )) {
    const candidate = path.join(directory, entry.name);
    if (entry.isFile() && entry.name === targetName) return candidate;
    if (entry.isDirectory()) {
      const nested = findNamedFile(candidate, targetName);
      if (nested) return nested;
    }
  }
  return null;
}

async function main() {
  mkdirSync(cacheDir, { recursive: true });
  if (!statExists(archivePath)) {
    console.log("正在下载 Android arm64 FFmpeg...");
    await download(ARCHIVE_URL, archivePath);
  }

  const actualSha256 = sha256(archivePath);
  if (actualSha256 !== EXPECTED_SHA256) {
    rmSync(archivePath, { force: true });
    throw new Error(
      `FFmpeg 归档 sha256 校验失败：期望 ${EXPECTED_SHA256}，实际 ${actualSha256}`,
    );
  }
  await verifyGzip(archivePath);

  rmSync(extractDir, { recursive: true, force: true });
  mkdirSync(extractDir, { recursive: true });
  execFileSync("tar", ["-xzf", archivePath, "-C", extractDir], {
    stdio: "inherit",
  });

  const ffmpeg = findNamedFile(extractDir, "ffmpeg");
  if (!ffmpeg) {
    throw new Error("归档内找不到独立 CLI 二进制：ffmpeg");
  }
  const embeddedStrings = execFileSync("strings", [ffmpeg], {
    encoding: "utf8",
    maxBuffer: 64 * 1024 * 1024,
  });
  const missingFlags = REQUIRED_CONFIGURATION_FLAGS.filter(
    (flag) => !embeddedStrings.includes(flag),
  );
  if (missingFlags.length > 0) {
    throw new Error(
      `FFmpeg CLI 缺少必需配置：${missingFlags.join(", ")}。请勿使用此不完整构建。`,
    );
  }

  mkdirSync(outputDir, { recursive: true });
  for (const name of ["ffmpeg", "ffprobe"]) {
    const source = findNamedFile(extractDir, name);
    if (!source) {
      throw new Error(`归档内找不到独立 CLI 二进制：${name}`);
    }
    const destination = path.join(outputDir, `lib${name}.so`);
    copyFileSync(source, destination);
    chmodSync(destination, 0o755);
    console.log(`已安装 ${destination}`);
  }
}

function statExists(file) {
  try {
    return statSync(file).isFile();
  } catch {
    return false;
  }
}

main().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
