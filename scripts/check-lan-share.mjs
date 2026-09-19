import { normalizeLanToken, shouldApplyLanShare } from "../src/lanShare.ts";

function assert(cond, msg) {
  if (!cond) {
    console.error(msg);
    process.exit(1);
  }
}

assert(normalizeLanToken("  secret  ") === "secret", "trim token");
assert(normalizeLanToken("   ") === "", "blank token");
assert(
  !shouldApplyLanShare({
    prev: { enabled: true, token: "ab" },
    next: { enabled: true, token: "  ab  " },
  }),
  "no-op blur after normalize",
);
assert(
  shouldApplyLanShare({
    prev: { enabled: false, token: "ab" },
    next: { enabled: false, token: "cd" },
  }),
  "token change",
);
assert(
  shouldApplyLanShare({
    prev: { enabled: false, token: "ab" },
    next: { enabled: true, token: "ab" },
  }),
  "enabled change",
);
assert(
  !shouldApplyLanShare({
    prev: { enabled: false, token: "" },
    next: { enabled: false, token: "   " },
  }),
  "empty vs whitespace",
);

console.log("lan share apply ok");
