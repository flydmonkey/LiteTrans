import { useCallback, useEffect, useMemo, useRef, useState, type PointerEvent } from "react";
import { listen } from "@tauri-apps/api/event";
import { getCurrentWebview } from "@tauri-apps/api/webview";
import { openPath, revealItemInDir } from "@tauri-apps/plugin-opener";
import {
  cancelJob,
  clearFinishedJobs,
  enqueueJobs,
  getOrInitOutputDir,
  loadSessionSettings,
  localMediaUrl,
  pickFiles,
  preparePreview,
  pickOutputDir,
  probeMedia,
  retryJob,
  saveSessionSettings,
} from "./api";
import type { Job, MediaInfo, OutputConfig, SourceItem } from "./types";
import "./App.css";

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

const PRESET_CARDS: Array<{
  id: string;
  title: string;
  hint: string;
  badge?: string;
  kind?: "audio" | "video";
}> = [
  {
    id: "mp4-h264",
    title: "MP4 · H.264",
    hint: "几乎所有设备都能打开",
    badge: "常用",
  },
  {
    id: "mp4-copy",
    title: "MP4 · 不重编码",
    hint: "只换外壳，速度最快",
    badge: "最快",
  },
  {
    id: "mp4-h265",
    title: "MP4 · H.265",
    hint: "同样是 MP4，编码更新",
  },
  {
    id: "mov-h264",
    title: "MOV · H.264",
    hint: "苹果设备、剪辑软件",
  },
  {
    id: "mkv-copy-friendly",
    title: "MKV · H.264",
    hint: "适合封装保存",
  },
  {
    id: "mkv-h265",
    title: "MKV · H.265",
    hint: "适合长期存档",
  },
  {
    id: "webm-vp9",
    title: "WebM · VP9",
    hint: "网页常用，会比 MP4 慢一点",
  },
  {
    id: "avi-mpeg4",
    title: "AVI · MPEG-4",
    hint: "旧电脑和投影",
  },
  {
    id: "gif",
    title: "GIF",
    hint: "短视频转成动图",
  },
  {
    id: "audio-mp3",
    title: "MP3",
    hint: "只导出音频",
    kind: "audio",
  },
  {
    id: "audio-aac",
    title: "M4A · AAC",
    hint: "只导出音频",
    kind: "audio",
  },
];

const PRIMARY_PRESET_IDS = ["mp4-h264", "mp4-copy", "mp4-h265", "mov-h264"];

function collapsedPresetCards(selectedId: string) {
  const primary = PRIMARY_PRESET_IDS.map(
    (id) => PRESET_CARDS.find((card) => card.id === id)!,
  );
  if (PRIMARY_PRESET_IDS.includes(selectedId)) return primary;
  const selected = PRESET_CARDS.find((card) => card.id === selectedId);
  if (!selected) return primary;
  return [...primary.slice(0, 3), selected];
}

const QUALITY_OPTIONS = [
  { id: "original", title: "原画", hint: "尽量保留细节" },
  { id: "standard", title: "标准", hint: "一般观看够用" },
  { id: "small", title: "节省体积", hint: "文件更小，会糊一点" },
] as const;

const SIZE_OPTIONS = [
  { id: "source", title: "原尺寸", hint: "不缩小画面", width: null as number | null, height: null as number | null },
  { id: "1080", title: "1080p", hint: "全高清", width: 1920, height: 1080 },
  { id: "720", title: "720p", hint: "高清", width: 1280, height: 720 },
  { id: "480", title: "480p", hint: "更小画面", width: 854, height: 480 },
] as const;

const STATUS_LABEL: Record<Job["status"], string> = {
  queued: "排队中",
  running: "正在转码",
  completed: "已完成",
  failed: "出错了",
  cancelled: "已取消",
};

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

function formatDuration(seconds: number | null) {
  if (seconds == null || Number.isNaN(seconds)) return "";
  const total = Math.round(seconds);
  if (total < 60) return `${total} 秒`;
  const mins = Math.floor(total / 60);
  const secs = total % 60;
  if (mins < 60) return secs ? `${mins} 分 ${secs} 秒` : `${mins} 分钟`;
  const hours = Math.floor(mins / 60);
  const rest = mins % 60;
  return `${hours} 小时 ${rest} 分`;
}

function friendlyCodec(codec: string | null) {
  if (!codec) return "";
  return CODEC_LABELS[codec] ?? codec.toUpperCase();
}

function friendlyContainer(container: string | null, path: string) {
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
  return ext || "视频";
}

function sourceFormat(item: SourceItem) {
  if (item.probing) return "正在读取格式…";
  if (!item.importable) return item.error ?? "这个文件打不开";
  return [
    friendlyContainer(item.container, item.path),
    friendlyCodec(item.videoCodec) || friendlyCodec(item.audioCodec),
    item.width && item.height ? `${item.width}×${item.height}` : "",
    item.frameRate ? `${Math.round(item.frameRate)} fps` : "",
    formatDuration(item.durationSecs),
  ]
    .filter(Boolean)
    .join(" · ");
}

function sourceFromLabel(item: MediaInfo) {
  return [
    friendlyContainer(item.container, item.path),
    friendlyCodec(item.videoCodec) || friendlyCodec(item.audioCodec),
  ]
    .filter(Boolean)
    .join(" · ");
}

function conversionPreview(items: SourceItem[], target: string) {
  const importable = items.filter((item) => item.importable);
  if (!importable.length) return "先添加源视频，再选要转成的格式";
  const labels = new Set(importable.map(sourceFromLabel));
  const from = labels.size === 1 ? [...labels][0] : `${labels.size} 种源格式`;
  return `${from}  →  ${target}`;
}

function jobTargetTitle(job: Job) {
  return PRESET_CARDS.find((card) => card.id === job.config.preset)?.title ?? job.config.preset;
}

function itemHasDuration(item: SourceItem) {
  return item.importable && item.durationSecs != null && item.durationSecs > 0.05;
}

function isAudioPreset(preset: string) {
  return preset === "audio-mp3" || preset === "audio-aac";
}

function isCopyPreset(preset: string) {
  return preset === "mp4-copy";
}

function isTrimmed(item: SourceItem) {
  if (!itemHasDuration(item)) return false;
  const duration = item.durationSecs ?? 0;
  return (item.trimStartSecs ?? 0) > 0.2 || (item.trimEndSecs != null && duration - item.trimEndSecs > 0.2);
}

function etaLabel(progress: number, startedAt: number | undefined) {
  if (startedAt == null || progress < 3) return "";
  const elapsed = (Date.now() - startedAt) / 1000;
  const remaining = (elapsed * (100 - progress)) / progress;
  if (!Number.isFinite(remaining)) return "";
  if (remaining < 12) return "马上好";
  return `大约还要 ${formatDuration(remaining)}`;
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
  duration,
  start,
  end,
  sourcePath,
  container,
  videoCodec,
  audioCodec,
  onChange,
}: {
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
          <p>正在做成可播放预览，稍等一下</p>
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
              const t = video.currentTime;
              setNow(t);
              if (t >= end - 0.04) {
                video.pause();
                video.currentTime = start;
                setNow(start);
                setPlaying(false);
              }
            }}
          />
        ) : (
          <p>这个格式没法在这里播放，仍可拖时间轴裁剪</p>
        )}
      </div>
      <div className="trim-controls">
        <button type="button" className="ghost" onClick={() => void togglePlay()} disabled={!playable || preparing}>
          {playing ? "暂停" : "播放选中段"}
        </button>
        <button type="button" className="chip" onClick={() => apply(now, end)}>
          <strong>设为开始</strong>
        </button>
        <button type="button" className="chip" onClick={() => apply(start, now)}>
          <strong>设为结束</strong>
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
        <span>开始 {formatClock(start)}</span>
        <span>当前 {formatClock(now)}</span>
        <span>结束 {formatClock(end)}</span>
        <span>保留 {formatDuration(end - start)}</span>
      </div>
    </div>
  );
}

export default function App() {
  const [sources, setSources] = useState<SourceItem[]>([]);
  const [config, setConfig] = useState<OutputConfig>(emptyConfig());
  const [outputDir, setOutputDir] = useState("");
  const [jobs, setJobs] = useState<Job[]>([]);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [dragging, setDragging] = useState(false);
  const [selectedPath, setSelectedPath] = useState<string | null>(null);
  const [prefsReady, setPrefsReady] = useState(false);
  const [showAllFormats, setShowAllFormats] = useState(false);
  const startedAtRef = useRef(new Map<string, number>());
  const sourcesRef = useRef(sources);
  sourcesRef.current = sources;

  const runningIds = useMemo(
    () => new Set(jobs.filter((job) => job.status === "running").map((job) => job.sourcePath)),
    [jobs],
  );
  const transcoding = jobs.some((job) => job.status === "running" || job.status === "queued");
  const importableCount = sources.filter((item) => item.importable).length;
  const readyToStart = importableCount > 0 && Boolean(outputDir) && !busy && !transcoding;
  const previewSource = useMemo(() => {
    const withDuration = sources.filter(itemHasDuration);
    return withDuration.find((item) => item.path === selectedPath) ?? withDuration[0] ?? null;
  }, [sources, selectedPath]);
  const timelineDuration = previewSource?.durationSecs ?? null;
  const finishedCount = jobs.filter(
    (job) => job.status === "completed" || job.status === "failed" || job.status === "cancelled",
  ).length;

  useEffect(() => {
    if (!sources.some((item) => item.path === selectedPath)) {
      const next = sources.find(itemHasDuration);
      setSelectedPath(next?.path ?? null);
    }
  }, [sources, selectedPath]);

  useEffect(() => {
    if (!prefsReady || !outputDir) return;
    void saveSessionSettings({
      outputDir,
      preset: config.preset,
      quality: config.quality ?? "standard",
      maxWidth: config.maxWidth ?? null,
      maxHeight: config.maxHeight ?? null,
    });
  }, [prefsReady, outputDir, config.preset, config.quality, config.maxWidth, config.maxHeight]);

  const addPaths = useCallback(async (paths: string[]) => {
    const unique = paths.filter((path) => !sourcesRef.current.some((item) => item.path === path));
    if (!unique.length) return;
    sourcesRef.current = [
      ...sourcesRef.current,
      ...unique.map((path) => ({
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
      })),
    ];
    setSources(sourcesRef.current);
    await mapWithConcurrency(unique, PROBE_CONCURRENCY, async (path) => {
      try {
        const info = await probeMedia(path);
        setSources((current) =>
          current.map((item) =>
            item.path === path
              ? {
                  ...info,
                  probing: false,
                  trimStartSecs: 0,
                  trimEndSecs: info.durationSecs,
                }
              : item,
          ),
        );
      } catch (err) {
        setSources((current) =>
          current.map((item) =>
            item.path === path
              ? { ...item, probing: false, importable: false, error: String(err) }
              : item,
          ),
        );
      }
    });
  }, []);

  useEffect(() => {
    void (async () => {
      try {
        const saved = await loadSessionSettings();
        if (saved.preset || saved.quality || saved.maxWidth != null || saved.maxHeight != null) {
          setConfig({
            preset: saved.preset || "mp4-h264",
            quality: saved.quality || "standard",
            maxWidth: saved.maxWidth ?? null,
            maxHeight: saved.maxHeight ?? null,
          });
        }
      } catch {
        // First launch has no saved prefs.
      }
      try {
        setOutputDir(await getOrInitOutputDir());
      } catch (err) {
        setNotice(String(err));
      }
      setPrefsReady(true);
    })();

    const unlistenJobs = listen<Job[]>("jobs-changed", (event) => {
      for (const job of event.payload) {
        if (job.status !== "running") startedAtRef.current.delete(job.id);
      }
      setJobs(event.payload);
    });
    const unlistenProgress = listen<{ id: string; percent: number }>("job-progress", (event) => {
      if (!startedAtRef.current.has(event.payload.id)) {
        startedAtRef.current.set(event.payload.id, Date.now());
      }
      setJobs((current) =>
        current.map((job) =>
          job.id === event.payload.id ? { ...job, progress: event.payload.percent } : job,
        ),
      );
    });
    let unlistenDrop: Promise<() => void> | null = null;
    try {
      unlistenDrop = getCurrentWebview().onDragDropEvent((event) => {
        if (event.payload.type === "over") setDragging(true);
        if (event.payload.type === "leave") setDragging(false);
        if (event.payload.type === "drop") {
          setDragging(false);
          void addPaths(event.payload.paths);
        }
      });
    } catch {
      // Browser preview outside the Tauri webview has no native window.
    }

    return () => {
      void unlistenJobs.then((fn) => fn());
      void unlistenProgress.then((fn) => fn());
      void unlistenDrop?.then((fn) => fn());
    };
  }, [addPaths]);

  async function onPickFiles() {
    try {
      const paths = await pickFiles();
      await addPaths(paths);
    } catch (err) {
      setNotice(String(err));
    }
  }

  async function onPickOutputDir() {
    try {
      const dir = await pickOutputDir();
      if (dir) setOutputDir(dir);
    } catch (err) {
      setNotice(String(err));
    }
  }

  function removeSource(path: string) {
    if (runningIds.has(path)) {
      setNotice("正在转码的文件先不要移除");
      return;
    }
    setSources((current) => current.filter((item) => item.path !== path));
  }

  async function onStart() {
    if (transcoding || busy) return;
    const importable = sources.filter((item) => item.importable);
    if (!importable.length) {
      setNotice("先把源视频拖进来，或点「选择文件」");
      return;
    }
    if (!outputDir) {
      setNotice("还没有保存位置");
      return;
    }
    setBusy(true);
    setNotice(null);
    try {
      const report = await enqueueJobs(
        importable.map(({ probing: _probing, ...info }) => info),
        config,
        outputDir,
      );
      if (report.skipped.length) {
        setNotice(
          `有 ${report.skipped.length} 个文件没法转码：${report.skipped
            .map((item) => `${fileName(item.path)}（${item.reason}）`)
            .join("；")}`,
        );
      }
    } catch (err) {
      setNotice(String(err));
    } finally {
      setBusy(false);
    }
  }

  const selectedCard = PRESET_CARDS.find((card) => card.id === config.preset);
  const shownPresets = showAllFormats
    ? PRESET_CARDS
    : collapsedPresetCards(config.preset);
  const audioOnly = isAudioPreset(config.preset);
  const qualityLabel =
    QUALITY_OPTIONS.find((item) => item.id === qualityId(config))?.title ?? "标准";
  const sizeLabel = SIZE_OPTIONS.find((item) => item.id === sizeId(config))?.title ?? "原尺寸";
  const copyOnly = isCopyPreset(config.preset);
  const trimmedCount = sources.filter(isTrimmed).length;
  const trimLabel = trimmedCount
    ? trimmedCount === 1 && previewSource && isTrimmed(previewSource)
      ? ` · 裁 ${formatClock(previewSource.trimStartSecs ?? 0)}–${formatClock(previewSource.trimEndSecs ?? timelineDuration ?? 0)}`
      : ` · ${trimmedCount} 个文件已裁剪`
    : "";

  return (
    <main className={dragging ? "app dragging" : "app"}>
      <header className="topbar">
        <div>
          <h1>轻转码</h1>
          <p>从一种格式转到另一种。文件只留在这台电脑上。</p>
        </div>
        <p className="privacy">不上传 · 不联网</p>
      </header>

      {notice ? (
        <div className="notice" role="status">
          <span>{notice}</span>
          <button type="button" className="text" onClick={() => setNotice(null)}>
            知道了
          </button>
        </div>
      ) : null}

      <section className="stage">
        <div className="stage-head">
          <span className="step">1</span>
          <div>
            <h2>源视频</h2>
            <p>现在是什么格式</p>
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
            <strong>{dragging ? "松开鼠标，放到这里" : "把要转码的视频拖到这里"}</strong>
            <p>也可以点这里选择文件，一次能选好几个</p>
          </div>
          <span className="ghost">选择文件</span>
        </button>
        {sources.length > 0 ? (
          <ul className="files">
            {sources.map((item) => (
              <li
                key={item.path}
                className={[
                  item.importable || item.probing ? "" : "bad",
                  item.path === selectedPath ? "on" : "",
                ]
                  .filter(Boolean)
                  .join(" ")}
                onClick={() => {
                  if (itemHasDuration(item)) setSelectedPath(item.path);
                }}
              >
                <div>
                  <strong>{fileName(item.path)}</strong>
                  <p>
                    {sourceFormat(item)}
                    {isTrimmed(item) ? " · 已裁剪" : ""}
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
                  移除
                </button>
              </li>
            ))}
          </ul>
        ) : null}
        {timelineDuration && previewSource ? (
          <div className="trim-wrap">
            <div className="trim-head">
              <div>
                <p className="tune-label">裁剪时间</p>
                <p className="tune-desc">
                  {sources.filter(itemHasDuration).length > 1
                    ? `正在裁「${fileName(previewSource.path)}」。点上面的文件可换一个。`
                    : "播放选中段，看到想要的位置就点「设为开始」或「设为结束」"}
                </p>
              </div>
              <button
                type="button"
                className="text"
                onClick={() =>
                  setSources((current) =>
                    current.map((item) =>
                      item.path === previewSource.path
                        ? { ...item, trimStartSecs: 0, trimEndSecs: timelineDuration }
                        : item,
                    ),
                  )
                }
              >
                恢复整段
              </button>
            </div>
            <TrimBar
              duration={timelineDuration}
              start={Math.min(previewSource.trimStartSecs ?? 0, timelineDuration)}
              end={Math.min(previewSource.trimEndSecs ?? timelineDuration, timelineDuration)}
              sourcePath={previewSource.path}
              container={previewSource.container}
              videoCodec={previewSource.videoCodec}
              audioCodec={previewSource.audioCodec}
              onChange={(nextStart, nextEnd) =>
                setSources((current) =>
                  current.map((item) =>
                    item.path === previewSource.path
                      ? { ...item, trimStartSecs: nextStart, trimEndSecs: nextEnd }
                      : item,
                  ),
                )
              }
            />
          </div>
        ) : null}
      </section>

      <section className="stage">
        <div className="stage-head">
          <span className="step">2</span>
          <div>
            <h2>转成</h2>
            <p>{conversionPreview(sources, selectedCard?.title ?? "MP4 · H.264")}</p>
          </div>
        </div>
        <div className="presets">
          {shownPresets.map((card) => (
            <button
              key={card.id}
              type="button"
              className={config.preset === card.id ? "preset on" : "preset"}
              onClick={() =>
                setConfig((current) => ({
                  preset: card.id,
                  quality: current.quality ?? "standard",
                  maxWidth: isCopyPreset(card.id) ? null : current.maxWidth,
                  maxHeight: isCopyPreset(card.id) ? null : current.maxHeight,
                }))
              }
            >
              <span className="preset-title">
                {card.title}
                {card.badge ? <em>{card.badge}</em> : null}
              </span>
              <span>{card.hint}</span>
            </button>
          ))}
          <button
            type="button"
            className="preset more"
            onClick={() => setShowAllFormats((open) => !open)}
          >
            <span className="preset-title">{showAllFormats ? "收起" : "更多"}</span>
            <span>{showAllFormats ? "只看常用格式" : "GIF、音频和其他格式"}</span>
          </button>
        </div>
        {isCopyPreset(config.preset) ? (
          <p className="tune-intro">
            不重编码只换文件外壳，画质和分辨率都保持原样。源视频编码必须能放进 MP4，不行的文件会提示改用普通转码。
          </p>
        ) : (
        <div className="tune">
          <p className="tune-intro">
            画质管「压得紧不紧」，分辨率管「画面有多大」。可以原画 + 1080p：画面缩小，细节尽量留着。
          </p>
          <div className="tune-grid">
            <div>
              <p className="tune-label">{audioOnly ? "音质" : "画质"}</p>
              <p className="tune-desc">
                {audioOnly ? "声音保留多少，和画面大小无关" : "压得厉不厉害，和画面大小无关"}
              </p>
              <div className="chips">
                {QUALITY_OPTIONS.map((item) => (
                  <button
                    key={item.id}
                    type="button"
                    className={qualityId(config) === item.id ? "chip on" : "chip"}
                    onClick={() => setConfig({ ...config, quality: item.id })}
                  >
                    <strong>{item.title}</strong>
                    <span>{item.hint}</span>
                  </button>
                ))}
              </div>
            </div>
            {audioOnly ? null : (
              <div>
                <p className="tune-label">分辨率</p>
                <p className="tune-desc">画面有多少像素。原尺寸就是不缩小。</p>
                <div className="chips">
                  {SIZE_OPTIONS.map((item) => (
                    <button
                      key={item.id}
                      type="button"
                      className={sizeId(config) === item.id ? "chip on" : "chip"}
                      onClick={() =>
                        setConfig({
                          ...config,
                          maxWidth: item.width,
                          maxHeight: item.height,
                        })
                      }
                    >
                      <strong>{item.title}</strong>
                      <span>{item.hint}</span>
                    </button>
                  ))}
                </div>
              </div>
            )}
          </div>
        </div>
        )}
      </section>

      <section className="save">
        <div>
          <h2>输出到</h2>
          <p>{outputDir ? shortFolder(outputDir) : "正在准备文件夹…"}</p>
        </div>
        <button type="button" className="ghost" onClick={() => void onPickOutputDir()}>
          换个位置
        </button>
      </section>

      {jobs.length > 0 ? (
        <section className="stage">
          <div className="stage-head">
            <span className="step">3</span>
            <div>
              <h2>转码队列</h2>
              <p>一次转一个，按源文件 → 输出文件进行</p>
            </div>
            {finishedCount > 0 ? (
              <button type="button" className="text" onClick={() => void clearFinishedJobs()}>
                清空已完成
              </button>
            ) : null}
          </div>
          <ul className="jobs">
            {jobs.map((job) => (
              <li key={job.id}>
                <div className="job-main">
                  <p className="job-path">
                    <strong>{fileName(job.sourcePath)}</strong>
                    <span className="to">→</span>
                    <strong>{job.outputPath ? fileName(job.outputPath) : "未命名"}</strong>
                  </p>
                  <p>
                    {sourceFromLabel(job.media)} → {jobTargetTitle(job)}
                    {" · "}
                    {STATUS_LABEL[job.status]}
                    {job.status === "running" ? ` ${Math.round(job.progress)}%` : ""}
                    {job.status === "running" && etaLabel(job.progress, startedAtRef.current.get(job.id))
                      ? ` · ${etaLabel(job.progress, startedAtRef.current.get(job.id))}`
                      : ""}
                    {job.error && job.status === "failed" ? ` · ${job.error}` : ""}
                  </p>
                  <div className="bar">
                    <span style={{ width: `${job.status === "completed" ? 100 : job.progress}%` }} />
                  </div>
                </div>
                <div className="job-actions">
                  {job.status === "running" || job.status === "queued" ? (
                    <button type="button" className="text" onClick={() => void cancelJob(job.id)}>
                      取消
                    </button>
                  ) : null}
                  {job.status === "failed" ? (
                    <button type="button" className="ghost" onClick={() => void retryJob(job.id)}>
                      再试一次
                    </button>
                  ) : null}
                  {job.status === "completed" && job.outputPath ? (
                    <>
                      <button
                        type="button"
                        className="ghost"
                        onClick={() => void openPath(job.outputPath!)}
                      >
                        打开
                      </button>
                      <button
                        type="button"
                        className="ghost"
                        onClick={() => void revealItemInDir(job.outputPath!)}
                      >
                        在文件夹中看
                      </button>
                    </>
                  ) : null}
                </div>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      <footer className="dock">
        <p>
          {importableCount
            ? audioOnly
              ? `将 ${importableCount} 个文件转为 ${selectedCard?.title ?? "MP3"} · ${qualityLabel}${trimLabel}`
              : copyOnly
                ? `将 ${importableCount} 个视频转为 ${selectedCard?.title ?? "MP4 · 不重编码"}${trimLabel}`
                : `将 ${importableCount} 个视频转为 ${selectedCard?.title ?? "MP4 · H.264"} · ${qualityLabel} · ${sizeLabel}${trimLabel}`
            : "先添加源视频，再开始转码"}
        </p>
        <button type="button" className="primary" onClick={() => void onStart()} disabled={!readyToStart}>
          {busy ? "正在加入队列…" : transcoding ? "正在转码…" : "开始转码"}
        </button>
      </footer>
    </main>
  );
}
