import type { HistorySegment, Job, JobStatus } from "./types";

// Preset id lists must stay in sync with src-tauri/src/history.rs.
const AUDIO_PRESETS = [
  "audio-mp3",
  "audio-aac",
  "audio-wav",
  "audio-flac",
  "audio-ogg",
  "audio-amr",
] as const;

const DOCUMENT_PRESETS = [
  "image-jpg",
  "image-png",
  "image-webp",
  "image-bmp",
  "image-gif",
  "image-compress",
  "pdf-image",
  "pdf-txt",
  "pdf-compress",
  "pdf-split",
  "office-pdf",
] as const;

export type JobRowAction = "cancel" | "retry" | "open" | "reveal" | "rename" | "delete";

export function isAudioPreset(preset: string): boolean {
  return (AUDIO_PRESETS as readonly string[]).includes(preset);
}

export function isDocumentPreset(preset: string): boolean {
  return (DOCUMENT_PRESETS as readonly string[]).includes(preset);
}

export function historySegmentAfterEnqueue(preset: string): HistorySegment {
  if (isAudioPreset(preset)) return "audio";
  if (isDocumentPreset(preset)) return "document";
  return "video";
}

export function historySegmentFor(job: Job): HistorySegment {
  return historySegmentAfterEnqueue(job.config.preset);
}

export function jobRowActions(status: JobStatus): JobRowAction[] {
  switch (status) {
    case "queued":
    case "running":
      return ["cancel"];
    case "failed":
    case "cancelled":
      return ["retry", "delete"];
    case "completed":
      return ["open", "reveal", "rename", "delete"];
  }
}

export function jobRowPrimaryAction(status: JobStatus): JobRowAction {
  return jobRowActions(status)[0];
}

export function jobRowOverflowActions(status: JobStatus): JobRowAction[] {
  return jobRowActions(status).slice(1);
}

export function formatHistoryDate(epochMs: number | null | undefined): string {
  if (epochMs == null) return "";
  const date = new Date(epochMs);
  if (Number.isNaN(date.getTime())) return "";
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  const hours = String(date.getHours()).padStart(2, "0");
  const minutes = String(date.getMinutes()).padStart(2, "0");
  return `${year}-${month}-${day} ${hours}:${minutes}`;
}
