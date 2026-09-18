import { useEffect, useState } from "react";
import { openUrl } from "@tauri-apps/plugin-opener";
import { appVersion } from "./api";
import { t } from "./i18n";
import type { AppLanguage } from "./types";

export type MinePageId = "language" | "privacy" | "terms" | "about";

const LANGUAGES: AppLanguage[] = ["system", "zh-Hans", "zh-Hant", "en", "ja", "ko"];

const LANGUAGE_KEY: Record<AppLanguage, string> = {
  system: "language_system",
  "zh-Hans": "language_zh_hans",
  "zh-Hant": "language_zh_hant",
  en: "language_en",
  ja: "language_ja",
  ko: "language_ko",
};

const PRIVACY_URL = "https://flydmonkey.github.io/LiteTrans/docs/privacy.html";
const TERMS_URL = "https://flydmonkey.github.io/LiteTrans/docs/terms.html";

const NAV_GROUPS: MinePageId[][] = [["language"], ["privacy", "terms"], ["about"]];

const NAV_KEY: Record<MinePageId, string> = {
  language: "mine_language",
  privacy: "mine_privacy",
  terms: "mine_terms",
  about: "mine_about",
};

export type MinePageProps = {
  locale: string;
  language: AppLanguage;
  setLanguage: (language: AppLanguage) => void;
  minePage: MinePageId;
  setMinePage: (page: MinePageId) => void;
  onNotice?: (message: string | null) => void;
};

export default function MinePage({
  locale,
  language,
  setLanguage,
  minePage,
  setMinePage,
  onNotice,
}: MinePageProps) {
  const [version, setVersion] = useState("");

  useEffect(() => {
    void appVersion()
      .then(setVersion)
      .catch(() => setVersion(""));
  }, []);

  async function openLegal(url: string) {
    try {
      await openUrl(url);
    } catch (err) {
      onNotice?.(String(err));
    }
  }

  return (
    <div className="mine-page">
      <div className="mine-columns">
        <nav className="mine-nav" aria-label={t(locale, "tab_mine")}>
          {NAV_GROUPS.map((group) => (
            <div key={group.join("-")} className="mine-nav-group">
              {group.map((id) => (
                <button
                  key={id}
                  type="button"
                  className={minePage === id ? "on" : ""}
                  aria-current={minePage === id ? "page" : undefined}
                  onClick={() => setMinePage(id)}
                >
                  {t(locale, NAV_KEY[id])}
                </button>
              ))}
            </div>
          ))}
        </nav>

        <section className="mine-pane" aria-labelledby="mine-pane-title">
          {minePage === "language" ? (
            <>
              <h1 id="mine-pane-title">{t(locale, "mine_language")}</h1>
              <div className="mine-radios" role="radiogroup" aria-labelledby="mine-pane-title">
                {LANGUAGES.map((id) => (
                  <label key={id} className={language === id ? "mine-radio on" : "mine-radio"}>
                    <input
                      type="radio"
                      name="app-language"
                      checked={language === id}
                      onChange={() => setLanguage(id)}
                    />
                    <span>{t(locale, LANGUAGE_KEY[id])}</span>
                  </label>
                ))}
              </div>
            </>
          ) : null}

          {minePage === "privacy" ? (
            <>
              <h1 id="mine-pane-title">{t(locale, "mine_privacy")}</h1>
              <p className="mine-legal">{t(locale, "privacy_body")}</p>
              <button type="button" className="text" onClick={() => void openLegal(PRIVACY_URL)}>
                {t(locale, "action_open_in_browser")}
              </button>
            </>
          ) : null}

          {minePage === "terms" ? (
            <>
              <h1 id="mine-pane-title">{t(locale, "mine_terms")}</h1>
              <p className="mine-legal">{t(locale, "terms_body")}</p>
              <button type="button" className="text" onClick={() => void openLegal(TERMS_URL)}>
                {t(locale, "action_open_in_browser")}
              </button>
            </>
          ) : null}

          {minePage === "about" ? (
            <>
              <h1 id="mine-pane-title" className="mine-about-title">
                {t(locale, "about_name")}
              </h1>
              {version ? <p className="mine-version">{version}</p> : null}
              <p>{t(locale, "about_no_upload")}</p>
              <p className="mine-legal">{t(locale, "about_body", { version })}</p>
            </>
          ) : null}
        </section>
      </div>

      <footer className="mine-footer">
        <strong>{t(locale, "about_name")}</strong>
        {version ? <span>{version}</span> : null}
        <p className="privacy">{t(locale, "privacy_capsule")}</p>
      </footer>
    </div>
  );
}
