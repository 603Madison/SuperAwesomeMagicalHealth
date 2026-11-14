import type { FieldLayout } from "./types";

/*
 * The field layout's rules that don't need React (npm test covers them; lib/fields.ts uses them):
 * what a failed GET /api/fields leaves, whether amounts of food show, and which answer counts.
 * Type imports only, so node can run it as it is.
 */

/**
 * What a failed GET /api/fields leaves: for a 404 (an older server without it), the defaults;
 * for anything else, nothing yet (null), which shows the default fields and no amounts of food
 * until it's asked again.
 */
export function layoutAfterError(status: number | undefined, defaults: string[]): FieldLayout | null {
  return status === 404 ? { effective: defaults, show: [], hide: [], because: {} } : null;
}

/**
 * Whether amounts of food show (calories, macros, targets): only when the server says so. Not
 * for someone in eating disorder recovery, not while it's still loading or couldn't be loaded,
 * and not when the answer leaves it out. While viewing someone's share, [own] is the viewer's
 * own layout: both have to allow them.
 */
export function showsFoodNumbers(shown: FieldLayout | null, own: FieldLayout | null): boolean {
  return shown?.foodNumbers === true && own?.foodNumbers === true;
}

/** Numbers each request, so only the latest one's answer counts. */
export function latestOnly(): { next: () => number; isLatest: (n: number) => boolean; forget: () => void } {
  let last = 0;
  return {
    next: () => ++last,
    isLatest: (n) => n === last,
    // Nothing asked before this counts any more (someone else signed in).
    forget: () => { last++; },
  };
}
