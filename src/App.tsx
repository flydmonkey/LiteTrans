import { useEffect, useState } from "react";
import { listen } from "@tauri-apps/api/event";
import { listJobs, loadSessionSettings } from "./api";
import ConvertPage from "./ConvertPage";
import { resolveLocaleTag, t } from "./i18n";
import type { AppLanguage, Job } from "./types";
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
  const [jobs, setJobs] = useState<Job[]>([]);
  const [locale, setLocale] = useState(() => resolveLocaleTag("system", navigator.language));
  const [notice, setNotice] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);

  const activeCount = jobs.filter((job) => job.status === "queued" || job.status === "running").length;

  useEffect(() => {
    void (async () => {
      try {
        const saved = await loadSessionSettings();
        setLocale(resolveLocaleTag(asAppLanguage(saved.language), navigator.language));
      } catch {
        setLocale(resolveLocaleTag("system", navigator.language));
      }
    })();

    void listJobs()
      .then(setJobs)
      .catch(() => {});

    const unlistenJobs = listen<Job[]>("jobs-changed", (event) => {
      setJobs(event.payload);
    });
    const unlistenProgress = listen<{ id: string; percent: number }>("job-progress", (event) => {
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
            locale={locale}
            jobs={jobs}
            dragging={dragging}
            onNotice={setNotice}
            onDraggingChange={setDragging}
          />
        </div>
        {tab === "history" ? <p>{t(locale, "tab_history")}</p> : null}
        {tab === "mine" ? <p>{t(locale, "tab_mine")}</p> : null}
      </div>
    </div>
  );
}
