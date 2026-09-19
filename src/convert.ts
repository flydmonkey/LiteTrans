import type { ConvertMode } from "./types";

export type { ConvertMode };

export type DocumentSourceKind = "image" | "pdf" | "word" | "excel";

export const VIDEO_CONCAT_PRESET = "video-concat";
export const VIDEO_CONCAT_MAX_SOURCES = 20;
export const DEFAULT_VIDEO_PRESET = "mp4-h264";

/** Video-mode cards (primary + More). Covered by tsc; no frontend test runner. */
export const VIDEO_PRESETS = [
  "mp4-h264",
  "mp4-copy",
  "mp4-h265",
  "mov-h264",
  VIDEO_CONCAT_PRESET,
  "mkv-copy-friendly",
  "mkv-h265",
  "webm-vp9",
  "avi-mpeg4",
  "gif",
] as const;

export function clampVideoPreset(preset: string): string {
  const id = preset.trim();
  return (VIDEO_PRESETS as readonly string[]).includes(id) ? id : DEFAULT_VIDEO_PRESET;
}

export function canStart(
  importable: number,
  probing: boolean,
  transcoding: boolean,
  outputReady: boolean,
  preset: string,
  sourceCount: number,
): boolean {
  const concat = isVideoConcatPreset(preset);
  const minimum = concat ? 2 : 1;
  const maximum = concat ? VIDEO_CONCAT_MAX_SOURCES : Number.MAX_SAFE_INTEGER;
  const noRejects = !concat || importable === sourceCount;
  return (
    importable >= minimum &&
    importable <= maximum &&
    noRejects &&
    !probing &&
    !transcoding &&
    outputReady
  );
}

export function isVideoConcatPreset(preset: string): boolean {
  return preset === VIDEO_CONCAT_PRESET;
}

export function allowsTrim(preset: string): boolean {
  return preset !== "mp4-copy" && !isVideoConcatPreset(preset);
}

function fileExtension(fileName: string): string | null {
  const dot = fileName.lastIndexOf(".");
  if (dot < 0 || dot === fileName.length - 1) return null;
  return fileName.slice(dot + 1).toLowerCase();
}

export function documentSourceKind(fileName: string): DocumentSourceKind | null {
  const ext = fileExtension(fileName);
  if (!ext) return null;
  switch (ext) {
    case "jpg":
    case "jpeg":
    case "png":
    case "webp":
    case "bmp":
    case "gif":
      return "image";
    case "pdf":
      return "pdf";
    case "docx":
      return "word";
    case "xlsx":
    case "xls":
    case "doc":
      return "excel";
    default:
      return null;
  }
}

export function sameDocumentKind(existing: string[], incoming: string): boolean {
  if (existing.length === 0) return true;
  const incomingKind = documentSourceKind(incoming);
  if (!incomingKind) return false;
  return existing.every((name) => documentSourceKind(name) === incomingKind);
}

export function clampPageRange(start: number, end: number, pageCount: number): [number, number] {
  const pages = Math.max(pageCount, 1);
  const lo = Math.min(Math.max(start, 1), pages);
  const hi = Math.min(Math.max(end, lo), pages);
  return [lo, hi];
}

/** Inherit the session page-range control onto a newly probed document, clamped to that file. */
export function pageRangeAfterProbe(
  sessionStart: number,
  sessionEnd: number,
  pageCount: number,
): [number, number] {
  return clampPageRange(sessionStart, sessionEnd, pageCount);
}

export function defaultDocumentPreset(kind: DocumentSourceKind): string {
  switch (kind) {
    case "image":
      return "image-jpg";
    case "pdf":
      return "pdf-image";
    case "word":
      return "office-pdf";
    case "excel":
      throw new Error("Excel sources have no default preset");
  }
}

function staticImageExtension(imageFormat?: string | null): string | null {
  const trimmed = imageFormat?.trim();
  if (!trimmed) return null;
  switch (trimmed) {
    case "jpg":
    case "jpeg":
      return "jpg";
    case "png":
      return "png";
    case "webp":
      return "webp";
    case "bmp":
      return "bmp";
    case "gif":
      return "gif";
    default:
      return null;
  }
}

export function documentExtension(preset: string, imageFormat?: string | null): string {
  switch (preset) {
    case "image-jpg":
      return "jpg";
    case "image-png":
      return "png";
    case "image-webp":
      return "webp";
    case "image-bmp":
      return "bmp";
    case "image-gif":
      return "gif";
    case "image-compress":
    case "pdf-image":
      return staticImageExtension(imageFormat) ?? "jpg";
    case "pdf-txt":
      return "txt";
    case "pdf-compress":
    case "pdf-split":
    case "office-pdf":
      return "pdf";
    default:
      return staticImageExtension(imageFormat) ?? "bin";
  }
}

const IMAGE_DOCUMENT_PRESETS = [
  "image-jpg",
  "image-png",
  "image-webp",
  "image-bmp",
  "image-gif",
  "image-compress",
] as const;

const PDF_DOCUMENT_PRESETS = ["pdf-image", "pdf-txt", "pdf-compress", "pdf-split"] as const;

const WORD_DOCUMENT_PRESETS = ["office-pdf"] as const;

export function documentCardsFor(kind: DocumentSourceKind): readonly string[] {
  switch (kind) {
    case "image":
      return IMAGE_DOCUMENT_PRESETS;
    case "pdf":
      return PDF_DOCUMENT_PRESETS;
    case "word":
      return WORD_DOCUMENT_PRESETS;
    case "excel":
      return [];
  }
}

export function plannedOutputCount(preset: string, pageStart: number, pageEnd: number): number {
  if (preset === "pdf-image" || preset === "pdf-split") {
    const lo = Math.min(pageStart, pageEnd);
    const hi = Math.max(pageStart, pageEnd);
    return Math.max(1, hi - lo + 1);
  }
  return 1;
}

export function documentOutputFileName(
  stem: string,
  index: number,
  total: number,
  ext: string,
): string {
  if (total <= 1) return `${stem}.${ext}`;
  return `${stem}-${String(index).padStart(3, "0")}.${ext}`;
}

export function evenDimension(value: number): number {
  return Math.max(2, value - (value % 2));
}

export function concatOutputStem(displayName: string): string {
  const base = displayName.replace(/^.*[/\\]/, "");
  const dot = base.lastIndexOf(".");
  const stem = dot > 0 ? base.slice(0, dot) : base || "output";
  return `${stem}-merged`;
}
