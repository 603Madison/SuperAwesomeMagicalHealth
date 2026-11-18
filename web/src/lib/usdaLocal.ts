import type { UsdaLocalStatus } from "./types";

/*
 * Admin › Food search's words for USDA's food list kept on this server: sizes, where the copy
 * stands, and what's under way. Type imports only, so node can run it as it is (npm test).
 */

/** "18 MB", "330 MB", "1.2 GB". */
export function sizeText(bytes: number): string {
  if (bytes >= 1e9) return `${(bytes / 1e9).toFixed(1)} GB`;
  return `${Math.max(1, Math.round(bytes / 1e6))} MB`;
}

/** What each choice takes, before downloading: "About 18 MB to download and 12 MB on disk." */
export function choiceText(s: Pick<UsdaLocalStatus, "everydayDownload" | "everydayDisk" | "allDownload" | "allDisk">, packaged: boolean): string {
  return packaged
    ? `About ${sizeText(s.allDownload)} to download and ${sizeText(s.allDisk)} on disk. Barcodes need these.`
    : `About ${sizeText(s.everydayDownload)} to download and ${sizeText(s.everydayDisk)} on disk. No barcodes.`;
}

/** "Release 2026-10-29 · 412,345 foods · 330 MB on disk", or that there's none yet. */
export function copyLine(s: Pick<UsdaLocalStatus, "ready" | "release" | "foods" | "bytes" | "packaged">): string {
  if (!s.ready) return "Not downloaded yet";
  const what = s.packaged ? "everyday and packaged foods" : "everyday foods";
  return [`Release ${s.release ?? "unknown"}`, `${(s.foods ?? 0).toLocaleString("en-US")} ${what}`, `${sizeText(s.bytes ?? 0)} on disk`].join(" · ");
}

/** "Downloading packaged foods · 42%", while something's under way; null otherwise. */
export function workingLine(s: Pick<UsdaLocalStatus, "working" | "phase" | "progress">): string | null {
  if (!s.working) return null;
  const phase = s.phase ?? "Getting ready";
  return s.progress != null ? `${phase} · ${Math.round(s.progress * 100)}%` : phase;
}

/** "Last updated 3 Nov 2026 · Next check 3 Dec 2026", with what's known. */
export function datesLine(s: Pick<UsdaLocalStatus, "lastUpdated" | "nextCheck">, date: (ms: number) => string): string | null {
  const parts = [s.lastUpdated ? `Last updated ${date(s.lastUpdated)}` : null, s.nextCheck ? `Next check ${date(s.nextCheck)}` : null].filter(Boolean);
  return parts.length ? parts.join(" · ") : null;
}
