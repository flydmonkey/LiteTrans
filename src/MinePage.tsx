import { useEffect, useRef, useState } from "react";
import { openUrl } from "@tauri-apps/plugin-opener";
import { appVersion, lanStatus, setLanShare } from "./api";
import { t } from "./i18n";
import {
  nextLanShareApply,
  requestLanShareApply,
  shouldApplyLanShare,
  type LanShareApplyGate,
} from "./lanShare";
import type { AppLanguage, LanStatus } from "./types";

export type MinePageId = "lan" | "language" | "privacy" | "terms" | "about";

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

const NAV_GROUPS: MinePageId[][] = [
  ["lan", "language"],
  ["privacy", "terms"],
  ["about"],
];

const NAV_KEY: Record<MinePageId, string> = {
  lan: "mine_lan",
  language: "mine_language",
  privacy: "mine_privacy",
  terms: "mine_terms",
  about: "mine_about",
};

const EMPTY_LAN_STATUS: LanStatus = {
  enabled: false,
  token: "",
  url: null,
  error: null,
};

export type MinePageProps = {
  locale: string;
  language: AppLanguage;
  setLanguage: (language: AppLanguage) => void;
  minePage: MinePageId;
  setMinePage: (page: MinePageId) => void;
  onNotice?: (message: string | null) => void;
};

function invokeErrorText(err: unknown): string {
  if (typeof err === "string") return err;
  if (err instanceof Error && err.message) return err.message;
  return String(err);
}

export default function MinePage({
  locale,
  language,
  setLanguage,
  minePage,
  setMinePage,
  onNotice,
}: MinePageProps) {
  const [version, setVersion] = useState("");
  const [status, setStatus] = useState<LanStatus>(EMPTY_LAN_STATUS);
  const [tokenDraft, setTokenDraft] = useState("");
  const enabledIntentRef = useRef(false);
  const switchClickInFlightRef = useRef(false);
  const shareGateRef = useRef<LanShareApplyGate>({ running: false, latest: null });

  function rememberShare(next: LanStatus) {
    setStatus(next);
    setTokenDraft(next.token);
    enabledIntentRef.current = next.enabled;
  }

  useEffect(() => {
    void appVersion()
      .then(setVersion)
      .catch(() => setVersion(""));
    void lanStatus()
      .then(rememberShare)
      .catch(() => {});
  }, []);

  async function refreshLanStatus() {
    const next = await lanStatus();
    rememberShare(next);
    return next;
  }

  async function applyShare(enabled: boolean, token: string) {
    enabledIntentRef.current = enabled;
    if (!requestLanShareApply(shareGateRef.current, { enabled, token })) {
      return;
    }
    while (true) {
      const intent = nextLanShareApply(shareGateRef.current);
      if (!intent) {
        break;
      }
      try {
        await setLanShare({ enabled: intent.enabled, token: intent.token });
      } catch (err) {
        const errorKey = invokeErrorText(err);
        if (errorKey === "lan_need_address" || errorKey === "lan_ports_busy") {
          onNotice?.(t(locale, errorKey));
        } else {
          onNotice?.(errorKey);
        }
      }
      try {
        await refreshLanStatus();
      } catch (err) {
        onNotice?.(invokeErrorText(err));
      }
    }
  }

  function onTokenBlur() {
    if (switchClickInFlightRef.current) {
      return;
    }
    const enabled = enabledIntentRef.current;
    if (
      !shouldApplyLanShare({
        prev: { enabled, token: status.token },
        next: { enabled, token: tokenDraft },
      })
    ) {
      return;
    }
    void applyShare(enabled, tokenDraft);
  }

  async function copyAddress(url: string) {
    try {
      await navigator.clipboard.writeText(url);
    } catch (err) {
      onNotice?.(invokeErrorText(err));
    }
  }

  async function openLegal(url: string) {
    try {
      await openUrl(url);
    } catch (err) {
      onNotice?.(String(err));
    }
  }

  const shareUrl = status.url;

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
          {minePage === "lan" ? (
            <>
              <h1 id="mine-pane-title">{t(locale, "mine_lan")}</h1>
              <label
                className="mine-lan-switch"
                onPointerDown={() => {
                  switchClickInFlightRef.current = true;
                }}
                onPointerUp={() => {
                  switchClickInFlightRef.current = false;
                }}
                onPointerCancel={() => {
                  switchClickInFlightRef.current = false;
                }}
              >
                <span className="mine-lan-switch-copy">
                  <span>{t(locale, "mine_lan")}</span>
                  {shareUrl ? <em>{t(locale, "lan_status_on")}</em> : null}
                </span>
                <input
                  type="checkbox"
                  role="switch"
                  checked={status.enabled}
                  onChange={(event) => {
                    enabledIntentRef.current = event.target.checked;
                    void applyShare(event.target.checked, tokenDraft);
                  }}
                />
              </label>
              <p className="mine-legal">{t(locale, "lan_open_warning")}</p>
              <label className="mine-lan-field">
                <span>{t(locale, "lan_token_label")}</span>
                <input
                  type="text"
                  value={tokenDraft}
                  placeholder={t(locale, "lan_token_placeholder")}
                  autoComplete="off"
                  spellCheck={false}
                  onChange={(event) => setTokenDraft(event.target.value)}
                  onBlur={onTokenBlur}
                />
              </label>
              {!tokenDraft.trim() ? (
                <p className="mine-lan-hint">{t(locale, "lan_token_empty_hint")}</p>
              ) : null}
              {shareUrl ? (
                <div className="mine-lan-address">
                  <p>
                    <span>{t(locale, "lan_address")}</span>
                    {shareUrl}
                  </p>
                  <div className="mine-lan-actions">
                    <button type="button" className="text" onClick={() => void copyAddress(shareUrl)}>
                      {t(locale, "lan_copy")}
                    </button>
                    <button type="button" className="text" onClick={() => void openLegal(shareUrl)}>
                      {t(locale, "lan_open_browser")}
                    </button>
                  </div>
                </div>
              ) : null}
            </>
          ) : null}

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
