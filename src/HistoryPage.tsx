import { useEffect, useRef, useState, type KeyboardEvent, type MouseEvent } from "react";
import { openPath, revealItemInDir } from "@tauri-apps/plugin-opener";
import { cancelJob, clearFinishedJobs, deleteJob, renameJob, retryJob } from "./api";
import {
  formatHistoryDate,
  historySegmentFor,
  jobRowActions,
  jobRowOverflowActions,
  jobRowPrimaryAction,
  type JobRowAction,
} from "./history";
import { t } from "./i18n";
import type { HistorySegment, Job } from "./types";

const SEGMENTS: HistorySegment[] = ["video", "audio", "document"];

const EMPTY_KEY: Record<HistorySegment, string> = {
  video: "history_empty_video",
  audio: "history_empty_audio",
  document: "history_empty_document",
};

const ACTION_KEY: Record<JobRowAction, string> = {
  cancel: "action_cancel",
  retry: "action_retry",
  open: "action_open",
  reveal: "action_reveal",
  rename: "action_rename",
  delete: "action_delete",
};

const STATUS_KEY: Record<Job["status"], string> = {
  queued: "status_queued",
  running: "status_running",
  completed: "status_completed",
  failed: "status_failed",
  cancelled: "status_cancelled",
};

type Dialog =
  | { kind: "clear" }
  | { kind: "delete"; job: Job }
  | { kind: "rename"; job: Job; name: string };

type MenuState = { jobId: string; x: number; y: number };

export type HistoryPageProps = {
  locale: string;
  jobs: Job[];
  segment: HistorySegment;
  startedAtById: Map<string, number>;
  onSegmentChange: (segment: HistorySegment) => void;
  onGoConvert: (segment: HistorySegment) => void;
  onNotice: (message: string | null) => void;
};

function fileName(path: string) {
  return path.split(/[/\\]/).pop() ?? path;
}

function firstOutputPath(job: Job): string | null {
  if (job.outputPaths && job.outputPaths.length > 0) return job.outputPaths[0] ?? null;
  return job.outputPath;
}

function jobTitle(job: Job, untitled: string): string {
  if (job.displayName?.trim()) return job.displayName;
  return fileName(job.outputPath || job.sourcePath) || untitled;
}

function renameSeed(job: Job): string {
  return jobTitle(job, "").replace(/\.[^.]+$/, "");
}

function formatEtaDuration(locale: string, seconds: number): string {
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

function etaLabel(locale: string, progress: number, startedAt: number | undefined): string {
  if (startedAt == null || progress < 3) return "";
  const elapsed = (Date.now() - startedAt) / 1000;
  const remaining = (elapsed * (100 - progress)) / progress;
  if (!Number.isFinite(remaining)) return "";
  if (remaining < 12) return t(locale, "eta_soon");
  return t(locale, "eta_about", { time: formatEtaDuration(locale, remaining) });
}

function errorText(locale: string, error: string | null): string {
  if (!error) return t(locale, "status_failed");
  const translated = t(locale, error);
  return translated !== error ? translated : error;
}

function jobSubtitle(locale: string, job: Job, startedAt: number | undefined): string {
  if (job.status === "failed") return errorText(locale, job.error);
  const parts = [t(locale, STATUS_KEY[job.status])];
  if (job.status === "running") {
    parts.push(`${Math.round(job.progress)}%`);
    const eta = etaLabel(locale, job.progress, startedAt);
    if (eta) parts.push(eta);
  }
  const date = formatHistoryDate(job.createdAtEpochMs);
  if (date) parts.push(date);
  return parts.join(" · ");
}

export default function HistoryPage({
  locale,
  jobs,
  segment,
  startedAtById,
  onSegmentChange,
  onGoConvert,
  onNotice,
}: HistoryPageProps) {
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [menu, setMenu] = useState<MenuState | null>(null);
  const [busy, setBusy] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);

  const visible = jobs.filter((job) => historySegmentFor(job) === segment).slice().reverse();
  const activeCount = jobs.filter((job) => job.status === "queued" || job.status === "running").length;
  const hasFinished = visible.some((job) => job.status !== "queued" && job.status !== "running");

  useEffect(() => {
    function onKey(event: globalThis.KeyboardEvent) {
      if (event.key === "Escape") {
        setMenu(null);
        setDialog(null);
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  useEffect(() => {
    if (!menu) return;
    function onPointerDown(event: PointerEvent) {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
        setMenu(null);
      }
    }
    window.addEventListener("pointerdown", onPointerDown);
    return () => window.removeEventListener("pointerdown", onPointerDown);
  }, [menu]);

  async function run(action: () => Promise<void>) {
    setBusy(true);
    onNotice(null);
    try {
      await action();
    } catch (err) {
      onNotice(String(err));
    } finally {
      setBusy(false);
    }
  }

  async function openJob(job: Job) {
    const path = firstOutputPath(job);
    if (!path) return;
    await run(() => openPath(path));
  }

  async function revealJob(job: Job) {
    const path = firstOutputPath(job);
    if (!path) return;
    await run(() => revealItemInDir(path));
  }

  function perform(action: JobRowAction, job: Job) {
    setMenu(null);
    switch (action) {
      case "cancel":
        void run(() => cancelJob(job.id));
        return;
      case "retry":
        void run(() => retryJob(job.id));
        return;
      case "open":
        void openJob(job);
        return;
      case "reveal":
        void revealJob(job);
        return;
      case "rename":
        setDialog({ kind: "rename", job, name: renameSeed(job) });
        return;
      case "delete":
        setDialog({ kind: "delete", job });
    }
  }

  function openMore(job: Job, event: MouseEvent<HTMLElement>) {
    event.preventDefault();
    event.stopPropagation();
    if (jobRowOverflowActions(job.status).length === 0) return;
    setMenu({ jobId: job.id, x: event.clientX, y: event.clientY });
  }

  function onRowKeyDown(job: Job, event: KeyboardEvent<HTMLLIElement>) {
    if (dialog) return;

    if (event.key === "Enter") {
      event.preventDefault();
      if (job.status === "completed") void openJob(job);
      else if (job.status === "failed" || job.status === "cancelled") void run(() => retryJob(job.id));
      return;
    }
    if (
      (event.key === "Delete" || event.key === "Backspace") &&
      jobRowActions(job.status).includes("delete")
    ) {
      event.preventDefault();
      setDialog({ kind: "delete", job });
    }
  }

  const menuJob = menu ? visible.find((job) => job.id === menu.jobId) : null;

  return (
    <div className="history-page">
      <header className="history-head">
        <div>
          <h1>{t(locale, "tab_history")}</h1>
          <p>{t(locale, "history_subtitle")}</p>
        </div>
        {hasFinished ? (
          <button type="button" className="text" onClick={() => setDialog({ kind: "clear" })} disabled={busy}>
            {t(locale, "action_clear_finished")}
          </button>
        ) : null}
      </header>

      <div className="chips history-segments">
        {SEGMENTS.map((id) => (
          <button
            key={id}
            type="button"
            className={segment === id ? "chip on" : "chip"}
            onClick={() => onSegmentChange(id)}
          >
            <strong>{t(locale, `segment_${id}`)}</strong>
          </button>
        ))}
      </div>

      {activeCount > 0 ? (
        <button type="button" className="history-banner" onClick={() => onGoConvert(segment)}>
          {t(locale, "history_running_banner", { count: activeCount })}
        </button>
      ) : null}

      {visible.length === 0 ? (
        <div className="history-empty">
          <strong>{t(locale, EMPTY_KEY[segment])}</strong>
          <p>{t(locale, "history_empty_hint")}</p>
          <button type="button" className="primary" onClick={() => onGoConvert(segment)}>
            {t(locale, "history_go_convert")}
          </button>
        </div>
      ) : (
        <ul className="jobs history-jobs">
          {visible.map((job) => {
            const primary = jobRowPrimaryAction(job.status);
            const overflow = jobRowOverflowActions(job.status);
            const active = job.status === "queued" || job.status === "running";
            return (
              <li
                key={job.id}
                className={job.status === "failed" ? "failed" : ""}
                tabIndex={0}
                onContextMenu={(event) => openMore(job, event)}
                onDoubleClick={() => {
                  if (job.status === "completed") void openJob(job);
                }}
                onKeyDown={(event) => onRowKeyDown(job, event)}
              >
                <div className="job-main">
                  <p className="job-path">
                    <strong>{jobTitle(job, t(locale, "untitled"))}</strong>
                  </p>
                  <p>{jobSubtitle(locale, job, startedAtById.get(job.id))}</p>
                  {active ? (
                    <div className="bar">
                      <span style={{ width: `${job.status === "queued" ? 0 : job.progress}%` }} />
                    </div>
                  ) : null}
                </div>
                <div className="job-actions" onDoubleClick={(event) => event.stopPropagation()}>
                  <button
                    type="button"
                    className={primary === "cancel" ? "text" : "ghost"}
                    disabled={busy}
                    onClick={(event) => {
                      event.stopPropagation();
                      perform(primary, job);
                    }}
                  >
                    {t(locale, ACTION_KEY[primary])}
                  </button>
                  {overflow.length > 0 ? (
                    <button
                      type="button"
                      className="text"
                      disabled={busy}
                      onClick={(event) => openMore(job, event)}
                    >
                      {t(locale, "action_more")}
                    </button>
                  ) : null}
                </div>
              </li>
            );
          })}
        </ul>
      )}

      {menu && menuJob ? (
        <div
          ref={menuRef}
          className="history-menu"
          role="menu"
          style={{ left: menu.x, top: menu.y }}
        >
          {jobRowOverflowActions(menuJob.status).map((action) => (
            <button
              key={action}
              type="button"
              role="menuitem"
              className={action === "delete" ? "danger" : ""}
              onClick={() => perform(action, menuJob)}
            >
              {t(locale, ACTION_KEY[action])}
            </button>
          ))}
        </div>
      ) : null}

      {dialog ? (
        <div
          className="modal-backdrop"
          role="dialog"
          aria-modal="true"
          onClick={() => setDialog(null)}
        >
          <div className="modal-card" onClick={(event) => event.stopPropagation()}>
            {dialog.kind === "clear" ? (
              <>
                <h2>{t(locale, "history_clear_title")}</h2>
                <p>{t(locale, "history_clear_message")}</p>
                <div className="modal-actions">
                  <button type="button" className="ghost" onClick={() => setDialog(null)}>
                    {t(locale, "action_cancel")}
                  </button>
                  <button
                    type="button"
                    className="primary"
                    disabled={busy}
                    onClick={() => {
                      setDialog(null);
                      void run(() => clearFinishedJobs(segment));
                    }}
                  >
                    {t(locale, "action_clear_finished")}
                  </button>
                </div>
              </>
            ) : null}
            {dialog.kind === "delete" ? (
              <>
                <h2>{t(locale, "history_delete_title")}</h2>
                <p>{t(locale, "history_delete_message")}</p>
                <div className="modal-actions">
                  <button type="button" className="ghost" onClick={() => setDialog(null)}>
                    {t(locale, "action_cancel")}
                  </button>
                  <button
                    type="button"
                    className="primary"
                    disabled={busy}
                    onClick={() => {
                      const id = dialog.job.id;
                      setDialog(null);
                      void run(() => deleteJob(id));
                    }}
                  >
                    {t(locale, "action_delete")}
                  </button>
                </div>
              </>
            ) : null}
            {dialog.kind === "rename" ? (
              <>
                <h2>{t(locale, "history_rename_title")}</h2>
                <p>{t(locale, "history_rename_hint")}</p>
                <input
                  value={dialog.name}
                  autoFocus
                  onChange={(event) => setDialog({ ...dialog, name: event.target.value })}
                  onKeyDown={(event) => {
                    if (event.key === "Enter") {
                      event.preventDefault();
                      if (!dialog.name.trim()) return;
                      const { job, name } = dialog;
                      setDialog(null);
                      void run(() => renameJob(job.id, name));
                    }
                  }}
                />
                <div className="modal-actions">
                  <button type="button" className="ghost" onClick={() => setDialog(null)}>
                    {t(locale, "action_cancel")}
                  </button>
                  <button
                    type="button"
                    className="primary"
                    disabled={busy || !dialog.name.trim()}
                    onClick={() => {
                      const { job, name } = dialog;
                      setDialog(null);
                      void run(() => renameJob(job.id, name));
                    }}
                  >
                    {t(locale, "action_rename")}
                  </button>
                </div>
              </>
            ) : null}
          </div>
        </div>
      ) : null}
    </div>
  );
}
