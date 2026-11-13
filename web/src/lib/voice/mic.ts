import { useCallback, useEffect, useRef, useState } from "react";
import captureUrl from "./capture.worklet.ts?worker&url";
import type { Smoothed, VoiceFrame } from "./dsp";
import type { FromWorker, ToWorker } from "./voice.worker";

/*
 * The microphone, for the voice trainer: asked for only when practice starts, listened to in a
 * worker (lib/voice/voice.worker.ts), and let go when practice ends. The sound never leaves the
 * browser; a recording is kept only if the person turns that on, and only in this browser.
 */

export type MicProblem = "denied" | "missing" | "busy" | "insecure" | "unsupported" | "failed";

export class MicError extends Error {
  problem: MicProblem;
  constructor(problem: MicProblem) {
    super(problem);
    this.problem = problem;
  }
}

/** What to say when the microphone can't start: kindly, and what to do about it. */
export const MIC_PROBLEM_TEXT: Record<MicProblem, string> = {
  denied: "That's alright: the microphone stays off. You can still follow every step with the timer; the meters just won't move. If you'd like them later, allow the microphone for this site in your browser (often the icon by the address), then try again.",
  missing: "No microphone was found. If you plug one in or turn one on, try again.",
  busy: "Another app seems to be using the microphone. Close it, then try again.",
  insecure: "Browsers only share the microphone with sites on a secure (https) address. Whoever runs this server can set that up.",
  unsupported: "This browser can't share its microphone here. A recent Firefox, Chrome, Edge or Safari can.",
  failed: "The microphone didn't start. Try again, or carry on with the steps and the timer.",
};

export interface Recorded {
  pcm: Int16Array;
  sampleRate: number;
}

export interface Listening {
  readonly sampleRate: number;
  startRecording(): void;
  stopRecording(): Promise<Recorded | null>;
  stop(): void;
}

/** Whether the browser will share the microphone without asking (or won't at all), where it says. */
export async function micPermission(): Promise<"granted" | "denied" | "prompt" | "unknown"> {
  try {
    const status = await navigator.permissions.query({ name: "microphone" as PermissionName });
    return status.state;
  } catch {
    return "unknown";
  }
}

async function openStream(): Promise<MediaStream> {
  if (!window.isSecureContext) throw new MicError("insecure");
  if (!navigator.mediaDevices?.getUserMedia) throw new MicError("unsupported");
  try {
    // The voice as it is: no echo cancelling, noise suppression or automatic gain, which change it.
    return await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false, channelCount: 1 } });
  } catch (e) {
    const name = e instanceof DOMException ? e.name : "";
    if (name === "NotAllowedError" || name === "SecurityError") throw new MicError("denied");
    if (name === "NotFoundError" || name === "OverconstrainedError") throw new MicError("missing");
    if (name === "NotReadableError" || name === "AbortError") throw new MicError("busy");
    throw new MicError("failed");
  }
}

/** Starts listening: [onFrames] gets each 20 ms frame and its smoothed values, a few at a time. */
export async function listen(onFrames: (frames: VoiceFrame[], smooth: Smoothed[]) => void): Promise<Listening> {
  const stream = await openStream();
  const context = new AudioContext();
  if (context.state === "suspended") await context.resume().catch(() => undefined);
  const source = context.createMediaStreamSource(stream);
  const silent = context.createGain();
  silent.gain.value = 0;
  silent.connect(context.destination);
  const worker = new Worker(new URL("./voice.worker.ts", import.meta.url), { type: "module" });
  const send = (m: ToWorker, transfer: Transferable[] = []) => worker.postMessage(m, transfer);
  const waiting = new Map<number, (r: Recorded | null) => void>();
  let nextId = 1;
  worker.onmessage = (e: MessageEvent<FromWorker>) => {
    const m = e.data;
    if (m.type === "frames") onFrames(m.frames, m.smooth);
    else if (m.type === "recording") {
      waiting.get(m.id)?.(m.pcm ? { pcm: m.pcm, sampleRate: m.sampleRate } : null);
      waiting.delete(m.id);
    }
  };

  let capture: AudioNode;
  try {
    // The sound goes from the audio thread straight to the worker.
    await context.audioWorklet.addModule(captureUrl);
    const node = new AudioWorkletNode(context, "samh-voice-capture", { numberOfInputs: 1, numberOfOutputs: 1, channelCount: 1 });
    const channel = new MessageChannel();
    node.port.postMessage({ port: channel.port1 }, [channel.port1]);
    send({ type: "start", sampleRate: context.sampleRate, port: channel.port2 }, [channel.port2]);
    capture = node;
  } catch {
    // Older browsers: through the page instead.
    const node = context.createScriptProcessor(2048, 1, 1);
    node.onaudioprocess = (e) => {
      const samples = new Float32Array(e.inputBuffer.getChannelData(0));
      send({ type: "audio", samples }, [samples.buffer]);
    };
    send({ type: "start", sampleRate: context.sampleRate });
    capture = node;
  }
  source.connect(capture);
  capture.connect(silent);

  let stopped = false;
  return {
    sampleRate: context.sampleRate,
    startRecording: () => send({ type: "record" }),
    stopRecording: () => new Promise<Recorded | null>((resolve) => {
      if (stopped) { resolve(null); return; }
      const id = nextId++;
      waiting.set(id, resolve);
      send({ type: "stopRecording", id });
    }),
    stop: () => {
      if (stopped) return;
      stopped = true;
      waiting.forEach((resolve) => resolve(null));
      waiting.clear();
      source.disconnect();
      capture.disconnect();
      stream.getTracks().forEach((t) => t.stop());
      void context.close().catch(() => undefined);
      worker.terminate();
    },
  };
}

export type MicStatus = "off" | "starting" | "on" | "problem";

/**
 * The microphone for a component: start() when practice starts (it asks the browser then), the
 * latest smoothed values for the meters (at most once a frame), and every 20 ms frame for whoever
 * subscribes. Let go when the component goes.
 */
export function useMic() {
  const [status, setStatus] = useState<MicStatus>("off");
  const [problem, setProblem] = useState<MicProblem | null>(null);
  const [now, setNow] = useState<Smoothed | null>(null);
  const listening = useRef<Listening | null>(null);
  const subscribers = useRef(new Set<(frames: VoiceFrame[], smooth: Smoothed[]) => void>());
  const latest = useRef<Smoothed | null>(null);
  const frame = useRef(0);
  const shown = useRef(0);
  const alive = useRef(true);

  const start = useCallback(async (): Promise<boolean> => {
    if (listening.current) return true;
    setStatus("starting");
    setProblem(null);
    try {
      const l = await listen((frames, smooth) => {
        subscribers.current.forEach((fn) => fn(frames, smooth));
        latest.current = smooth[smooth.length - 1] ?? latest.current;
        // At most about twenty times a second: plenty for meters, and it leaves the page free.
        if (!frame.current && performance.now() - shown.current >= 50) {
          frame.current = requestAnimationFrame(() => { frame.current = 0; shown.current = performance.now(); if (alive.current) setNow(latest.current); });
        }
      });
      if (!alive.current) { l.stop(); return false; }
      listening.current = l;
      setStatus("on");
      return true;
    } catch (e) {
      if (alive.current) {
        setProblem(e instanceof MicError ? e.problem : "failed");
        setStatus("problem");
      }
      return false;
    }
  }, []);

  const stop = useCallback(() => {
    listening.current?.stop();
    listening.current = null;
    latest.current = null;
    setStatus("off");
    setNow(null);
  }, []);

  const subscribe = useCallback((fn: (frames: VoiceFrame[], smooth: Smoothed[]) => void) => {
    subscribers.current.add(fn);
    return () => { subscribers.current.delete(fn); };
  }, []);

  const startRecording = useCallback(() => listening.current?.startRecording(), []);
  const stopRecording = useCallback(async () => (listening.current ? listening.current.stopRecording() : null), []);

  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
      cancelAnimationFrame(frame.current);
      listening.current?.stop();
      listening.current = null;
    };
  }, []);

  return { status, problem, now, start, stop, subscribe, startRecording, stopRecording };
}

export type Mic = ReturnType<typeof useMic>;

/** Keeps the screen awake while [active] (practising), where the browser can. */
export function useWakeLock(active: boolean) {
  useEffect(() => {
    if (!active || !("wakeLock" in navigator)) return;
    let lock: WakeLockSentinel | null = null;
    let done = false;
    const take = async () => {
      try {
        const l = await navigator.wakeLock.request("screen");
        if (done) void l.release().catch(() => undefined);
        else lock = l;
      } catch {
        // Not allowed just now (a hidden tab, low battery): the screen may dim, that's all.
      }
    };
    // The browser lets go when the tab is hidden; take it again on coming back.
    const onVisible = () => { if (document.visibilityState === "visible" && (!lock || lock.released)) void take(); };
    void take();
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      done = true;
      document.removeEventListener("visibilitychange", onVisible);
      void lock?.release().catch(() => undefined);
    };
  }, [active]);
}
