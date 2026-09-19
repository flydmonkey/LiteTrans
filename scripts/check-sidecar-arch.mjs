import { existsSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  expectedHostArch,
  nativeArchOfFile,
  parseNativeArch,
} from "./sidecar-arch.mjs";

function assert(cond, msg) {
  if (!cond) {
    console.error(msg);
    process.exit(1);
  }
}

const armHeader = Buffer.alloc(8);
armHeader.writeUInt32LE(0xfeedfacf, 0);
armHeader.writeUInt32LE(0x0100000c, 4);
assert(parseNativeArch(armHeader) === "arm64", "mach-o arm64 header");

const x64Header = Buffer.alloc(8);
x64Header.writeUInt32LE(0xfeedfacf, 0);
x64Header.writeUInt32LE(0x01000007, 4);
assert(parseNativeArch(x64Header) === "x86_64", "mach-o x86_64 header");

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
          : os.arch() === "arm64"
            ? "aarch64-pc-windows-msvc"
            : "x86_64-pc-windows-msvc";
const suffix = os.platform() === "win32" ? ".exe" : "";
const host = expectedHostArch();

for (const name of ["ffmpeg", "ffprobe"]) {
  const file = path.join(root, "src-tauri", "binaries", `${name}-${triple}${suffix}`);
  assert(existsSync(file), `missing ${file}`);
  const actual = nativeArchOfFile(file);
  assert(
    actual === host,
    `${name} sidecar arch is ${actual}, expected ${host}`,
  );
}

console.log(`sidecar arch ok: ffmpeg/ffprobe are ${host}`);
