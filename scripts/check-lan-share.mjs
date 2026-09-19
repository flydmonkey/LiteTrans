import {
  nextLanShareApply,
  normalizeLanToken,
  requestLanShareApply,
  shouldApplyLanShare,
} from "../src/lanShare.ts";

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

const gate = { running: false, latest: null };
assert(requestLanShareApply(gate, { enabled: false, token: "secret" }), "first apply starts worker");
assert(
  !requestLanShareApply(gate, { enabled: true, token: "secret" }),
  "overlapping apply does not start a second worker",
);
const latest = nextLanShareApply(gate);
assert(latest?.enabled === true && latest.token === "secret", "stale disable loses to later enable");
assert(nextLanShareApply(gate) === null, "queue drains after latest intent");
assert(gate.running === false, "worker stops when queue is empty");

const blurThenSwitch = { running: false, latest: null };
assert(requestLanShareApply(blurThenSwitch, { enabled: true, token: "ab" }), "token blur starts worker");
assert(
  !requestLanShareApply(blurThenSwitch, { enabled: false, token: "ab" }),
  "switch after blur stays on one worker",
);
const afterSwitch = nextLanShareApply(blurThenSwitch);
assert(afterSwitch?.enabled === false, "tab-off then toggle ends on the latest switch intent");
assert(nextLanShareApply(blurThenSwitch) === null, "no leftover share intent");

console.log("lan share apply ok");
