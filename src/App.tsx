import { useEffect, useRef, useState } from "react";
import { listen } from "@tauri-apps/api/event";
import { listJobs, loadSessionSettings, saveSessionSettings } from "./api";
import ConvertPage from "./ConvertPage";
import HistoryPage from "./HistoryPage";
import MinePage, { type MinePageId } from "./MinePage";
import { historySegmentAfterEnqueue } from "./history";
import { resolveLocaleTag, t } from "./i18n";
import type { AppLanguage, ConvertMode, HistorySegment, Job } from "./types";
import "./App.css";

type Tab = "convert" | "history" | "mine";

function asAppLanguage(value: string | null | undefined): AppLanguage {
  switch (value) {
    case "system":
    case "zh-Hans":
    case "zh-Hant":
    case "en":
    case "ja":
    case "ko":
      return value;
    default:
      return "system";
  }
}

export default function App() {
  const [tab, setTab] = useState<Tab>("convert");
  const [convertMode, setConvertMode] = useState<ConvertMode>("video");
  const [historySegment, setHistorySegment] = useState<HistorySegment>("video");
  const [minePage, setMinePage] = useState<MinePageId>("language");
  const [language, setLanguage] = useState<AppLanguage>("system");
  const [jobs, setJobs] = useState<Job[]>([]);
  const [locale, setLocale] = useState(() => resolveLocaleTag("system", navigator.language));
  const [notice, setNotice] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);
  const startedAtRef = useRef(new Map<string, number>());

  const activeCount = jobs.filter((job) => job.status === "queued" || job.status === "running").length;

  useEffect(() => {
    void (async () => {
      try {
        const saved = await loadSessionSettings();
        const next = asAppLanguage(saved.language);
        setLanguage(next);
        setLocale(resolveLocaleTag(next, navigator.language));
      } catch {
        setLanguage("system");
        setLocale(resolveLocaleTag("system", navigator.language));
      }
    })();

    void listJobs()
      .then(setJobs)
      .catch(() => {});

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

    return () => {
      void unlistenJobs.then((fn) => fn());
      void unlistenProgress.then((fn) => fn());
    };
  }, []);

  async function applyLanguage(next: AppLanguage) {
    try {
      const current = await loadSessionSettings();
      await saveSessionSettings({ ...current, language: next });
      setLanguage(next);
      setLocale(resolveLocaleTag(next, navigator.language));
    } catch (err) {
      setNotice(String(err));
    }
  }

  return (
    <div className={dragging && tab === "convert" ? "shell dragging" : "shell"}>
      <nav className="sidebar">
        <p className="sidebar-brand">{t(locale, "about_name")}</p>
        <button
          type="button"
          className={tab === "convert" ? "on" : ""}
          onClick={() => setTab("convert")}
        >
          {t(locale, "tab_convert")}
        </button>
        <button
          type="button"
          className={tab === "history" ? "on" : ""}
          onClick={() => setTab("history")}
        >
          {t(locale, "tab_history")}
          {activeCount > 0 ? <em>{activeCount}</em> : null}
        </button>
        <button
          type="button"
          className={tab === "mine" ? "on" : ""}
          onClick={() => setTab("mine")}
        >
          {t(locale, "tab_mine")}
        </button>
      </nav>
      <div className="content">
        {notice ? (
          <div className="notice" role="status">
            <span>{notice}</span>
            <button type="button" className="text" onClick={() => setNotice(null)}>
              {t(locale, "action_got_it")}
            </button>
          </div>
        ) : null}
        <div className="convert-host" hidden={tab !== "convert"}>
          <ConvertPage
            active={tab === "convert"}
            locale={locale}
            mode={convertMode}
            jobs={jobs}
            dragging={dragging}
            onModeChange={setConvertMode}
            onNotice={setNotice}
            onDraggingChange={setDragging}
            onEnqueued={(preset) => setHistorySegment(historySegmentAfterEnqueue(preset))}
          />
        </div>
        {tab === "history" ? (
          <HistoryPage
            locale={locale}
            jobs={jobs}
            segment={historySegment}
            startedAtById={startedAtRef.current}
            onSegmentChange={setHistorySegment}
            onGoConvert={(mode) => {
              setConvertMode(mode);
              setTab("convert");
            }}
            onNotice={setNotice}
          />
        ) : null}
        {tab === "mine" ? (
          <MinePage
            locale={locale}
            language={language}
            setLanguage={(next) => void applyLanguage(next)}
            minePage={minePage}
            setMinePage={setMinePage}
            onNotice={setNotice}
          />
        ) : null}
      </div>
    </div>
  );
}
