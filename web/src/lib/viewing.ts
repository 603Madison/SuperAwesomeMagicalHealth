import { create } from "zustand";
import type { Share } from "./types";

/**
 * Whose data the app is showing: the person's own (null), or someone who shared theirs with them.
 * While viewing, every GET carries `X-SAMH-View`, nothing can be changed, and only the shared
 * features show. Kept for this tab only, so a reload stays on the same view.
 */
const KEY = "samh.viewing";

export const useViewing = create<{ share: Share | null }>(() => ({ share: load() }));

function load(): Share | null {
  try {
    const text = sessionStorage.getItem(KEY);
    return text ? (JSON.parse(text) as Share) : null;
  } catch {
    return null;
  }
}

export function startViewing(share: Share | null) {
  useViewing.setState({ share });
  try {
    if (share) sessionStorage.setItem(KEY, JSON.stringify(share));
    else sessionStorage.removeItem(KEY);
  } catch {
    // Not kept; the view lasts until the page is reloaded.
  }
}

/** Whether nothing may be changed here: someone else's data is showing. */
export function useReadOnly(): boolean {
  return useViewing((s) => s.share != null);
}

/** For the API layer: the owner whose data is showing, if any. */
export function viewingOwner(): string | null {
  return useViewing.getState().share?.ownerId ?? null;
}
