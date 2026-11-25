import { useEffect, useState } from "react";
import { VOICE_PITCH, VOICE_RATE, chooseVoice } from "./meditation";

/*
 * The browser's side of guided meditation (pages/MeditationPlayer.tsx): reading aloud with a voice
 * on this device only, a soft chime made right here, and keeping the screen awake. Nothing is
 * fetched and nothing leaves the device: no audio files, no cloud voices (lib/meditation.ts's
 * chooseVoice turns those away).
 */

export const speechSupported = (): boolean => typeof window !== "undefined" && "speechSynthesis" in window && "SpeechSynthesisUtterance" in window;

/** The language to read in: the person's own where it's the page's, else the page's. */
function wantedLang(): string {
  const page = document.documentElement.lang || "en";
  const own = navigator.language || page;
  return own.toLowerCase().startsWith(page.toLowerCase().split("-")[0]) ? own : page;
}

/**
 * The voice this device can read with offline, once the browser has listed its voices (they
 * often come a moment after the page): null while it's still looking ([ready] false) and when
 * there's none.
 */
export function useLocalVoice(): { voice: SpeechSynthesisVoice | null; ready: boolean } {
  const [state, setState] = useState<{ voice: SpeechSynthesisVoice | null; ready: boolean }>(() => pick(false));
  useEffect(() => {
    if (!speechSupported()) { setState({ voice: null, ready: true }); return; }
    const update = () => setState(pick(true));
    window.speechSynthesis.addEventListener?.("voiceschanged", update);
    // Some browsers never say: look again after a moment, and take what there is.
    const timer = window.setTimeout(update, 1500);
    if (window.speechSynthesis.getVoices().length > 0) update();
    return () => {
      window.speechSynthesis.removeEventListener?.("voiceschanged", update);
      window.clearTimeout(timer);
    };
  }, []);
  return state;
}

function pick(settled: boolean): { voice: SpeechSynthesisVoice | null; ready: boolean } {
  if (!speechSupported()) return { voice: null, ready: true };
  const voices = window.speechSynthesis.getVoices();
  return { voice: chooseVoice(voices, wantedLang()), ready: settled || voices.length > 0 };
}

/**
 * Reads [text] (one sentence) with [voice], calm and a little low, and calls [onDone] when it's
 * finished (or the browser gave up on it). The function it gives back stops it without calling
 * [onDone].
 */
export function speak(text: string, voice: SpeechSynthesisVoice, onDone: () => void): () => void {
  let live = true;
  const u = new SpeechSynthesisUtterance(text);
  u.voice = voice;
  u.lang = voice.lang;
  u.rate = VOICE_RATE;
  u.pitch = VOICE_PITCH;
  u.volume = 1;
  const done = () => { if (live) { live = false; onDone(); } };
  u.onend = done;
  u.onerror = done;
  window.speechSynthesis.speak(u);
  return () => { live = false; };
}

/** Stops whatever's being read. */
export function hush() {
  if (speechSupported()) window.speechSynthesis.cancel();
}

// ---- The chime ----

/** A sound context, made on a tap (browsers only let a page make sound after one). Null where there's none. */
export function audioContext(): AudioContext | null {
  try {
    const Ctx = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    return Ctx ? new Ctx() : null;
  } catch {
    return null;
  }
}

/**
 * A soft bell, made here from two sine tones that fade out slowly: higher to begin, lower at the
 * end. Quiet, and over in about four seconds.
 */
export function chime(ctx: AudioContext | null, which: "start" | "end") {
  if (!ctx) return;
  try {
    if (ctx.state === "suspended") void ctx.resume();
    const now = ctx.currentTime + 0.05;
    const base = which === "start" ? 528 : 396;
    const master = ctx.createGain();
    master.gain.value = 0.18;
    master.connect(ctx.destination);
    for (const [ratio, level] of [[1, 1], [2.01, 0.28], [3, 0.08]] as const) {
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.type = "sine";
      osc.frequency.value = base * ratio;
      gain.gain.setValueAtTime(0, now);
      gain.gain.linearRampToValueAtTime(level, now + 0.06);
      gain.gain.exponentialRampToValueAtTime(0.0001, now + 4);
      osc.connect(gain).connect(master);
      osc.start(now);
      osc.stop(now + 4.1);
    }
  } catch {
    // No sound: the words still show.
  }
}

// ---- Keeping the screen on ----

/**
 * Keeps the screen awake while [on], where the browser can (the Wake Lock API), asking again when
 * the page comes back into view (browsers let go of it while it's hidden).
 */
export function useWakeLock(on: boolean) {
  useEffect(() => {
    if (!on || !("wakeLock" in navigator)) return;
    let lock: WakeLockSentinel | null = null;
    let live = true;
    const ask = async () => {
      try {
        if (document.visibilityState !== "visible") return;
        const next = await navigator.wakeLock.request("screen");
        if (live) lock = next; else void next.release();
      } catch {
        // Not allowed just now (low battery, a setting): the screen may dim as usual.
      }
    };
    const again = () => { if (document.visibilityState === "visible") void ask(); };
    void ask();
    document.addEventListener("visibilitychange", again);
    return () => {
      live = false;
      document.removeEventListener("visibilitychange", again);
      void lock?.release().catch(() => undefined);
    };
  }, [on]);
}

// ---- Voice or text, remembered in this browser ----

const VOICE_KEY = "samh.meditation.voice";

/** Whether the person chose to read along without the voice, in this browser. */
export function textOnlyChosen(): boolean {
  try { return localStorage.getItem(VOICE_KEY) === "text"; } catch { return false; }
}

export function chooseTextOnly(textOnly: boolean) {
  try { localStorage.setItem(VOICE_KEY, textOnly ? "text" : "voice"); } catch { /* this browser just won't remember it */ }
}

/** Whether the person asked for less motion (the pacer's words then show without the moving circle). */
export function useReducedMotion(): boolean {
  const query = "(prefers-reduced-motion: reduce)";
  const [reduced, setReduced] = useState(() => typeof window !== "undefined" && !!window.matchMedia?.(query).matches);
  useEffect(() => {
    const list = window.matchMedia?.(query);
    if (!list) return;
    const change = () => setReduced(list.matches);
    list.addEventListener?.("change", change);
    return () => list.removeEventListener?.("change", change);
  }, []);
  return reduced;
}
