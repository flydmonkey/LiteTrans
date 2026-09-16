export type MediaInfo = {
  path: string;
  durationSecs: number | null;
  container: string | null;
  videoCodec: string | null;
  width: number | null;
  height: number | null;
  frameRate: number | null;
  audioCodec: string | null;
  channels: number | null;
  importable: boolean;
  error: string | null;
  trimStartSecs?: number | null;
  trimEndSecs?: number | null;
};

export type PresetInfo = {
  id: string;
  label: string;
  description: string;
};

export type OutputConfig = {
  preset: string;
  container?: string | null;
  videoEncoder?: string | null;
  maxWidth?: number | null;
  maxHeight?: number | null;
  videoBitrateKbps?: number | null;
  frameRate?: number | null;
  audioEncoder?: string | null;
  audioBitrateKbps?: number | null;
  keepAudio?: boolean | null;
  quality?: string | null;
  trimStartSecs?: number | null;
  trimEndSecs?: number | null;
};

export type JobStatus = "queued" | "running" | "completed" | "failed" | "cancelled";

export type Job = {
  id: string;
  sourcePath: string;
  outputPath: string | null;
  status: JobStatus;
  progress: number;
  error: string | null;
  config: OutputConfig;
  media: MediaInfo;
};

export type SkippedSource = {
  path: string;
  reason: string;
};

export type EnqueueReport = {
  jobs: Job[];
  skipped: SkippedSource[];
};

export type SessionSettings = {
  outputDir?: string | null;
  preset?: string | null;
  quality?: string | null;
  maxWidth?: number | null;
  maxHeight?: number | null;
};

export type SourceItem = MediaInfo & {
  probing: boolean;
};
