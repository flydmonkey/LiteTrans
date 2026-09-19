import { useCallback, useEffect, useMemo, useRef, useState, type PointerEvent } from "react";
import { getCurrentWebview } from "@tauri-apps/api/webview";
import {
  enqueueJobs,
  getOrInitOutputDir,
  loadSessionSettings,
  localMediaUrl,
  pathAllowedForMode,
  pickFiles,
  preparePreview,
  pickOutputDir,
  probeMedia,
  saveSessionSettings,
} from "./api";
import {
  allowsTrim,
  canStart,
  clampVideoPreset,
  defaultDocumentPreset,
  documentSourceKind,
  isVideoConcatPreset,
  sameDocumentKind,
} from "./convert";
import { t } from "./i18n";
import type { ConvertMode, Job, MediaInfo, ModeSettings, OutputConfig, SourceItem } from "./types";

const PROBE_CONCURRENCY = 4;

async function mapWithConcurrency<T>(
  items: T[],
  limit: number,
  worker: (item: T) => Promise<void>,
) {
  let next = 0;
  await Promise.all(
    Array.from({ length: Math.min(limit, items.length) }, async () => {
      while (true) {
        const index = next;
        next += 1;
        if (index >= items.length) return;
        await worker(items[index]);
      }
    }),
  );
}

type PresetCard = {
  id: string;
  titleKey: string;
  hintKey: string;
  badgeKey?: string;
};

const VIDEO_PRESET_CARDS: PresetCard[] = [
  {
    id: "mp4-h264",
    titleKey: "preset_mp4_h264_title",
    hintKey: "preset_mp4_h264_hint",
    badgeKey: "preset_badge_common",
  },
  {
    id: "mp4-copy",
    titleKey: "preset_mp4_copy_title",
    hintKey: "preset_mp4_copy_hint",
    badgeKey: "preset_badge_fastest",
  },
  {
    id: "mp4-h265",
    titleKey: "preset_mp4_h265_title",
    hintKey: "preset_mp4_h265_hint",
  },
  {
    id: "mov-h264",
    titleKey: "preset_mov_h264_title",
    hintKey: "preset_mov_h264_hint",
  },
  {
    id: "video-concat",
    titleKey: "preset_video_concat_title",
    hintKey: "preset_video_concat_hint",
  },
  {
    id: "mkv-copy-friendly",
    titleKey: "preset_mkv_copy_friendly_title",
    hintKey: "preset_mkv_copy_friendly_hint",
  },
  {
    id: "mkv-h265",
    titleKey: "preset_mkv_h265_title",
    hintKey: "preset_mkv_h265_hint",
  },
  {
    id: "webm-vp9",
    titleKey: "preset_webm_vp9_title",
    hintKey: "preset_webm_vp9_hint",
  },
  {
    id: "avi-mpeg4",
    titleKey: "preset_avi_mpeg4_title",
    hintKey: "preset_avi_mpeg4_hint",
  },
  {
    id: "gif",
    titleKey: "preset_gif_title",
    hintKey: "preset_gif_hint",
  },
];

const AUDIO_PRESET_CARDS: PresetCard[] = [
  {
    id: "audio-mp3",
    titleKey: "preset_audio_mp3_title",
    hintKey: "preset_audio_mp3_hint",
  },
  {
    id: "audio-aac",
    titleKey: "preset_audio_aac_title",
    hintKey: "preset_audio_aac_hint",
  },
  {
    id: "audio-wav",
    titleKey: "preset_audio_wav_title",
    hintKey: "preset_audio_wav_hint",
  },
  {
    id: "audio-flac",
    titleKey: "preset_audio_flac_title",
    hintKey: "preset_audio_flac_hint",
  },
  {
    id: "audio-ogg",
    titleKey: "preset_audio_ogg_title",
    hintKey: "preset_audio_ogg_hint",
  },
  {
    id: "audio-amr",
    titleKey: "preset_audio_amr_title",
    hintKey: "preset_audio_amr_hint",
  },
];

const DOCUMENT_PRESET_CARDS: PresetCard[] = [
  { id: "image-jpg", titleKey: "preset_image_jpg_title", hintKey: "preset_image_jpg_hint" },
  { id: "image-png", titleKey: "preset_image_png_title", hintKey: "preset_image_png_hint" },
  { id: "image-webp", titleKey: "preset_image_webp_title", hintKey: "preset_image_webp_hint" },
  { id: "image-bmp", titleKey: "preset_image_bmp_title", hintKey: "preset_image_bmp_hint" },
  { id: "image-gif", titleKey: "preset_image_gif_title", hintKey: "preset_image_gif_hint" },
  { id: "image-compress", titleKey: "preset_image_compress_title", hintKey: "preset_image_compress_hint" },
  { id: "pdf-image", titleKey: "preset_pdf_image_title", hintKey: "preset_pdf_image_hint" },
  { id: "pdf-txt", titleKey: "preset_pdf_txt_title", hintKey: "preset_pdf_txt_hint" },
  { id: "pdf-compress", titleKey: "preset_pdf_compress_title", hintKey: "preset_pdf_compress_hint" },
  { id: "pdf-split", titleKey: "preset_pdf_split_title", hintKey: "preset_pdf_split_hint" },
  { id: "office-pdf", titleKey: "preset_office_pdf_title", hintKey: "preset_office_pdf_hint" },
];

const ALL_PRESET_CARDS = [...VIDEO_PRESET_CARDS, ...AUDIO_PRESET_CARDS, ...DOCUMENT_PRESET_CARDS];

const PRIMARY_PRESET_IDS = ["mp4-h264", "mp4-copy", "mp4-h265", "mov-h264", "video-concat"];

const CONVERT_MODES: ConvertMode[] = ["video", "audio", "document"];

const DEFAULT_PRESET: Record<ConvertMode, string> = {
  video: "mp4-h264",
  audio: "audio-mp3",
  document: "image-jpg",
};

function collapsedPresetCards(selectedId: string) {
  const primary = PRIMARY_PRESET_IDS.map(
    (id) => VIDEO_PRESET_CARDS.find((card) => card.id === id)!,
  );
  if (PRIMARY_PRESET_IDS.includes(selectedId)) return primary;
  const selected = VIDEO_PRESET_CARDS.find((card) => card.id === selectedId);
  if (!selected) return primary;
  return [...primary.slice(0, 4), selected];
}

const QUALITY_OPTIONS = [
  { id: "original", titleKey: "quality_original", hintKey: "quality_original_hint" },
  { id: "standard", titleKey: "quality_standard", hintKey: "quality_standard_hint" },
  { id: "small", titleKey: "quality_small", hintKey: "quality_small_hint" },
] as const;

const SIZE_OPTIONS = [
  { id: "source", titleKey: "size_original", hintKey: "size_original_hint", width: null as number | null, height: null as number | null },
  { id: "1080", titleKey: "size_1080", hintKey: "size_1080_hint", width: 1920, height: 1080 },
  { id: "720", titleKey: "size_720", hintKey: "size_720_hint", width: 1280, height: 720 },
  { id: "480", titleKey: "size_480", hintKey: "size_480_hint", width: 854, height: 480 },
] as const;

const CODEC_LABELS: Record<string, string> = {
  h264: "H.264",
  hevc: "H.265",
  h265: "H.265",
  vp9: "VP9",
  vp8: "VP8",
  av1: "AV1",
  mpeg4: "MPEG-4",
  mpeg2video: "MPEG-2",
  aac: "AAC",
  opus: "Opus",
  mp3: "MP3",
  ac3: "AC3",
  eac3: "E-AC3",
  flac: "FLAC",
  vorbis: "Vorbis",
};

function fileName(path: string) {
  return path.split(/[/\\]/).pop() ?? path;
}

function shortFolder(path: string) {
  const parts = path.split(/[/\\]/).filter(Boolean);
  if (parts.length <= 2) return path;
  return `…/${parts.slice(-2).join("/")}`;
}

function formatDuration(locale: string, seconds: number | null) {
  if (seconds == null || Number.isNaN(seconds)) return "";
  const total = Math.round(seconds);
  if (total < 60) return t(locale, "duration_seconds", { count: total });
  const mins = Math.floor(total / 60);
  const secs = total % 60;
  if (mins < 60) {
    return secs
      ? t(locale, "duration_min_sec", { mins, secs })
      : t(locale, "duration_minutes", { count: mins });
  }
  const hours = Math.floor(mins / 60);
  const rest = mins % 60;
  return t(locale, "duration_hour_min", { hours, mins: rest });
}

function friendlyCodec(codec: string | null) {
  if (!codec) return "";
  return CODEC_LABELS[codec] ?? codec.toUpperCase();
}

function friendlyContainer(locale: string, container: string | null, path: string) {
  const ext = (fileName(path).split(".").pop() ?? "").toUpperCase();
  const name = (container ?? "").toLowerCase();
  if (name.includes("matroska") || ext === "MKV") return "MKV";
  if (name.includes("webm") || ext === "WEBM") return "WebM";
  if (name.includes("mp3") || ext === "MP3") return "MP3";
  if (name.includes("avi") || ext === "AVI") return "AVI";
  if (ext === "TS" || ext === "M2TS" || name.includes("mpegts")) return ext || "TS";
  if (ext === "MOV") return "MOV";
  if (ext === "M4V") return "M4V";
  if (name.includes("mp4") || name.includes("mov") || ext === "MP4") return "MP4";
  return ext || t(locale, "container_video");
}

function localizedError(locale: string, error: string | null) {
  if (!error) return t(locale, "source_unreadable");
  const translated = t(locale, error);
  return translated !== error ? translated : error;
}

function sourceFormat(locale: string, item: SourceItem) {
  if (item.probing) return t(locale, "source_reading_format");
  if (!item.importable) return localizedError(locale, item.error);
  return [
    friendlyContainer(locale, item.container, item.path),
    friendlyCodec(item.videoCodec) || friendlyCodec(item.audioCodec),
    item.width && item.height ? `${item.width}×${item.height}` : "",
    item.frameRate ? `${Math.round(item.frameRate)} fps` : "",
    formatDuration(locale, item.durationSecs),
  ]
    .filter(Boolean)
    .join(" · ");
}

function sourceFromLabel(locale: string, item: MediaInfo) {
  return [
    friendlyContainer(locale, item.container, item.path),
    friendlyCodec(item.videoCodec) || friendlyCodec(item.audioCodec),
  ]
    .filter(Boolean)
    .join(" · ");
}

function conversionPreview(locale: string, items: SourceItem[], target: string) {
  const importable = items.filter((item) => item.importable);
  if (!importable.length) return t(locale, "conversion_need_source");
  const labels = new Set(importable.map((item) => sourceFromLabel(locale, item)));
  const from = labels.size === 1 ? [...labels][0] : t(locale, "source_kinds", { count: labels.size });
  return `${from}  →  ${target}`;
}

function itemHasDuration(item: SourceItem) {
  return item.importable && item.durationSecs != null && item.durationSecs > 0.05;
}

function isAudioPreset(preset: string) {
  return preset.startsWith("audio-");
}

function isLosslessAudioPreset(preset: string) {
  return preset === "audio-wav" || preset === "audio-flac";
}

function isCopyPreset(preset: string) {
  return preset === "mp4-copy";
}

function shouldShowQuality(preset: string, mode: ConvertMode) {
  if (mode === "document") return false;
  return !isCopyPreset(preset) && !isLosslessAudioPreset(preset);
}

function shouldShowResolution(preset: string, mode: ConvertMode) {
  return mode === "video" && !isCopyPreset(preset) && !isVideoConcatPreset(preset);
}

function isTrimmed(item: SourceItem) {
  if (!itemHasDuration(item)) return false;
  const duration = item.durationSecs ?? 0;
  return (item.trimStartSecs ?? 0) > 0.2 || (item.trimEndSecs != null && duration - item.trimEndSecs > 0.2);
}

function sizeId(config: OutputConfig) {
  const match = SIZE_OPTIONS.find(
    (item) => item.width === (config.maxWidth ?? null) && item.height === (config.maxHeight ?? null),
  );
  return match?.id ?? "source";
}

function qualityId(config: OutputConfig) {
  const value = config.quality ?? "standard";
  return value === "high" ? "original" : value;
}

function emptyConfig(preset = "mp4-h264"): OutputConfig {
  return { preset, quality: "standard" };
}

type WizardSession = {
  sources: SourceItem[];
  config: OutputConfig;
  outputDir: string;
  showAll: boolean;
};

function emptySession(preset: string, outputDir = ""): WizardSession {
  return {
    sources: [],
    config: emptyConfig(preset),
    outputDir,
    showAll: false,
  };
}

function sessionFromSettings(
  saved: ModeSettings | undefined,
  fallbackPreset: string,
  fallbackDir: string,
  mode: ConvertMode,
): WizardSession {
  const raw = saved?.preset || fallbackPreset;
  return {
    sources: [],
    config: {
      preset: mode === "video" ? clampVideoPreset(raw) : raw,
      quality: saved?.quality || "standard",
      maxWidth: saved?.maxWidth ?? null,
      maxHeight: saved?.maxHeight ?? null,
    },
    outputDir: saved?.outputDir || fallbackDir,
    showAll: false,
  };
}

function emptySources(): Record<ConvertMode, WizardSession> {
  return {
    video: emptySession(DEFAULT_PRESET.video),
    audio: emptySession(DEFAULT_PRESET.audio),
    document: emptySession(DEFAULT_PRESET.document),
  };
}

function placeholderSource(path: string): SourceItem {
  return {
    path,
    durationSecs: null,
    container: null,
    videoCodec: null,
    width: null,
    height: null,
    frameRate: null,
    audioCodec: null,
    channels: null,
    importable: false,
    error: null,
    probing: true,
    pageCount: null,
    pageStart: null,
    pageEnd: null,
  };
}

function pickDialogLabels(locale: string, mode: ConvertMode) {
  if (mode === "video") {
    return { title: t(locale, "pick_files_title"), filter: t(locale, "pick_files_filter") };
  }
  return { title: t(locale, "action_pick_files"), filter: t(locale, `segment_${mode}`) };
}

function formatClock(seconds: number) {
  const total = Math.max(0, Math.floor(seconds + 0.5));
  const hours = Math.floor(total / 3600);
  const mins = Math.floor((total % 3600) / 60);
  const secs = total % 60;
  if (hours > 0) {
    return `${hours}:${String(mins).padStart(2, "0")}:${String(secs).padStart(2, "0")}`;
  }
  return `${mins}:${String(secs).padStart(2, "0")}`;
}

function TrimBar({
  active,
  locale,
  duration,
  start,
  end,
  sourcePath,
  container,
  videoCodec,
  audioCodec,
  onChange,
}: {
  active: boolean;
  locale: string;
  duration: number;
  start: number;
  end: number;
  sourcePath: string;
  container: string | null;
  videoCodec: string | null;
  audioCodec: string | null;
  onChange: (start: number, end: number) => void;
}) {
  const trackRef = useRef<HTMLDivElement>(null);
  const videoRef = useRef<HTMLVideoElement>(null);
  const dragRef = useRef<"start" | "end" | "range" | null>(null);
  const rangeOrigin = useRef({ start: 0, end: 0, x: 0 });
  const forcedRef = useRef(false);
  const [playing, setPlaying] = useState(false);
  const [now, setNow] = useState(start);
  const [playable, setPlayable] = useState(false);
  const [preparing, setPreparing] = useState(true);
  const [playbackPath, setPlaybackPath] = useState(sourcePath);
  const mediaUrl = useMemo(() => localMediaUrl(playbackPath), [playbackPath]);

  useEffect(() => {
    let cancelled = false;
    forcedRef.current = false;
    setPlaying(false);
    setPlaybackPath(sourcePath);
    const nativeGuess = /\.(mp4|m4v|mov|webm)$/i.test(sourcePath);
    setPlayable(nativeGuess);
    setPreparing(!nativeGuess);

    void preparePreview({
      path: sourcePath,
      container,
      videoCodec,
      audioCodec,
      force: false,
    })
      .then((path) => {
        if (cancelled) return;
        setPlaybackPath(path);
        setPlayable(true);
        setPreparing(false);
      })
      .catch(() => {
        if (cancelled) return;
        setPlayable(false);
        setPreparing(false);
      });

    return () => {
      cancelled = true;
    };
  }, [sourcePath, container, videoCodec, audioCodec, duration]);

  useEffect(() => {
    if (active) return;
    videoRef.current?.pause();
    setPlaying(false);
  }, [active]);

  function seekTo(time: number) {
    const video = videoRef.current;
    const next = Math.min(Math.max(time, 0), duration);
    if (video) video.currentTime = next;
    setNow(next);
  }

  function apply(nextStart: number, nextEnd: number) {
    const minGap = Math.min(0.2, duration / 20);
    onChange(
      Math.min(nextStart, nextEnd - minGap),
      Math.max(nextEnd, nextStart + minGap),
    );
  }

  function timeAt(clientX: number) {
    const rect = trackRef.current?.getBoundingClientRect();
    if (!rect || rect.width <= 0) return 0;
    const ratio = (clientX - rect.left) / rect.width;
    return Math.min(duration, Math.max(0, ratio * duration));
  }

  function onPointerDown(kind: "start" | "end" | "range", event: PointerEvent<HTMLDivElement>) {
    event.preventDefault();
    event.stopPropagation();
    event.currentTarget.setPointerCapture(event.pointerId);
    dragRef.current = kind;
    rangeOrigin.current = { start, end, x: event.clientX };
    videoRef.current?.pause();
    setPlaying(false);
    if (kind === "start") seekTo(start);
    if (kind === "end") seekTo(end);
  }

  function onPointerMove(event: PointerEvent<HTMLDivElement>) {
    const kind = dragRef.current;
    if (!kind) return;
    if (kind === "start") {
      const next = timeAt(event.clientX);
      apply(next, end);
      seekTo(next);
      return;
    }
    if (kind === "end") {
      const next = timeAt(event.clientX);
      apply(start, next);
      seekTo(next);
      return;
    }
    const rect = trackRef.current?.getBoundingClientRect();
    if (!rect || rect.width <= 0) return;
    const delta = ((event.clientX - rangeOrigin.current.x) / rect.width) * duration;
    const span = rangeOrigin.current.end - rangeOrigin.current.start;
    const nextStart = Math.min(Math.max(0, rangeOrigin.current.start + delta), duration - span);
    apply(nextStart, nextStart + span);
    seekTo(nextStart);
  }

  function onPointerUp() {
    dragRef.current = null;
  }

  function onTrackClick(event: PointerEvent<HTMLDivElement>) {
    if (event.target !== trackRef.current) return;
    seekTo(timeAt(event.clientX));
  }

  async function togglePlay() {
    const video = videoRef.current;
    if (!video || !playable) return;
    if (playing) {
      video.pause();
      setPlaying(false);
      return;
    }
    if (video.currentTime < start || video.currentTime >= end - 0.05) {
      video.currentTime = start;
      setNow(start);
    }
    try {
      await video.play();
      setPlaying(true);
    } catch {
      setPlayable(false);
    }
  }

  const left = (start / duration) * 100;
  const width = ((end - start) / duration) * 100;
  const playhead = (now / duration) * 100;

  return (
    <div className="trim">
      <div className="trim-player">
        {preparing ? (
          <p>{t(locale, "trim_preparing")}</p>
        ) : mediaUrl && playable ? (
          <video
            key={playbackPath}
            ref={videoRef}
            src={mediaUrl}
            preload="metadata"
            playsInline
            onLoadedMetadata={() => {
              const video = videoRef.current;
              if (!video) return;
              video.currentTime = start;
              setNow(start);
            }}
            onPlay={() => setPlaying(true)}
            onPause={() => setPlaying(false)}
            onError={() => {
              if (forcedRef.current || playbackPath !== sourcePath) {
                setPlayable(false);
                return;
              }
              forcedRef.current = true;
              setPreparing(true);
              setPlayable(false);
              void preparePreview({
                path: sourcePath,
                container,
                videoCodec,
                audioCodec,
                force: true,
              })
                .then((path) => {
                  setPlaybackPath(path);
                  setPlayable(true);
                  setPreparing(false);
                })
                .catch(() => {
                  setPlayable(false);
                  setPreparing(false);
                });
            }}
            onTimeUpdate={() => {
              const video = videoRef.current;
              if (!video) return;
              const time = video.currentTime;
              setNow(time);
              if (time >= end - 0.04) {
                video.pause();
                video.currentTime = start;
                setNow(start);
                setPlaying(false);
              }
            }}
          />
        ) : (
          <p>{t(locale, "trim_no_preview")}</p>
        )}
      </div>
      <div className="trim-controls">
        <button type="button" className="ghost" onClick={() => void togglePlay()} disabled={!playable || preparing}>
          {playing ? t(locale, "trim_pause") : t(locale, "trim_play_selection")}
        </button>
        <button type="button" className="chip" onClick={() => apply(now, end)}>
          <strong>{t(locale, "trim_set_start")}</strong>
        </button>
        <button type="button" className="chip" onClick={() => apply(start, now)}>
          <strong>{t(locale, "trim_set_end")}</strong>
        </button>
      </div>
      <div ref={trackRef} className="trim-track" onPointerDown={onTrackClick}>
        <div
          className="trim-range"
          style={{ left: `${left}%`, width: `${width}%` }}
          onPointerDown={(event) => onPointerDown("range", event)}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
          onPointerCancel={onPointerUp}
        />
        <div className="trim-playhead" style={{ left: `${playhead}%` }} />
        <div
          className="trim-handle start"
          style={{ left: `${left}%` }}
          onPointerDown={(event) => onPointerDown("start", event)}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
          onPointerCancel={onPointerUp}
        />
        <div
          className="trim-handle end"
          style={{ left: `${left + width}%` }}
          onPointerDown={(event) => onPointerDown("end", event)}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
          onPointerCancel={onPointerUp}
        />
      </div>
      <div className="trim-times">
        <span>{t(locale, "trim_start", { time: formatClock(start) })}</span>
        <span>{t(locale, "trim_current", { time: formatClock(now) })}</span>
        <span>{t(locale, "trim_end", { time: formatClock(end) })}</span>
        <span>{t(locale, "trim_keep", { time: formatDuration(locale, end - start) })}</span>
      </div>
    </div>
  );
}

export type ConvertPageProps = {
  active: boolean;
  locale: string;
  mode: ConvertMode;
  jobs: Job[];
  dragging: boolean;
  onModeChange: (mode: ConvertMode) => void;
  onNotice: (message: string | null) => void;
  onDraggingChange: (dragging: boolean) => void;
  onEnqueued: (preset: string) => void;
};

export default function ConvertPage({
  active,
  locale,
  mode,
  jobs,
  dragging,
  onModeChange,
  onNotice,
  onDraggingChange,
  onEnqueued,
}: ConvertPageProps) {
  const [sessions, setSessions] = useState<Record<ConvertMode, WizardSession>>(emptySources);
  const [busy, setBusy] = useState(false);
  const [selectedPath, setSelectedPath] = useState<string | null>(null);
  const [prefsReady, setPrefsReady] = useState(false);
  const [dragFrom, setDragFrom] = useState<number | null>(null);
  const sessionsRef = useRef(sessions);
  sessionsRef.current = sessions;
  const activeRef = useRef(active);
  activeRef.current = active;
  const modeRef = useRef(mode);
  modeRef.current = mode;
  const localeRef = useRef(locale);
  localeRef.current = locale;

  const session = sessions[mode];
  const { sources, config, outputDir, showAll: showAllFormats } = session;

  const runningIds = useMemo(
    () => new Set(jobs.filter((job) => job.status === "running").map((job) => job.sourcePath)),
    [jobs],
  );
  const transcoding = jobs.some((job) => job.status === "running" || job.status === "queued");
  const importableCount = sources.filter((item) => item.importable).length;
  const probing = sources.some((item) => item.probing);
  const readyToStart = canStart(
    importableCount,
    probing,
    transcoding || busy,
    Boolean(outputDir),
    config.preset,
    sources.length,
  );
  const previewSource = useMemo(() => {
    const withDuration = sources.filter(itemHasDuration);
    return withDuration.find((item) => item.path === selectedPath) ?? withDuration[0] ?? null;
  }, [sources, selectedPath]);
  const timelineDuration = previewSource?.durationSecs ?? null;

  useEffect(() => {
    if (!sources.some((item) => item.path === selectedPath)) {
      const next = sources.find(itemHasDuration);
      setSelectedPath(next?.path ?? null);
    }
  }, [sources, selectedPath]);

  useEffect(() => {
    if (!prefsReady || !outputDir) return;
    void saveSessionSettings({
      [mode]: {
        outputDir,
        preset: config.preset,
        quality: config.quality ?? "standard",
        maxWidth: config.maxWidth ?? null,
        maxHeight: config.maxHeight ?? null,
      },
    });
  }, [prefsReady, mode, outputDir, config.preset, config.quality, config.maxWidth, config.maxHeight]);

  const patchSession = useCallback((target: ConvertMode, patch: (current: WizardSession) => WizardSession) => {
    setSessions((all) => {
      const next = { ...all, [target]: patch(all[target]) };
      sessionsRef.current = next;
      return next;
    });
  }, []);

  const addPaths = useCallback(async (paths: string[]) => {
    const modeNow = modeRef.current;
    const current = sessionsRef.current[modeNow];
    const unique = paths.filter((path) => !current.sources.some((item) => item.path === path));
    if (!unique.length) return;

    const allowed = unique.filter((path) => pathAllowedForMode(path, modeNow));
    if (allowed.length !== unique.length) {
      onNotice(t(localeRef.current, "notice_wrong_mode"));
    }
    if (!allowed.length) return;

    if (modeNow === "document") {
      const existingNames = current.sources.map((item) => item.path);
      const incoming: string[] = [];
      let mixed = false;
      for (const path of allowed) {
        if (!sameDocumentKind([...existingNames, ...incoming], path)) {
          mixed = true;
          break;
        }
        incoming.push(path);
      }
      if (mixed) {
        onNotice(t(localeRef.current, "notice_document_mixed"));
        return;
      }
    }

    let nextConfig = current.config;
    if (modeNow === "document" && current.sources.length === 0) {
      const kind = documentSourceKind(allowed[0]);
      if (kind && kind !== "excel") {
        nextConfig = { ...current.config, preset: defaultDocumentPreset(kind) };
      }
    }

    const nextSources = [...current.sources, ...allowed.map(placeholderSource)];
    const nextSession = { ...current, sources: nextSources, config: nextConfig };
    sessionsRef.current = { ...sessionsRef.current, [modeNow]: nextSession };
    setSessions(sessionsRef.current);

    await mapWithConcurrency(allowed, PROBE_CONCURRENCY, async (path) => {
      try {
        const info = await probeMedia(path);
        patchSession(modeNow, (session) => ({
          ...session,
          sources: session.sources.map((item) =>
            item.path === path
              ? {
                  ...info,
                  probing: false,
                  trimStartSecs: 0,
                  trimEndSecs: info.durationSecs,
                }
              : item,
          ),
        }));
      } catch (err) {
        patchSession(modeNow, (session) => ({
          ...session,
          sources: session.sources.map((item) =>
            item.path === path
              ? { ...item, probing: false, importable: false, error: String(err) }
              : item,
          ),
        }));
      }
    });
  }, [onNotice, patchSession]);

  useEffect(() => {
    void (async () => {
      let fallbackDir = "";
      try {
        fallbackDir = await getOrInitOutputDir();
      } catch (err) {
        onNotice(String(err));
      }
      try {
        const saved = await loadSessionSettings();
        const videoSaved = saved.video?.preset ? saved.video : {
          outputDir: saved.outputDir,
          preset: saved.preset,
          quality: saved.quality,
          maxWidth: saved.maxWidth,
          maxHeight: saved.maxHeight,
        };
        setSessions({
          video: sessionFromSettings(videoSaved, DEFAULT_PRESET.video, fallbackDir, "video"),
          audio: sessionFromSettings(saved.audio, DEFAULT_PRESET.audio, fallbackDir, "audio"),
          document: sessionFromSettings(saved.document, DEFAULT_PRESET.document, fallbackDir, "document"),
        });
      } catch {
        setSessions({
          video: emptySession(DEFAULT_PRESET.video, fallbackDir),
          audio: emptySession(DEFAULT_PRESET.audio, fallbackDir),
          document: emptySession(DEFAULT_PRESET.document, fallbackDir),
        });
      }
      setPrefsReady(true);
    })();
  }, [onNotice]);

  useEffect(() => {
    let unlistenDrop: Promise<() => void> | null = null;
    try {
      unlistenDrop = getCurrentWebview().onDragDropEvent((event) => {
        if (event.payload.type === "over") {
          if (activeRef.current) onDraggingChange(true);
          return;
        }
        if (event.payload.type === "leave") {
          onDraggingChange(false);
          return;
        }
        if (event.payload.type === "drop") {
          onDraggingChange(false);
          if (!activeRef.current) return;
          void addPaths(event.payload.paths);
        }
      });
    } catch {
      // Browser preview outside the Tauri webview has no native window.
    }
    return () => {
      void unlistenDrop?.then((fn) => fn());
    };
  }, [addPaths, onDraggingChange]);

  async function onPickFiles() {
    try {
      const paths = await pickFiles(mode, pickDialogLabels(locale, mode));
      await addPaths(paths);
    } catch (err) {
      onNotice(String(err));
    }
  }

  async function onPickOutputDir() {
    try {
      const dir = await pickOutputDir(t(locale, "pick_output_title"));
      if (dir) patchSession(mode, (current) => ({ ...current, outputDir: dir }));
    } catch (err) {
      onNotice(String(err));
    }
  }

  function reorderSources(from: number, to: number) {
    if (from === to || from < 0 || to < 0) return;
    patchSession(mode, (current) => {
      if (to >= current.sources.length) return current;
      const next = [...current.sources];
      const [item] = next.splice(from, 1);
      if (!item) return current;
      next.splice(to, 0, item);
      return { ...current, sources: next };
    });
  }

  function removeSource(path: string) {
    if (runningIds.has(path)) {
      onNotice(t(locale, "notice_cannot_remove_running"));
      return;
    }
    patchSession(mode, (current) => ({
      ...current,
      sources: current.sources.filter((item) => item.path !== path),
    }));
  }

  function applyPreset(preset: string) {
    patchSession(mode, (current) => ({
      ...current,
      config: {
        preset,
        quality: current.config.quality ?? "standard",
        maxWidth:
          isCopyPreset(preset) || isVideoConcatPreset(preset) || mode === "audio" || mode === "document"
            ? null
            : current.config.maxWidth,
        maxHeight:
          isCopyPreset(preset) || isVideoConcatPreset(preset) || mode === "audio" || mode === "document"
            ? null
            : current.config.maxHeight,
      },
    }));
  }

  function applyConfig(next: OutputConfig) {
    patchSession(mode, (current) => ({ ...current, config: next }));
  }

  async function onStart() {
    if (!readyToStart) return;
    const importable = sources.filter((item) => item.importable);
    if (!importable.length) {
      onNotice(t(locale, "notice_need_source"));
      return;
    }
    if (!outputDir) {
      onNotice(t(locale, "notice_need_output"));
      return;
    }
    setBusy(true);
    onNotice(null);
    try {
      const report = await enqueueJobs(
        importable.map(({ probing: _probing, ...info }) => info),
        config,
        outputDir,
      );
      patchSession(mode, (current) => ({ ...current, sources: [] }));
      setSelectedPath(null);
      onEnqueued(config.preset);
      if (report.skipped.length) {
        onNotice(
          t(locale, "notice_skipped", {
            count: report.skipped.length,
            list: report.skipped
              .map((item) => `${fileName(item.path)}（${item.reason}）`)
              .join("；"),
          }),
        );
      }
    } catch (err) {
      onNotice(String(err));
    } finally {
      setBusy(false);
    }
  }

  const selectedCard = ALL_PRESET_CARDS.find((card) => card.id === config.preset);
  const selectedTitle = selectedCard
    ? t(locale, selectedCard.titleKey)
    : t(locale, mode === "audio" ? "preset_audio_mp3_title" : "preset_mp4_h264_title");
  const shownPresets =
    mode === "audio"
      ? AUDIO_PRESET_CARDS
      : mode === "document"
        ? []
        : showAllFormats
          ? VIDEO_PRESET_CARDS
          : collapsedPresetCards(config.preset);
  const audioOnly = mode === "audio" || isAudioPreset(config.preset);
  const showQuality = shouldShowQuality(config.preset, mode);
  const showResolution = shouldShowResolution(config.preset, mode);
  const showTrim = Boolean(allowsTrim(config.preset) && timelineDuration && previewSource);
  const qualityLabel =
    t(
      locale,
      QUALITY_OPTIONS.find((item) => item.id === qualityId(config))?.titleKey ?? "quality_standard",
    );
  const sizeLabel = t(
    locale,
    SIZE_OPTIONS.find((item) => item.id === sizeId(config))?.titleKey ?? "size_original",
  );
  const copyOnly = isCopyPreset(config.preset);
  const concatOnly = isVideoConcatPreset(config.preset);
  const trimmedCount = sources.filter(isTrimmed).length;
  const trimLabel = trimmedCount
    ? trimmedCount === 1 && previewSource && isTrimmed(previewSource)
      ? t(locale, "trim_clock", {
          start: formatClock(previewSource.trimStartSecs ?? 0),
          end: formatClock(previewSource.trimEndSecs ?? timelineDuration ?? 0),
        })
      : t(locale, "files_trimmed", { count: trimmedCount })
    : "";

  let dockSummary = t(locale, "dock_need_source");
  if (concatOnly && importableCount < 2) {
    dockSummary = t(locale, "dock_concat_need_two");
  } else if (importableCount) {
    if (audioOnly || mode === "document" || concatOnly) {
      dockSummary = t(locale, "dock_convert_files", {
        count: importableCount,
        target: selectedTitle,
        quality: qualityLabel,
      });
    } else if (copyOnly) {
      dockSummary = t(locale, "dock_convert_copy", {
        count: importableCount,
        target: selectedCard ? t(locale, selectedCard.titleKey) : t(locale, "preset_mp4_copy_title"),
      });
    } else {
      dockSummary = t(locale, "dock_convert_videos", {
        count: importableCount,
        target: selectedTitle,
        quality: qualityLabel,
        size: sizeLabel,
      });
    }
    dockSummary += trimLabel;
  }

  return (
    <>
      <div className="chips convert-segments">
        {CONVERT_MODES.map((id) => (
          <button
            key={id}
            type="button"
            className={mode === id ? "chip on" : "chip"}
            onClick={() => onModeChange(id)}
          >
            <strong>{t(locale, `segment_${id}`)}</strong>
          </button>
        ))}
      </div>
      <div className="convert-grid">
        <section className="stage">
          <div className="stage-head">
            <span className="step">1</span>
            <div>
              <h2>{t(locale, "source_title")}</h2>
              <p>{t(locale, "source_subtitle")}</p>
            </div>
          </div>
          <button
            type="button"
            className={sources.length ? "dropzone compact" : "dropzone"}
            onClick={() => void onPickFiles()}
          >
            <div className="drop-art" aria-hidden="true">
              <svg width="56" height="56" viewBox="0 0 56 56" fill="none">
                <rect x="10" y="14" width="36" height="28" rx="4" stroke="#2c241c" strokeWidth="2" />
                <path d="M18 36l7-9 5 6 3-3 9 6H18z" fill="#2c241c" />
                <rect x="34" y="10" width="12" height="8" rx="2" fill="#c45a2a" />
              </svg>
            </div>
            <div>
              <strong>{dragging ? t(locale, "dropzone_dragging") : t(locale, "dropzone_title")}</strong>
              <p>{t(locale, "dropzone_hint")}</p>
            </div>
            <span className="ghost">{t(locale, "action_pick_files")}</span>
          </button>
          {sources.length > 0 ? (
            <ul className="files">
              {sources.map((item, index) => (
                <li
                  key={item.path}
                  draggable={concatOnly}
                  className={[
                    item.importable || item.probing ? "" : "bad",
                    item.path === selectedPath ? "on" : "",
                    concatOnly ? "sortable" : "",
                    concatOnly && dragFrom === index ? "dragging" : "",
                  ]
                    .filter(Boolean)
                    .join(" ")}
                  onDragStart={(event) => {
                    if (!concatOnly) return;
                    event.dataTransfer.effectAllowed = "move";
                    event.dataTransfer.setData("text/plain", String(index));
                    setDragFrom(index);
                  }}
                  onDragOver={(event) => {
                    if (!concatOnly) return;
                    event.preventDefault();
                    event.dataTransfer.dropEffect = "move";
                  }}
                  onDrop={(event) => {
                    if (!concatOnly) return;
                    event.preventDefault();
                    const from = Number(event.dataTransfer.getData("text/plain"));
                    if (Number.isFinite(from)) reorderSources(from, index);
                    setDragFrom(null);
                  }}
                  onDragEnd={() => setDragFrom(null)}
                  onClick={() => {
                    if (allowsTrim(config.preset) && itemHasDuration(item)) setSelectedPath(item.path);
                  }}
                >
                  <div>
                    <strong>{fileName(item.path)}</strong>
                    <p>
                      {sourceFormat(locale, item)}
                      {isTrimmed(item) ? ` · ${t(locale, "source_trimmed")}` : ""}
                    </p>
                  </div>
                  <button
                    type="button"
                    className="text"
                    onClick={(event) => {
                      event.stopPropagation();
                      removeSource(item.path);
                    }}
                    disabled={runningIds.has(item.path)}
                  >
                    {t(locale, "action_remove")}
                  </button>
                </li>
              ))}
            </ul>
          ) : null}
          {showTrim && timelineDuration && previewSource ? (
            <div className="trim-wrap">
              <div className="trim-head">
                <div>
                  <p className="tune-label">{t(locale, "trim_title")}</p>
                  <p className="tune-desc">
                    {sources.filter(itemHasDuration).length > 1
                      ? t(locale, "trim_hint_multi", { name: fileName(previewSource.path) })
                      : t(locale, "trim_hint")}
                  </p>
                </div>
                <button
                  type="button"
                  className="text"
                  onClick={() =>
                    patchSession(mode, (current) => ({
                      ...current,
                      sources: current.sources.map((item) =>
                        item.path === previewSource.path
                          ? { ...item, trimStartSecs: 0, trimEndSecs: timelineDuration }
                          : item,
                      ),
                    }))
                  }
                >
                  {t(locale, "trim_reset")}
                </button>
              </div>
              <TrimBar
                active={active}
                locale={locale}
                duration={timelineDuration}
                start={Math.min(previewSource.trimStartSecs ?? 0, timelineDuration)}
                end={Math.min(previewSource.trimEndSecs ?? timelineDuration, timelineDuration)}
                sourcePath={previewSource.path}
                container={previewSource.container}
                videoCodec={previewSource.videoCodec}
                audioCodec={previewSource.audioCodec}
                onChange={(nextStart, nextEnd) =>
                  patchSession(mode, (current) => ({
                    ...current,
                    sources: current.sources.map((item) =>
                      item.path === previewSource.path
                        ? { ...item, trimStartSecs: nextStart, trimEndSecs: nextEnd }
                        : item,
                    ),
                  }))
                }
              />
            </div>
          ) : null}
        </section>

        <div className="convert-right">
          <section className="stage">
            <div className="stage-head">
              <span className="step">2</span>
              <div>
                <h2>{t(locale, "convert_title")}</h2>
                <p>{conversionPreview(locale, sources, selectedTitle)}</p>
              </div>
            </div>
            {shownPresets.length > 0 ? (
              <div className="presets">
                {shownPresets.map((card) => (
                  <button
                    key={card.id}
                    type="button"
                    className={config.preset === card.id ? "preset on" : "preset"}
                    onClick={() => applyPreset(card.id)}
                  >
                    <span className="preset-title">
                      {t(locale, card.titleKey)}
                      {card.badgeKey ? <em>{t(locale, card.badgeKey)}</em> : null}
                    </span>
                    <span>{t(locale, card.hintKey)}</span>
                  </button>
                ))}
                {mode === "video" ? (
                  <button
                    type="button"
                    className="preset more"
                    onClick={() =>
                      patchSession(mode, (current) => ({ ...current, showAll: !current.showAll }))
                    }
                  >
                    <span className="preset-title">
                      {t(locale, showAllFormats ? "action_collapse" : "action_more")}
                    </span>
                    <span>{t(locale, showAllFormats ? "formats_less_hint" : "formats_more_hint")}</span>
                  </button>
                ) : null}
              </div>
            ) : null}
            {copyOnly ? (
              <p className="tune-intro">{t(locale, "hint_copy_mp4")}</p>
            ) : showQuality || showResolution ? (
              <div className="tune">
                {showResolution ? <p className="tune-intro">{t(locale, "tune_intro")}</p> : null}
                <div className="tune-grid">
                  {showQuality ? (
                    <div>
                      <p className="tune-label">{t(locale, audioOnly ? "quality_audio_title" : "quality_title")}</p>
                      <p className="tune-desc">
                        {t(locale, audioOnly ? "quality_audio_desc" : "quality_desc")}
                      </p>
                      <div className="chips">
                        {QUALITY_OPTIONS.map((item) => (
                          <button
                            key={item.id}
                            type="button"
                            className={qualityId(config) === item.id ? "chip on" : "chip"}
                            onClick={() => applyConfig({ ...config, quality: item.id })}
                          >
                            <strong>{t(locale, item.titleKey)}</strong>
                            <span>{t(locale, item.hintKey)}</span>
                          </button>
                        ))}
                      </div>
                    </div>
                  ) : null}
                  {showResolution ? (
                    <div>
                      <p className="tune-label">{t(locale, "resolution_title")}</p>
                      <p className="tune-desc">{t(locale, "resolution_desc")}</p>
                      <div className="chips">
                        {SIZE_OPTIONS.map((item) => (
                          <button
                            key={item.id}
                            type="button"
                            className={sizeId(config) === item.id ? "chip on" : "chip"}
                            onClick={() =>
                              applyConfig({
                                ...config,
                                maxWidth: item.width,
                                maxHeight: item.height,
                              })
                            }
                          >
                            <strong>{t(locale, item.titleKey)}</strong>
                            <span>{t(locale, item.hintKey)}</span>
                          </button>
                        ))}
                      </div>
                    </div>
                  ) : null}
                </div>
              </div>
            ) : null}
          </section>

          <section className="save">
            <div>
              <h2>{t(locale, "output_title")}</h2>
              <p>{outputDir ? shortFolder(outputDir) : t(locale, "output_preparing")}</p>
            </div>
            <button type="button" className="ghost" onClick={() => void onPickOutputDir()}>
              {t(locale, "output_change")}
            </button>
          </section>
        </div>
      </div>

      <footer className="dock">
        <p>{dockSummary}</p>
        <button type="button" className="primary" onClick={() => void onStart()} disabled={!readyToStart}>
          {busy
            ? t(locale, "joining_queue")
            : transcoding
              ? t(locale, "converting")
              : t(locale, mode === "video" ? "start_transcode" : "start_convert")}
        </button>
      </footer>
    </>
  );
}
