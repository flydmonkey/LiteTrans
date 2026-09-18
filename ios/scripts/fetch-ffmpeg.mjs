import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import {
  createWriteStream,
  mkdirSync,
  readdirSync,
  readFileSync,
  renameSync,
  rmSync,
  statSync,
} from "node:fs";
import { pipeline } from "node:stream/promises";
import { fileURLToPath } from "node:url";
import https from "node:https";
import path from "node:path";

const ARCHIVE_URL =
  "https://github.com/NooruddinLakhani/ffmpeg-kit-ios-full-gpl/archive/refs/tags/latest.zip";
const EXPECTED_SHA256 =
  "00af5c43a67fbf82f117da6058364af2d28e37f82db5a8460565eba719468ce3";
const REQUIRED_CONFIGURATION_FLAGS = [
  "--enable-libx264",
  "--enable-libx265",
  "--enable-libvpx",
  "--enable-libmp3lame",
  "--enable-libopus",
];

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const iosDir = path.resolve(scriptDir, "..");
const cacheDir = path.join(iosDir, ".ffmpeg-cache");
const archivePath = path.join(cacheDir, "ffmpeg-kit-ios-full-gpl-latest.zip");
const extractDir = path.join(cacheDir, "extract");
const outputDir = path.join(iosDir, "Vendor", "FFmpeg");

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

function findNamedDirectory(directory, matcher) {
  const matches = [];
  for (const entry of readdirSync(directory, { withFileTypes: true }).sort((a, b) =>
    a.name.localeCompare(b.name),
  )) {
    const candidate = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      if (matcher(entry.name)) matches.push(candidate);
      matches.push(...findNamedDirectory(candidate, matcher));
    }
  }
  return matches;
}

function collectBinaries(directory) {
  const files = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const candidate = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      files.push(...collectBinaries(candidate));
      continue;
    }
    if (!entry.isFile() && !entry.isSymbolicLink()) continue;
    if (entry.name.includes(".")) continue;
    try {
      if (statSync(candidate).isFile() && statSync(candidate).size > 10_000) {
        files.push(candidate);
      }
    } catch {
      // Skip broken symlinks.
    }
  }
  return files;
}

function isFfmpegXcframework(name) {
  return (
    name === "ffmpegkit.xcframework" ||
    /^libav.+\.xcframework$/.test(name) ||
    /^libsw.+\.xcframework$/.test(name)
  );
}

function statExists(file) {
  try {
    return statSync(file).isFile();
  } catch {
    return false;
  }
}

async function main() {
  mkdirSync(cacheDir, { recursive: true });
  if (!statExists(archivePath)) {
    console.log("正在下载 iOS FFmpegKit xcframework...");
    await download(ARCHIVE_URL, archivePath);
  }

  const actualSha256 = sha256(archivePath);
  if (actualSha256 !== EXPECTED_SHA256) {
    rmSync(archivePath, { force: true });
    throw new Error(
      `FFmpeg 归档 sha256 校验失败：期望 ${EXPECTED_SHA256}，实际 ${actualSha256}`,
    );
  }

  rmSync(extractDir, { recursive: true, force: true });
  mkdirSync(extractDir, { recursive: true });
  execFileSync("unzip", ["-q", archivePath, "-d", extractDir], {
    stdio: "inherit",
  });

  const frameworks = findNamedDirectory(extractDir, isFfmpegXcframework);
  const unique = new Map();
  for (const framework of frameworks) {
    unique.set(path.basename(framework), framework);
  }
  if (!unique.has("ffmpegkit.xcframework")) {
    throw new Error("归档内找不到 ffmpegkit.xcframework");
  }
  if (![...unique.keys()].some((name) => name.startsWith("libav"))) {
    throw new Error("归档内找不到 libav*.xcframework");
  }

  mkdirSync(outputDir, { recursive: true });
  for (const entry of readdirSync(outputDir)) {
    if (entry.endsWith(".xcframework")) {
      rmSync(path.join(outputDir, entry), { recursive: true, force: true });
    }
  }

  for (const [name, source] of unique) {
    const destination = path.join(outputDir, name);
    execFileSync("cp", ["-a", source, destination]);
    console.log(`已安装 ${destination}`);
  }

  const binaries = collectBinaries(outputDir);
  if (binaries.length === 0) {
    throw new Error("解压后找不到可检查的 FFmpeg 二进制");
  }
  const embeddedStrings = binaries
    .map((file) =>
      execFileSync("strings", [file], {
        encoding: "utf8",
        maxBuffer: 64 * 1024 * 1024,
      }),
    )
    .join("\n");
  const missingFlags = REQUIRED_CONFIGURATION_FLAGS.filter(
    (flag) => !embeddedStrings.includes(flag),
  );
  if (missingFlags.length > 0) {
    throw new Error(
      `FFmpeg 缺少必需配置：${missingFlags.join(", ")}。请勿使用此不完整构建。`,
    );
  }
}

main().catch((error) => {
  console.error(error.message);
  process.exit(1);
});
