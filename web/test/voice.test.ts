// The voice trainer's measures against synthetic sounds (npm test). The same sounds the phone app
// tests with: sines and harmonic tones for pitch, vowels with known formants for resonance,
// harmonic tones with set H1 and H2 for weight, and a glide for intonation.

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  HOP_MS, LoudnessWatch, Smoother, VoiceAnalyzer, analyseSignal, dbfs, formantSpacing, h1MinusH2, intonationSpread, levinson,
  autocorrelate, median, noteName, polyRoots, resample, resonanceOf, summarise, tractLength, weightFromH1H2, yin,
  type VoiceFrame,
} from "../src/lib/voice/dsp.ts";

const SR = 44100;

function sine(hz: number, seconds: number, sr = SR, amp = 0.5): Float32Array {
  const x = new Float32Array(Math.round(seconds * sr));
  for (let i = 0; i < x.length; i++) x[i] = amp * Math.sin((2 * Math.PI * hz * i) / sr);
  return x;
}

/** A tone with the given harmonic amplitudes (the first is the fundamental). */
function harmonic(f0: number, amps: number[], seconds: number, sr = SR): Float32Array {
  const x = new Float32Array(Math.round(seconds * sr));
  for (let i = 0; i < x.length; i++) {
    let s = 0;
    amps.forEach((a, k) => { s += a * Math.sin((2 * Math.PI * (k + 1) * f0 * i) / sr + k * 0.7); });
    x[i] = s;
  }
  return normalise(x, 0.5);
}

function normalise(x: Float32Array, peak: number): Float32Array {
  let max = 0;
  for (const v of x) max = Math.max(max, Math.abs(v));
  if (max > 0) for (let i = 0; i < x.length; i++) x[i] *= peak / max;
  return x;
}

/** A Klatt resonator: a two-pole filter at [hz] with [bandwidth], unity gain at 0 Hz. */
function resonator(x: Float32Array, hz: number, bandwidth: number, sr: number): Float32Array {
  const T = 1 / sr;
  const C = -Math.exp(-2 * Math.PI * bandwidth * T);
  const B = 2 * Math.exp(-Math.PI * bandwidth * T) * Math.cos(2 * Math.PI * hz * T);
  const A = 1 - B - C;
  const y = new Float32Array(x.length);
  let y1 = 0, y2 = 0;
  for (let i = 0; i < x.length; i++) {
    const v = A * x[i] + B * y1 + C * y2;
    y[i] = v; y2 = y1; y1 = v;
  }
  return y;
}

/**
 * A synthetic vowel: a pulse train at [f0] shaped like a glottal source (−12 dB an octave), the lips'
 * radiation (+6 dB), and the formants in cascade.
 */
function vowel(f0: number, formants: number[], seconds: number, sr = SR, bandwidths = [80, 100, 140, 180]): Float32Array {
  const n = Math.round(seconds * sr);
  const source = new Float32Array(n);
  const period = sr / f0;
  for (let p = 0; p < n; p += period) source[Math.floor(p)] = 1;
  // Glottal: two one-pole low-passes.
  let a = 0, b = 0;
  for (let i = 0; i < n; i++) { a = 0.97 * a + source[i]; b = 0.97 * b + a; source[i] = b; }
  // Lips: a first difference.
  let prev = 0;
  for (let i = 0; i < n; i++) { const v = source[i]; source[i] = v - prev; prev = v; }
  let y = source;
  formants.forEach((f, k) => { y = resonator(y, f, bandwidths[k] ?? 200, sr); });
  return normalise(y, 0.5);
}

const voicedPitches = (frames: VoiceFrame[]) => frames.filter((f) => f.voiced && f.pitchHz != null).map((f) => f.pitchHz!);
const near = (actual: number, expected: number, tolerance: number, what: string) =>
  assert.ok(Math.abs(actual - expected) <= tolerance, `${what}: ${actual.toFixed(2)}, expected ${expected} ± ${tolerance}`);
const report: string[] = [];

// ---------------------------------------------------------------- Pitch

test("YIN finds the pitch of sines, at the usual sample rates", () => {
  for (const sr of [44100, 48000, 16000]) {
    for (const hz of [65, 110, 196, 220, 330, 480]) {
      const frame = sine(hz, 0.04, sr);
      const p = yin(frame, sr);
      assert.ok(p, `${hz} Hz at ${sr}: no pitch`);
      near(p.hz, hz, hz * 0.005, `${hz} Hz at ${sr}`);
    }
  }
});

test("YIN finds the fundamental of harmonic tones, including a strong second harmonic", () => {
  for (const [f0, amps] of [[120, [1, 0.5, 0.33, 0.25, 0.2]], [180, [1, 0.8, 0.6]], [210, [0.6, 1, 0.5, 0.3]], [95, [1, 0.9, 0.8, 0.7, 0.6, 0.5]]] as [number, number[]][]) {
    const pitches = voicedPitches(analyseSignal(harmonic(f0, amps, 0.5), SR));
    assert.ok(pitches.length > 15, `${f0} Hz: only ${pitches.length} voiced frames`);
    near(median(pitches), f0, f0 * 0.005, `harmonic tone at ${f0} Hz`);
  }
});

test("silence, a whisper of noise and out-of-range tones aren't voiced", () => {
  assert.equal(yin(new Float32Array(1764), SR), null);
  const noise = new Float32Array(SR);
  let seed = 7;
  for (let i = 0; i < noise.length; i++) { seed = (seed * 16807) % 2147483647; noise[i] = (seed / 2147483647 - 0.5) * 0.5; }
  assert.ok(voicedPitches(analyseSignal(noise, SR)).length <= 1, "noise counted as voice");
  assert.equal(voicedPitches(analyseSignal(sine(200, 0.5, SR, 0.001), SR)).length, 0, "too quiet to count");
  assert.equal(yin(sine(40, 0.04), SR), null, "below 60 Hz");
});

test("notes have their names", () => {
  assert.equal(noteName(440), "A4");
  assert.equal(noteName(261.63), "C4");
  assert.equal(noteName(185), "F♯3");
  assert.equal(noteName(110), "A2");
});

// ---------------------------------------------------------------- Resonance

test("the contract's resonance check values", () => {
  near(formantSpacing([515, 1544, 2573]), 1029, 1, "ΔF");
  near(tractLength(formantSpacing([515, 1544, 2573])), 17.0, 0.05, "L");
  near(resonanceOf([515, 1544, 2573]), 12, 1, "resonance");
  near(tractLength(formantSpacing([600, 1800, 3000])), 14.6, 0.05, "L");
  near(resonanceOf([600, 1800, 3000]), 73, 1, "resonance");
  assert.equal(resonanceOf([300, 900, 1500]), 0, "clamped at 0");
  assert.equal(resonanceOf([1000, 3000, 5000]), 100, "clamped at 100");
});

test("Levinson–Durbin and the root finder recover a known all-pole filter", () => {
  // Poles at 600 and 1800 Hz (radius 0.97) at 11 025 Hz.
  const sr = 11025;
  const poles = [600, 1800].map((f) => ({ r: 0.97, w: (2 * Math.PI * f) / sr }));
  // A(z) = Π (1 − 2r cos w z⁻¹ + r² z⁻²)
  let a = [1];
  for (const p of poles) {
    const q = [1, -2 * p.r * Math.cos(p.w), p.r * p.r];
    const next = new Array(a.length + 2).fill(0);
    a.forEach((x, i) => q.forEach((y, j) => { next[i + j] += x * y; }));
    a = next;
  }
  const roots = polyRoots(a)!;
  const found = roots.filter((z) => z.im > 0).map((z) => (Math.atan2(z.im, z.re) * sr) / (2 * Math.PI)).sort((x, y) => x - y);
  near(found[0], 600, 0.01, "root 1");
  near(found[1], 1800, 0.01, "root 2");
  // Filter white-ish noise through 1/A(z) and estimate A back.
  const n = 8000;
  const x = new Float64Array(n);
  let seed = 3;
  for (let i = 0; i < n; i++) {
    seed = (seed * 16807) % 2147483647;
    let v = seed / 2147483647 - 0.5;
    for (let k = 1; k < a.length; k++) if (i - k >= 0) v -= a[k] * x[i - k];
    x[i] = v;
  }
  const est = levinson(autocorrelate(x, 4), 4)!;
  a.forEach((v, i) => near(est[i], v, 0.08, `a${i}`));
});

for (const [formants, expected] of [[[515, 1544, 2573], 12], [[600, 1800, 3000], 73]] as [number[], number][]) {
  for (const f0 of [110, 150, 210]) {
    test(`a synthetic vowel with formants ${formants.join(", ")} at ${f0} Hz has resonance near ${expected}`, () => {
      const frames = analyseSignal(vowel(f0, formants, 1), SR);
      const values = frames.map((f) => f.resonance).filter((v): v is number => v != null);
      assert.ok(values.length > 20, `only ${values.length} frames with formants`);
      const measured = median(values);
      const withFormants = frames.filter((f) => f.formants);
      const fs = [0, 1, 2].map((k) => median(withFormants.map((f) => f.formants![k])));
      report.push(`vowel ${formants.join("/")} Hz at f0 ${f0} Hz: resonance ${measured.toFixed(1)} (expected ≈${expected}); F1–F3 ${fs.map((v) => Math.round(v)).join(", ")} Hz`);
      near(measured, expected, 8, "resonance");
      formants.forEach((f, k) => near(fs[k], f, f * 0.08, `F${k + 1}`));
    });
  }
}

test("resampling keeps what's under the new Nyquist and removes what's over it", () => {
  const down = resample(sine(1000, 0.2), SR, 11025);
  const mid = down.subarray(200, down.length - 200);
  near(dbfs(mid), dbfs(sine(1000, 0.2)), 0.2, "1 kHz level");
  const low = resample(sine(300, 0.2), SR, 11025);
  const p = yin(low.subarray(200, 641), 11025);
  assert.ok(p);
  near(p.hz, 300, 0.5, "300 Hz pitch after resampling");
  const alias = resample(sine(7000, 0.2), SR, 11025);
  assert.ok(dbfs(alias.subarray(200, alias.length - 200)) < dbfs(sine(7000, 0.2)) - 40, "7 kHz should be gone");
});

// ---------------------------------------------------------------- Weight

test("vocal weight follows H1 − H2", () => {
  for (const [h1h2, weight] of [[12, 0], [6, 33.3], [3, 50], [-3, 83.3], [-6, 100]] as [number, number][]) {
    const a2 = 10 ** (-h1h2 / 20);
    const f0 = 150;
    const x = harmonic(f0, [1, a2, 0.25 * a2, 0.12 * a2], 0.04);
    near(h1MinusH2(x, SR, f0), h1h2, 0.3, `H1 − H2 for ${h1h2} dB`);
    near(weightFromH1H2(h1MinusH2(x, SR, f0)), weight, 2, `weight for ${h1h2} dB`);
    // And through the whole analysis, with YIN's pitch.
    const frames = analyseSignal(harmonic(f0, [1, a2, 0.25 * a2, 0.12 * a2], 0.5), SR);
    const w = median(frames.map((f) => f.weight).filter((v): v is number => v != null));
    near(w, weight, 3, `analysed weight for ${h1h2} dB`);
  }
  assert.equal(weightFromH1H2(20), 0);
  assert.equal(weightFromH1H2(-10), 100);
});

// ---------------------------------------------------------------- Intonation

test("a glide over an octave spreads by 12 / √12 semitones; a steady tone hardly at all", () => {
  const seconds = 2;
  const x = new Float32Array(seconds * SR);
  let phase = 0;
  for (let i = 0; i < x.length; i++) {
    const f = 150 * 2 ** (i / SR / seconds);
    phase += (2 * Math.PI * f) / SR;
    x[i] = 0.4 * Math.sin(phase) + 0.2 * Math.sin(2 * phase) + 0.1 * Math.sin(3 * phase);
  }
  const glide = intonationSpread(voicedPitches(analyseSignal(x, SR)))!;
  report.push(`glide 150→300 Hz: intonation ${glide.toFixed(2)} semitones (expected ${(12 / Math.sqrt(12)).toFixed(2)})`);
  near(glide, 12 / Math.sqrt(12), 0.25, "glide");
  const steady = intonationSpread(voicedPitches(analyseSignal(harmonic(180, [1, 0.5, 0.3], 1), SR)))!;
  assert.ok(steady < 0.1, `steady tone spread ${steady}`);
  assert.equal(intonationSpread([200]), null);
});

// ---------------------------------------------------------------- Loudness

test("loudness is RMS in dBFS", () => {
  near(dbfs(sine(440, 0.1, SR, 0.5)), 20 * Math.log10(0.5 / Math.SQRT2), 0.05, "sine at half scale");
  near(dbfs(sine(440, 0.1, SR, 1)), -3.01, 0.05, "full-scale sine");
  assert.equal(dbfs(new Float32Array(100)), -120);
});

test("the loudness watch notices pushing for a while, not a moment", () => {
  const watch = new LoudnessWatch();
  const hop = HOP_MS / 1000;
  let t = 0;
  let warned = false;
  for (; t < 10; t += hop) warned = watch.push({ t, db: -30, voiced: true }) || warned;
  assert.equal(warned, false, "an ordinary level");
  for (let s = 0; s < 1; s += hop, t += hop) warned = watch.push({ t, db: -8, voiced: true }) || warned;
  assert.equal(warned, false, "a moment of loudness");
  for (let s = 0; s < 8; s += hop, t += hop) warned = watch.push({ t, db: -8, voiced: true }) || warned;
  assert.equal(warned, true, "pushing for eight seconds");
});

// ---------------------------------------------------------------- Streams, smoothing and summaries

test("the analyser gives the same frames whatever size the pieces come in", () => {
  const x = vowel(160, [600, 1800, 3000], 0.5);
  const whole = analyseSignal(x, SR);
  const pieces = new VoiceAnalyzer(SR);
  const streamed: VoiceFrame[] = [];
  for (let i = 0; i < x.length; i += 128) streamed.push(...pieces.push(x.subarray(i, i + 128)));
  assert.equal(streamed.length, whole.length);
  streamed.forEach((f, i) => {
    assert.equal(f.t, whole[i].t);
    assert.equal(f.pitchHz, whole[i].pitchHz);
    assert.equal(f.resonance, whole[i].resonance);
  });
  near(whole.length, (0.5 - 0.04) / (HOP_MS / 1000) + 1, 1, "a frame every 20 ms");
});

test("smoothing takes the median of the last 300 ms, and goes quiet with the voice", () => {
  const s = new Smoother();
  let out = s.push({ t: 0, db: -20, voiced: true, pitchHz: 200, resonance: 50, weight: 40, formants: null });
  for (let i = 1; i <= 14; i++) out = s.push({ t: i * 0.02, db: -20, voiced: true, pitchHz: i === 7 ? 400 : 200, resonance: 50, weight: 40, formants: null });
  assert.equal(out.pitchHz, 200, "one stray frame doesn't move it");
  for (let i = 15; i <= 40; i++) out = s.push({ t: i * 0.02, db: -70, voiced: false, pitchHz: null, resonance: null, weight: null, formants: null });
  assert.equal(out.pitchHz, null);
  assert.equal(out.voiced, false);
});

test("a summary has the pitch, time in range and medians", () => {
  const frames: VoiceFrame[] = [];
  for (let i = 0; i < 100; i++) frames.push({ t: i * 0.02, db: -20, voiced: true, pitchHz: i < 60 ? 200 : 150, resonance: 60, weight: 30, formants: null });
  for (let i = 0; i < 20; i++) frames.push({ t: 2 + i * 0.02, db: -70, voiced: false, pitchHz: null, resonance: null, weight: null, formants: null });
  const s = summarise(frames, { low: 180, high: 220 });
  assert.equal(s.inRange, 60);
  assert.equal(s.resonance, 60);
  assert.equal(s.weight, 30);
  near(s.voicedSeconds, 2, 0.01, "voiced seconds");
  near(s.pitchAvgHz!, 180, 1, "average");
  assert.equal(s.pitchMinHz, 150);
  assert.equal(s.pitchMaxHz, 200);
});

test("what was measured", () => {
  console.log(`\n${report.join("\n")}\n`);
});
