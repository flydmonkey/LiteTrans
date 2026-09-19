import { convertFileSrc, invoke } from "@tauri-apps/api/core";
import { open } from "@tauri-apps/plugin-dialog";
import type {
  ConvertMode,
  EnqueueReport,
  HistorySegment,
  Job,
  MediaInfo,
  OutputConfig,
  PresetInfo,
  SessionSettings,
} from "./types";

const VIDEO_EXTENSIONS = [
  "mp4",
  "mkv",
  "mov",
  "avi",
  "webm",
  "flv",
  "wmv",
  "ts",
  "m4v",
  "3gp",
  "mpeg",
  "mpg",
  "m2ts",
  "vob",
  "mts",
];

const AUDIO_EXTENSIONS = [...VIDEO_EXTENSIONS, "mp3", "m4a", "wav", "flac", "ogg", "amr", "aac"];

const DOCUMENT_EXTENSIONS = [
  "jpg",
  "jpeg",
  "png",
  "webp",
  "bmp",
  "gif",
  "pdf",
  "docx",
  "doc",
  "xls",
  "xlsx",
];

export function extensionsForMode(mode: ConvertMode): string[] {
  switch (mode) {
    case "video":
      return VIDEO_EXTENSIONS;
    case "audio":
      return AUDIO_EXTENSIONS;
    case "document":
      return DOCUMENT_EXTENSIONS;
  }
}

export function pathAllowedForMode(path: string, mode: ConvertMode): boolean {
  const base = path.split(/[/\\]/).pop() ?? path;
  const dot = base.lastIndexOf(".");
  if (dot < 0 || dot === base.length - 1) return false;
  return extensionsForMode(mode).includes(base.slice(dot + 1).toLowerCase());
}

export function normalizePickedPaths(selected: string | string[] | null): string[] {
  if (selected == null) return [];
  return Array.isArray(selected) ? selected : [selected];
}

export function probeMedia(path: string) {
  return invoke<MediaInfo>("probe_media_command", { path });
}

export async function pickFiles(mode: ConvertMode, labels: { title: string; filter: string }) {
  const selected = await open({
    multiple: true,
    title: labels.title,
    filters: [{ name: labels.filter, extensions: extensionsForMode(mode) }],
  });
  return normalizePickedPaths(selected);
}

export async function pickOutputDir(title: string) {
  const selected = await open({
    directory: true,
    multiple: false,
    title,
  });
  const path = normalizePickedPaths(selected)[0] ?? null;
  if (path) {
    await invoke("set_output_dir", { path });
  }
  return path;
}

export function getOutputDir() {
  return invoke<string | null>("get_output_dir");
}

export function getOrInitOutputDir() {
  return invoke<string>("get_or_init_output_dir");
}

export function listPresets() {
  return invoke<PresetInfo[]>("list_output_presets");
}

export function listJobs() {
  return invoke<Job[]>("list_jobs");
}

export function localMediaUrl(path: string) {
  try {
    return convertFileSrc(path);
  } catch {
    return "";
  }
}

export function extractPreviewFrame(path: string, timeSecs: number) {
  return invoke<string>("extract_preview_frame_command", { path, timeSecs });
}

export function preparePreview(args: {
  path: string;
  container?: string | null;
  videoCodec?: string | null;
  audioCodec?: string | null;
  force?: boolean;
}) {
  return invoke<string>("prepare_preview_command", args);
}

export function enqueueJobs(sources: MediaInfo[], config: OutputConfig, outputDir: string) {
  return invoke<EnqueueReport>("enqueue_jobs", { sources, config, outputDir });
}

export function cancelJob(id: string) {
  return invoke<void>("cancel_job", { id });
}

export function retryJob(id: string) {
  return invoke<void>("retry_job", { id });
}

export function clearFinishedJobs(segment: HistorySegment) {
  return invoke<void>("clear_finished_jobs", { segment });
}

export function deleteJob(id: string) {
  return invoke<void>("delete_job", { id });
}

export function renameJob(id: string, rawName: string) {
  return invoke<void>("rename_job", { id, rawName });
}

export function appVersion() {
  return invoke<string>("app_version");
}

export function loadSessionSettings() {
  return invoke<SessionSettings>("load_session_settings");
}

export function saveSessionSettings(settings: SessionSettings) {
  return invoke<void>("save_session_settings", { settings });
}
