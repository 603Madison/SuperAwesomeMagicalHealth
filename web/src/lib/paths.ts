/*
 * Where to go after signing in (npm test covers it). No imports, so node can run it as it is.
 */

const HERE = "https://this-server.invalid";

/**
 * [path] if it's a page on this server ("/food?date=…"), otherwise "/". Never another site:
 * not "//elsewhere", "/\elsewhere" or "https://…", and nothing with control characters or
 * backslashes, which browsers tidy into those.
 */
export function safeReturn(path: string | null | undefined): string {
  if (!path || !path.startsWith("/") || path.startsWith("//")) return "/";
  if (/[\u0000-\u001f\u007f\\]/.test(path)) return "/";
  try {
    const url = new URL(path, HERE);
    if (url.origin !== HERE) return "/";
    return `${url.pathname}${url.search}${url.hash}`;
  } catch {
    return "/";
  }
}
