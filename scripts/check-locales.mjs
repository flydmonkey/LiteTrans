import { readFileSync } from "node:fs";
const en = JSON.parse(readFileSync("src/locales/en.json", "utf8"));
for (const id of ["zh-Hans", "zh-Hant", "ja", "ko"]) {
  const other = JSON.parse(readFileSync(`src/locales/${id}.json`, "utf8"));
  const missing = Object.keys(en).filter((k) => !(k in other));
  if (missing.length) {
    console.error(id, missing);
    process.exit(1);
  }
}
console.log("locales ok");
