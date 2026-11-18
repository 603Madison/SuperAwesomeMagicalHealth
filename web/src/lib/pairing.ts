/*
 * The app sign-in's pairing code: six digits worked out from the sign-in's challenge, the same way
 * as the Android app's `SignIn.pairingCode`. The app shows it before opening this page and the page
 * shows it too, so the person can see the page is for the sign-in they started, not one another app
 * started. No crypto API needed, so it works on a plain-http home server too.
 */
const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

export function pairingCode(challenge: string): string {
  let n = 0;
  for (const ch of challenge) n = (n * 64 + Math.max(ALPHABET.indexOf(ch), 0)) % 1_000_000;
  const digits = String(n).padStart(6, "0");
  return `${digits.slice(0, 3)} ${digits.slice(3)}`;
}
