import type { AppLanguage } from "./types";
import en from "./locales/en.json";
import zhHans from "./locales/zh-Hans.json";
import zhHant from "./locales/zh-Hant.json";
import ja from "./locales/ja.json";
import ko from "./locales/ko.json";

type Messages = Record<string, string>;

const TABLES: Record<string, Messages> = {
  en,
  "zh-Hans": zhHans,
  "zh-Hant": zhHant,
  ja,
  ko,
};

function resolveSystemTag(systemTag: string): string {
  const normalized = systemTag.replace(/_/g, "-").toLowerCase();
  if (!normalized) return "en";
  if (
    normalized === "zh-cn" ||
    normalized === "zh-sg" ||
    normalized === "zh-hans" ||
    normalized.startsWith("zh-hans-")
  ) {
    return "zh-Hans";
  }
  if (
    normalized === "zh-tw" ||
    normalized === "zh-hk" ||
    normalized === "zh-mo" ||
    normalized === "zh-hant" ||
    normalized.startsWith("zh-hant-")
  ) {
    return "zh-Hant";
  }
  if (normalized === "en" || normalized.startsWith("en-")) return "en";
  if (normalized === "ja" || normalized.startsWith("ja-")) return "ja";
  if (normalized === "ko" || normalized.startsWith("ko-")) return "ko";
  return "en";
}

export function resolveLocaleTag(language: AppLanguage, systemTag: string): string {
  switch (language) {
    case "system":
      return resolveSystemTag(systemTag);
    case "zh-Hans":
      return "zh-Hans";
    case "zh-Hant":
      return "zh-Hant";
    case "en":
      return "en";
    case "ja":
      return "ja";
    case "ko":
      return "ko";
  }
}

export function t(locale: string, key: string, vars?: Record<string, string | number>): string {
  const table = TABLES[locale] ?? TABLES.en;
  let value = table[key] ?? TABLES.en[key] ?? key;
  if (vars) {
    for (const [name, replacement] of Object.entries(vars)) {
      value = value.replace(new RegExp(`\\{\\{${name}\\}\\}`, "g"), String(replacement));
    }
  }
  return value;
}
