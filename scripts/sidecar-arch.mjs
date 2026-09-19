import { readFileSync } from "node:fs";
import os from "node:os";

const MH_MAGIC_64 = 0xfeedfacf;
const MH_CIGAM_64 = 0xcffaedfe;
const CPU_TYPE_X86_64 = 0x01000007;
const CPU_TYPE_ARM64 = 0x0100000c;
const EM_X86_64 = 62;
const EM_AARCH64 = 183;
const IMAGE_FILE_MACHINE_AMD64 = 0x8664;
const IMAGE_FILE_MACHINE_ARM64 = 0xaa64;

export function parseNativeArch(buf) {
  if (!buf || buf.length < 8) return null;

  if (buf[0] === 0x7f && buf[1] === 0x45 && buf[2] === 0x4c && buf[3] === 0x46) {
    if (buf.length < 20) return null;
    const le = buf[5] === 1;
    const machine = le ? buf.readUInt16LE(18) : buf.readUInt16BE(18);
    if (machine === EM_X86_64) return "x86_64";
    if (machine === EM_AARCH64) return "arm64";
    return `elf-${machine}`;
  }

  if (buf[0] === 0x4d && buf[1] === 0x5a) {
    if (buf.length < 0x40) return null;
    const peOffset = buf.readUInt32LE(0x3c);
    if (peOffset + 6 > buf.length) return null;
    if (buf.toString("ascii", peOffset, peOffset + 4) !== "PE\0\0") return null;
    const machine = buf.readUInt16LE(peOffset + 4);
    if (machine === IMAGE_FILE_MACHINE_AMD64) return "x86_64";
    if (machine === IMAGE_FILE_MACHINE_ARM64) return "arm64";
    return `pe-${machine.toString(16)}`;
  }

  const magicLe = buf.readUInt32LE(0);
  if (magicLe === MH_MAGIC_64) {
    const cpu = buf.readUInt32LE(4);
    if (cpu === CPU_TYPE_X86_64) return "x86_64";
    if (cpu === CPU_TYPE_ARM64) return "arm64";
    return `macho-${cpu.toString(16)}`;
  }
  if (magicLe === MH_CIGAM_64) {
    const cpu = buf.readUInt32BE(4);
    if (cpu === CPU_TYPE_X86_64) return "x86_64";
    if (cpu === CPU_TYPE_ARM64) return "arm64";
    return `macho-${cpu.toString(16)}`;
  }
  return null;
}

export function expectedHostArch() {
  const arch = os.arch();
  if (arch === "arm64") return "arm64";
  if (arch === "x64") return "x86_64";
  return arch;
}

export function nativeArchOfFile(filePath) {
  const buf = readFileSync(filePath);
  return parseNativeArch(buf.subarray(0, Math.min(buf.length, 4096)));
}

export function assertSidecarArch(filePath) {
  const actual = nativeArchOfFile(filePath);
  const expected = expectedHostArch();
  if (actual !== expected) {
    throw new Error(
      `${filePath} 架构是 ${actual ?? "未知"}，当前系统是 ${expected}（会导致 Bad CPU type / os error 86）`,
    );
  }
}
