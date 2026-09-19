export type LanShareSnapshot = {
  enabled: boolean;
  token: string;
};

export type LanShareIntent = {
  enabled: boolean;
  token: string;
};

/** Latest-wins gate: overlapping applyShare calls keep only the newest intent. */
export type LanShareApplyGate = {
  running: boolean;
  latest: LanShareIntent | null;
};

export function normalizeLanToken(raw: string): string {
  return raw.trim();
}

export function shouldApplyLanShare({
  prev,
  next,
}: {
  prev: LanShareSnapshot;
  next: LanShareSnapshot;
}): boolean {
  return (
    prev.enabled !== next.enabled ||
    normalizeLanToken(prev.token) !== normalizeLanToken(next.token)
  );
}

/** Record the latest intent. Returns true if the caller should run the worker loop. */
export function requestLanShareApply(
  gate: LanShareApplyGate,
  intent: LanShareIntent,
): boolean {
  gate.latest = intent;
  if (gate.running) {
    return false;
  }
  gate.running = true;
  return true;
}

/** Take the newest queued intent, or stop the worker when the queue is empty. */
export function nextLanShareApply(gate: LanShareApplyGate): LanShareIntent | null {
  const intent = gate.latest;
  gate.latest = null;
  if (!intent) {
    gate.running = false;
    return null;
  }
  return intent;
}
