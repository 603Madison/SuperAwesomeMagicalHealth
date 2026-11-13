/*
 * How the voice trainer listens: pure functions over sound, tested with synthetic signals
 * (web/test/voice.test.ts) so the web and the phone app give the same numbers. The measures are
 * the contract's (shared/…/Voice.kt):
 *
 * - pitch: YIN on 40 ms frames, 60–500 Hz; a frame is voiced when YIN's dip is under 0.15 and it's
 *   loud enough (VOICED_MIN_DB);
 * - resonance (0 darker … 100 brighter): F1–F3 by LPC (order 12, on sound resampled to 11 025 Hz,
 *   25 ms Hamming frames, voiced frames only); ΔF = Σ Fn·(n − ½) / Σ (n − ½)², L = 35 000 / (2·ΔF) cm,
 *   resonance = (17.5 − L) / 4 × 100, clamped;
 * - vocal weight (0 lighter … 100 heavier): H1 − H2 in dB at f0 and 2·f0, weight = (12 − (H1 − H2)) / 18 × 100;
 * - intonation: the standard deviation of voiced pitch, in semitones from its median;
 * - loudness: RMS in dBFS.
 *
 * Details the contract leaves open, kept here so the apps can match: the 25 ms LPC frame is the
 * middle of the 40 ms pitch frame; it gets a first-difference pre-emphasis (0.97) before the
 * window; formants are the LPC polynomial's roots above 200 Hz (and above 1.15·f0) with bandwidths
 * under 500 Hz; H1 and H2 are read from a Hann-windowed spectrum, at the strongest point within
 * 2% of f0 and 2·f0. Live values are medians over the last 300 ms. Frames are 40 ms every 20 ms.
 */

export const FRAME_MS = 40;
export const HOP_MS = 20;
export const PITCH_MIN_HZ = 60;
export const PITCH_MAX_HZ = 500;
export const YIN_THRESHOLD = 0.15;
/** Quieter than this (RMS, dBFS), a frame isn't voiced: it's the room, not the person. */
export const VOICED_MIN_DB = -50;
export const LPC_RATE = 11025;
export const LPC_ORDER = 12;
export const LPC_FRAME_MS = 25;
export const PRE_EMPHASIS = 0.97;
export const FORMANT_MIN_HZ = 200;
export const FORMANT_MAX_BANDWIDTH = 500;
export const SMOOTH_MS = 300;
/** Recordings are kept at this rate: plenty for a speaking voice, and a third of the size of 48 kHz. */
export const RECORDING_RATE = 16000;

const clamp = (v: number, lo: number, hi: number) => Math.min(hi, Math.max(lo, v));

// ---------------------------------------------------------------- Basics

export function rms(x: ArrayLike<number>): number {
  let s = 0;
  for (let i = 0; i < x.length; i++) s += x[i] * x[i];
  return x.length ? Math.sqrt(s / x.length) : 0;
}

/** Loudness: RMS in dBFS (a full-scale square wave is 0; silence is −120). */
export function dbfs(x: ArrayLike<number>): number {
  const r = rms(x);
  return r > 1e-6 ? 20 * Math.log10(r) : -120;
}

export function median(values: number[]): number {
  if (values.length === 0) return NaN;
  const s = [...values].sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
}

const windows = new Map<string, Float64Array>();
function windowOf(kind: "hann" | "hamming", n: number): Float64Array {
  const key = `${kind}${n}`;
  let w = windows.get(key);
  if (!w) {
    w = new Float64Array(n);
    const a = kind === "hann" ? 0.5 : 0.54;
    for (let i = 0; i < n; i++) w[i] = a - (1 - a) * Math.cos((2 * Math.PI * i) / (n - 1));
    if (windows.size > 16) windows.clear();
    windows.set(key, w);
  }
  return w;
}

// ---------------------------------------------------------------- Pitch: YIN

export interface Pitch {
  hz: number;
  /** YIN's dip at the period, 0 (clean) … 1: under YIN_THRESHOLD counts as voiced. */
  dip: number;
}

/**
 * YIN (de Cheveigné & Kawahara, 2002): the cumulative-mean-normalised difference function, the
 * first dip under the threshold (followed to its bottom), refined by a parabola. Null when no
 * period between [minHz] and [maxHz] dips under the threshold.
 */
export function yin(frame: ArrayLike<number>, sampleRate: number, minHz = PITCH_MIN_HZ, maxHz = PITCH_MAX_HZ, threshold = YIN_THRESHOLD): Pitch | null {
  const tauMin = Math.max(2, Math.floor(sampleRate / maxHz));
  const tauMax = Math.min(Math.ceil(sampleRate / minHz), Math.floor(frame.length / 2));
  if (tauMax <= tauMin + 1) return null;
  const w = frame.length - tauMax;
  const cmnd = new Float64Array(tauMax + 1);
  cmnd[0] = 1;
  let running = 0;
  let tau = -1;
  // The difference function only as far as it's needed: to the bottom of the first dip, and one past.
  for (let t = 1; t <= tauMax; t++) {
    let d = 0;
    for (let j = 0; j < w; j++) {
      const diff = frame[j] - frame[j + t];
      d += diff * diff;
    }
    running += d;
    cmnd[t] = running > 0 ? (d * t) / running : 1;
    if (tau < 0) {
      if (t >= tauMin && cmnd[t] < threshold) tau = t;
    } else if (cmnd[t] < cmnd[tau]) {
      tau = t;
    } else {
      break;
    }
  }
  if (tau < 0) return null;
  let period = tau;
  if (tau > 1 && tau < tauMax) {
    const a = cmnd[tau - 1];
    const b = cmnd[tau];
    const c = cmnd[tau + 1];
    const denom = a - 2 * b + c;
    if (denom > 0) period = tau + (a - c) / (2 * denom);
  }
  const hz = sampleRate / period;
  if (hz < minHz || hz > maxHz) return null;
  return { hz, dip: cmnd[tau] };
}

// ---------------------------------------------------------------- Resampling

/**
 * Windowed-sinc resampling (a Hann-windowed ideal low-pass at 90% of the lower Nyquist, ten zero
 * crossings each side). Batch: the ends are a little quieter, so callers use the middle.
 */
export function resample(x: ArrayLike<number>, from: number, to: number): Float32Array {
  if (from === to) return Float32Array.from(x);
  const ratio = to / from;
  const n = Math.floor(x.length * ratio);
  const out = new Float32Array(n);
  const { table, half } = kernel(from, to);
  for (let i = 0; i < n; i++) {
    const p = i / ratio;
    const k0 = Math.max(0, Math.ceil(p - half));
    const k1 = Math.min(x.length - 1, Math.floor(p + half));
    let s = 0;
    for (let k = k0; k <= k1; k++) {
      const t = Math.abs(k - p) * KERNEL_STEPS;
      const j = Math.floor(t);
      s += x[k] * (table[j] + (t - j) * (table[j + 1] - table[j]));
    }
    out[i] = s;
  }
  return out;
}

/** The resampling kernel, tabulated KERNEL_STEPS times an input sample (so no trigonometry per tap). */
const KERNEL_STEPS = 512;
const kernels = new Map<string, { table: Float64Array; half: number }>();
function kernel(from: number, to: number) {
  const key = `${from}>${to}`;
  let k = kernels.get(key);
  if (!k) {
    const fc = (0.9 * Math.min(from, to)) / 2 / from; // the cut-off, in cycles per input sample
    const half = 10 / (2 * fc); // input samples to the tenth zero crossing
    const table = new Float64Array(Math.ceil(half * KERNEL_STEPS) + 2);
    for (let j = 0; j < table.length; j++) {
      const t = j / KERNEL_STEPS;
      if (t > half) continue;
      const arg = 2 * fc * t;
      const sinc = arg === 0 ? 1 : Math.sin(Math.PI * arg) / (Math.PI * arg);
      table[j] = 2 * fc * sinc * 0.5 * (1 + Math.cos((Math.PI * t) / half));
    }
    k = { table, half };
    kernels.set(key, k);
  }
  return k;
}

/** Sound as 16-bit samples, for keeping. */
export function toInt16(x: ArrayLike<number>): Int16Array {
  const out = new Int16Array(x.length);
  for (let i = 0; i < x.length; i++) out[i] = Math.round(clamp(x[i], -1, 1) * 32767);
  return out;
}

// ---------------------------------------------------------------- LPC and formants

export function autocorrelate(x: ArrayLike<number>, order: number): Float64Array {
  const r = new Float64Array(order + 1);
  for (let k = 0; k <= order; k++) {
    let s = 0;
    for (let n = k; n < x.length; n++) s += x[n] * x[n - k];
    r[k] = s;
  }
  return r;
}

/** Levinson–Durbin: the predictor A(z) = 1 + a1·z⁻¹ + … + ap·z⁻ᵖ from autocorrelations [r]. */
export function levinson(r: ArrayLike<number>, order: number): Float64Array | null {
  let a = new Float64Array(order + 1);
  a[0] = 1;
  let error = r[0];
  if (!(error > 0)) return null;
  for (let i = 1; i <= order; i++) {
    let acc = r[i];
    for (let j = 1; j < i; j++) acc += a[j] * r[i - j];
    const k = -acc / error;
    const next = Float64Array.from(a);
    for (let j = 1; j < i; j++) next[j] = a[j] + k * a[i - j];
    next[i] = k;
    a = next;
    error *= 1 - k * k;
    if (!(error > 0)) return null;
  }
  return a;
}

/** The complex roots of z^n + c[1]·z^(n−1) + … + c[n] (c[0] is 1), by the Aberth–Ehrlich method. */
export function polyRoots(c: ArrayLike<number>): { re: number; im: number }[] | null {
  const n = c.length - 1;
  if (n < 1) return [];
  const re = new Float64Array(n);
  const im = new Float64Array(n);
  // Start on a circle inside the unit circle, turned off the real axis.
  for (let k = 0; k < n; k++) {
    const angle = (2 * Math.PI * k) / n + 0.4;
    re[k] = 0.9 * Math.cos(angle);
    im[k] = 0.9 * Math.sin(angle);
  }
  for (let iteration = 0; iteration < 500; iteration++) {
    let biggest = 0;
    for (let k = 0; k < n; k++) {
      // p(z) and p'(z) by Horner.
      let pr = 1, pi = 0, dr = 0, di = 0;
      const zr = re[k], zi = im[k];
      for (let j = 1; j <= n; j++) {
        const ndr = dr * zr - di * zi + pr;
        const ndi = dr * zi + di * zr + pi;
        dr = ndr; di = ndi;
        const npr = pr * zr - pi * zi + c[j];
        const npi = pr * zi + pi * zr;
        pr = npr; pi = npi;
      }
      const dd = dr * dr + di * di;
      if (dd === 0) continue;
      // ratio = p / p'
      const qr = (pr * dr + pi * di) / dd;
      const qi = (pi * dr - pr * di) / dd;
      // sum of 1 / (z_k − z_j)
      let sr = 0, si = 0;
      for (let j = 0; j < n; j++) {
        if (j === k) continue;
        const ar = zr - re[j], ai = zi - im[j];
        const aa = ar * ar + ai * ai;
        if (aa === 0) continue;
        sr += ar / aa;
        si += -ai / aa;
      }
      // w = ratio / (1 − ratio·sum)
      const br = 1 - (qr * sr - qi * si);
      const bi = -(qr * si + qi * sr);
      const bb = br * br + bi * bi;
      if (bb === 0) continue;
      const wr = (qr * br + qi * bi) / bb;
      const wi = (qi * br - qr * bi) / bb;
      re[k] -= wr;
      im[k] -= wi;
      biggest = Math.max(biggest, Math.hypot(wr, wi));
    }
    if (biggest < 1e-12) break;
    if (!Number.isFinite(biggest)) return null;
  }
  return Array.from(re, (r, k) => ({ re: r, im: im[k] }));
}

export interface Formant {
  hz: number;
  bandwidth: number;
}

/** Formant candidates of a frame already at [sampleRate], lowest first: LPC roots, as above. */
export function lpcFormants(x: ArrayLike<number>, sampleRate: number, order = LPC_ORDER, f0?: number): Formant[] | null {
  const n = x.length;
  const w = windowOf("hamming", n);
  const y = new Float64Array(n);
  for (let i = n - 1; i >= 0; i--) y[i] = (x[i] - (i > 0 ? PRE_EMPHASIS * x[i - 1] : 0)) * w[i];
  const r = autocorrelate(y, order);
  r[0] *= 1 + 1e-9; // a whisper of white noise, for stability on very clean sounds
  const a = levinson(r, order);
  if (!a) return null;
  const roots = polyRoots(a);
  if (!roots) return null;
  const lowest = Math.max(FORMANT_MIN_HZ, f0 ? f0 * 1.15 : 0);
  return roots
    .filter((z) => z.im > 1e-9)
    .map((z) => ({ hz: (Math.atan2(z.im, z.re) * sampleRate) / (2 * Math.PI), bandwidth: (-Math.log(Math.hypot(z.re, z.im)) * sampleRate) / Math.PI }))
    .filter((f) => f.hz >= lowest && f.hz <= sampleRate / 2 - 50 && f.bandwidth > 0 && f.bandwidth <= FORMANT_MAX_BANDWIDTH)
    .sort((p, q) => p.hz - q.hz);
}

/** F1–F3 of a voiced frame at any rate: its middle 25 ms, resampled to 11 025 Hz. Null when they can't be told apart. */
export function formantsOf(frame: ArrayLike<number>, sampleRate: number, f0?: number): [number, number, number] | null {
  const x = resample(frame, sampleRate, LPC_RATE);
  const len = Math.round((LPC_FRAME_MS / 1000) * LPC_RATE);
  const start = Math.max(0, Math.floor((x.length - len) / 2));
  const seg = x.subarray(start, Math.min(x.length, start + len));
  const found = lpcFormants(seg, LPC_RATE, LPC_ORDER, f0);
  if (!found || found.length < 3) return null;
  const [f1, f2, f3] = found.map((f) => f.hz);
  if (f1 > 1300 || f2 < 500 || f2 > 3500 || f3 < 1400 || f3 > 5000) return null;
  return [f1, f2, f3];
}

/** ΔF: the formants' spacing, by least squares through the origin (F1 counts least). */
export function formantSpacing(f: readonly number[]): number {
  let num = 0;
  let den = 0;
  for (let n = 1; n <= 3; n++) {
    const k = n - 0.5;
    num += f[n - 1] * k;
    den += k * k;
  }
  return num / den;
}

/** The apparent vocal-tract length in cm. */
export const tractLength = (spacing: number) => 35000 / (2 * spacing);

/** Resonance, 0 darker … 100 brighter, from a tract length. */
export const resonanceFromLength = (cm: number) => clamp(((17.5 - cm) / (17.5 - 13.5)) * 100, 0, 100);

export const resonanceOf = (formants: readonly number[]) => resonanceFromLength(tractLength(formantSpacing(formants)));

// ---------------------------------------------------------------- Vocal weight: H1 − H2

/** The level in dB of [hz] in a Hann-windowed frame. */
export function levelAt(frame: ArrayLike<number>, sampleRate: number, hz: number): number {
  const n = frame.length;
  const w = windowOf("hann", n);
  const step = (2 * Math.PI * hz) / sampleRate;
  const cs = Math.cos(step), sn = Math.sin(step);
  let c = 1, s = 0, re = 0, im = 0;
  for (let i = 0; i < n; i++) {
    const v = frame[i] * w[i];
    re += v * c;
    im -= v * s;
    const nc = c * cs - s * sn;
    s = s * cs + c * sn;
    c = nc;
  }
  return 20 * Math.log10(Math.hypot(re, im) + 1e-12);
}

/** H1 − H2 in dB: the first harmonic's level less the second's, each at its strongest within 2%. */
export function h1MinusH2(frame: ArrayLike<number>, sampleRate: number, f0: number): number {
  const peak = (hz: number) => {
    let best = -Infinity;
    for (let k = -4; k <= 4; k++) best = Math.max(best, levelAt(frame, sampleRate, hz * (1 + k * 0.005)));
    return best;
  };
  return peak(f0) - peak(2 * f0);
}

/** Vocal weight, 0 lighter … 100 heavier. */
export const weightFromH1H2 = (h1h2: number) => clamp(((12 - h1h2) / 18) * 100, 0, 100);

// ---------------------------------------------------------------- Intonation

/** How much the pitch moves: the standard deviation, in semitones from its median. */
export function intonationSpread(pitches: readonly number[]): number | null {
  if (pitches.length < 3) return null;
  const m = median([...pitches]);
  const st = pitches.map((p) => 12 * Math.log2(p / m));
  const mean = st.reduce((a, b) => a + b, 0) / st.length;
  return Math.sqrt(st.reduce((a, b) => a + (b - mean) * (b - mean), 0) / st.length);
}

// ---------------------------------------------------------------- Notes

const NOTE_NAMES = ["C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"];

/** "A4" for 440 Hz. */
export function noteName(hz: number): string {
  const midi = Math.round(69 + 12 * Math.log2(hz / 440));
  return `${NOTE_NAMES[((midi % 12) + 12) % 12]}${Math.floor(midi / 12) - 1}`;
}

export const semitones = (from: number, to: number) => 12 * Math.log2(to / from);
export const shiftHz = (hz: number, st: number) => hz * 2 ** (st / 12);

// ---------------------------------------------------------------- One frame, and a stream of them

export interface VoiceFrame {
  /** Seconds since listening began, at the frame's end. */
  t: number;
  db: number;
  voiced: boolean;
  pitchHz: number | null;
  resonance: number | null;
  weight: number | null;
  formants: [number, number, number] | null;
}

/** Everything about one 40 ms frame. */
export function analyseFrame(frame: ArrayLike<number>, sampleRate: number, t = 0): VoiceFrame {
  const db = dbfs(frame);
  const quiet: VoiceFrame = { t, db, voiced: false, pitchHz: null, resonance: null, weight: null, formants: null };
  if (db < VOICED_MIN_DB) return quiet;
  const pitch = yin(frame, sampleRate);
  if (!pitch) return quiet;
  const weight = weightFromH1H2(h1MinusH2(frame, sampleRate, pitch.hz));
  const formants = formantsOf(frame, sampleRate, pitch.hz);
  return { t, db, voiced: true, pitchHz: pitch.hz, weight, formants, resonance: formants ? resonanceOf(formants) : null };
}

/** Takes sound in pieces of any size and gives a frame every HOP_MS. */
export class VoiceAnalyzer {
  readonly sampleRate: number;
  readonly frameLength: number;
  readonly hopLength: number;
  private ring: Float32Array;
  private at = 0;
  private total = 0;
  private sinceHop = 0;

  constructor(sampleRate: number, hopMs = HOP_MS) {
    this.sampleRate = sampleRate;
    this.frameLength = Math.round((FRAME_MS / 1000) * sampleRate);
    this.hopLength = Math.round((hopMs / 1000) * sampleRate);
    this.ring = new Float32Array(this.frameLength);
  }

  push(chunk: ArrayLike<number>): VoiceFrame[] {
    const out: VoiceFrame[] = [];
    for (let i = 0; i < chunk.length; i++) {
      this.ring[this.at] = chunk[i];
      this.at = (this.at + 1) % this.frameLength;
      this.total++;
      this.sinceHop++;
      if (this.sinceHop >= this.hopLength && this.total >= this.frameLength) {
        this.sinceHop = 0;
        const frame = new Float32Array(this.frameLength);
        frame.set(this.ring.subarray(this.at));
        frame.set(this.ring.subarray(0, this.at), this.frameLength - this.at);
        out.push(analyseFrame(frame, this.sampleRate, this.total / this.sampleRate));
      }
    }
    return out;
  }
}

/** All the frames of a whole sound (for tests and recordings). */
export function analyseSignal(x: ArrayLike<number>, sampleRate: number): VoiceFrame[] {
  return new VoiceAnalyzer(sampleRate).push(x);
}

export interface Smoothed {
  t: number;
  db: number;
  voiced: boolean;
  pitchHz: number | null;
  resonance: number | null;
  weight: number | null;
}

/** Live values: medians over the last 300 ms, so the meters move calmly. */
export class Smoother {
  private frames: VoiceFrame[] = [];
  private windowSec: number;

  constructor(windowSec = SMOOTH_MS / 1000) {
    this.windowSec = windowSec;
  }

  push(frame: VoiceFrame): Smoothed {
    this.frames.push(frame);
    while (this.frames.length && this.frames[0].t <= frame.t - this.windowSec) this.frames.shift();
    const voiced = this.frames.filter((f) => f.voiced);
    const enough = voiced.length >= Math.max(2, Math.ceil(this.frames.length * 0.3));
    const of = (pick: (f: VoiceFrame) => number | null) => {
      const v = voiced.map(pick).filter((x): x is number => x != null);
      return enough && v.length ? median(v) : null;
    };
    return {
      t: frame.t,
      db: median(this.frames.map((f) => f.db)),
      voiced: enough,
      pitchHz: of((f) => f.pitchHz),
      resonance: of((f) => f.resonance),
      weight: of((f) => f.weight),
    };
  }
}

// ---------------------------------------------------------------- A stretch of practice, summed up

export interface VoiceSummary {
  /** Voiced time, in seconds. */
  voicedSeconds: number;
  pitchAvgHz?: number;
  pitchMinHz?: number;
  pitchMaxHz?: number;
  /** Share of voiced time inside [low, high], 0–100. */
  inRange?: number;
  resonance?: number;
  weight?: number;
  intonation?: number;
  loudnessDb?: number;
}

/**
 * The numbers for an exercise or a baseline: pitch average and range with the outer 5% at each end
 * left out (so one squeak doesn't set the range), time in the target range, and medians of the rest.
 */
export function summarise(frames: readonly VoiceFrame[], range?: { low: number; high: number }, hopSec = HOP_MS / 1000): VoiceSummary {
  const voiced = frames.filter((f) => f.voiced && f.pitchHz != null);
  const out: VoiceSummary = { voicedSeconds: Math.round(voiced.length * hopSec * 10) / 10 };
  if (voiced.length < 5) return out;
  const pitches = voiced.map((f) => f.pitchHz!);
  const sorted = [...pitches].sort((a, b) => a - b);
  const trimmed = sorted.slice(Math.floor(sorted.length * 0.05), Math.max(Math.floor(sorted.length * 0.05) + 1, Math.ceil(sorted.length * 0.95)));
  out.pitchAvgHz = trimmed.reduce((a, b) => a + b, 0) / trimmed.length;
  out.pitchMinHz = trimmed[0];
  out.pitchMaxHz = trimmed[trimmed.length - 1];
  if (range) out.inRange = Math.round((100 * pitches.filter((p) => p >= range.low && p <= range.high).length) / pitches.length);
  const res = voiced.map((f) => f.resonance).filter((v): v is number => v != null);
  if (res.length >= 3) out.resonance = median(res);
  const weight = voiced.map((f) => f.weight).filter((v): v is number => v != null);
  if (weight.length >= 3) out.weight = median(weight);
  out.intonation = intonationSpread(pitches) ?? undefined;
  out.loudnessDb = median(voiced.map((f) => f.db));
  return out;
}

// ---------------------------------------------------------------- Pushing too hard

/**
 * A gentle watch on loudness: true when most of the last 8 seconds of voice have been well above
 * the person's own usual (or near the top of the scale), for at least 4 seconds of it.
 */
export class LoudnessWatch {
  private recent: { t: number; db: number }[] = [];
  private all: number[] = [];
  private windowSec: number;
  private minSec: number;
  private hopSec: number;

  constructor(windowSec = 8, minSec = 4, hopSec = HOP_MS / 1000) {
    this.windowSec = windowSec;
    this.minSec = minSec;
    this.hopSec = hopSec;
  }

  /** The person's usual level so far, once there's enough voice to tell. */
  get usual(): number | null {
    return this.all.length * this.hopSec >= 5 ? median(this.all.slice(-1500)) : null;
  }

  push(frame: { t: number; db: number; voiced: boolean }): boolean {
    if (frame.voiced) {
      this.recent.push({ t: frame.t, db: frame.db });
      this.all.push(frame.db);
      if (this.all.length > 3000) this.all.splice(0, 1500);
    }
    while (this.recent.length && this.recent[0].t <= frame.t - this.windowSec) this.recent.shift();
    if (this.recent.length * this.hopSec < this.minSec) return false;
    const usual = this.usual;
    const loud = usual == null ? -14 : Math.max(usual + 9, -18);
    return this.recent.filter((r) => r.db > loud).length >= this.recent.length * 0.75;
  }
}
