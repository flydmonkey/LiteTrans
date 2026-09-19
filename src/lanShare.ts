export type LanShareSnapshot = {
  enabled: boolean;
  token: string;
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
