import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { noteName, type Smoothed } from "../../lib/voice/dsp";
import { intonationWord, resonanceWord, weightWord } from "../../lib/voice/program";
import type { Mic } from "../../lib/voice/mic";

/*
 * The trainer's live meters: a pitch trace against the target band, resonance (darker ↔ brighter)
 * with the aim marked, vocal weight (lighter ↔ heavier), intonation and loudness. Each shows its
 * value in words and numbers too; colours are the chart tokens (--c1, --c2, --c3).
 */

export interface TracePoint {
  t: number;
  hz: number | null;
}

const TRACE_SECONDS = 8;

/** The last few seconds of smoothed pitch, for the trace (kept in a ref: it changes 50 times a second). */
export function usePitchTrace(mic: Mic) {
  const trace = useRef<TracePoint[]>([]);
  useEffect(() => mic.subscribe((_, smooth) => {
    const list = trace.current;
    for (const s of smooth) list.push({ t: s.t, hz: s.pitchHz });
    const cut = list.length ? list[list.length - 1].t - TRACE_SECONDS : 0;
    while (list.length && list[0].t < cut) list.shift();
  }), [mic.subscribe]);
  return trace;
}

function css(el: Element, name: string, fallback: string): string {
  return getComputedStyle(el).getPropertyValue(name).trim() || fallback;
}

/** Where a pitch sits against a band: in it, under it or over it. */
export function bandWord(hz: number, band?: { low: number; high: number }, name = "your range"): string | null {
  if (!band) return null;
  if (hz < band.low) return `under ${name}`;
  if (hz > band.high) return `over ${name}`;
  return name === "your range" ? "in your range" : `on ${name}`;
}

/** A pitch trace over the last eight seconds, on a log scale, with the target band shaded. */
export function PitchTrace({ now, trace, band, bandName = "your range", target, label = "Pitch", height = 170 }: {
  now: Smoothed | null; trace: React.RefObject<TracePoint[]>; band?: { low: number; high: number };
  /** What the band is: "your range", or "the note" while matching one. */
  bandName?: string;
  target?: number; label?: string; height?: number;
}) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const box = useRef<HTMLDivElement>(null);
  const [width, setWidth] = useState(0);
  useLayoutEffect(() => {
    const el = box.current;
    if (!el) return;
    setWidth(el.clientWidth);
    const observer = new ResizeObserver(([entry]) => setWidth(Math.floor(entry.contentRect.width)));
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  const bandLow = band?.low;
  const bandHigh = band?.high;
  useEffect(() => {
    const el = canvas.current;
    if (!el || width === 0) return;
    const band = bandLow != null && bandHigh != null ? { low: bandLow, high: bandHigh } : undefined;
    const dpr = window.devicePixelRatio || 1;
    el.width = Math.round(width * dpr);
    el.height = Math.round(height * dpr);
    const g = el.getContext("2d");
    if (!g) return;
    const lo = Math.max(55, (band ? band.low : 100) / 1.6);
    const hi = Math.min(620, (band ? band.high : 260) * 1.6);
    const left = 44;
    const y = (hz: number) => {
      const f = (Math.log(Math.min(hi, Math.max(lo, hz))) - Math.log(lo)) / (Math.log(hi) - Math.log(lo));
      return 8 + (1 - f) * (height - 16);
    };
    let frame = 0;
    let drawn = -1;
    let ticks = 0;
    let colours = { c1: "", c2: "", faint: "", outline: "", card: "" };
    const draw = () => {
      frame = requestAnimationFrame(draw);
      // Only when there's something new (and the theme's colours, now and then, in case they changed).
      const points = trace.current ?? [];
      const latest = points.length ? points[points.length - 1].t : 0;
      if (latest === drawn && ticks % 60 !== 0) { ticks++; return; }
      if (ticks++ % 60 === 0) colours = { c1: css(el, "--c1", "#6a3fd6"), c2: css(el, "--c2", "#00805e"), faint: css(el, "--faint", "#6b7585"), outline: css(el, "--outline", "#c5cdd9"), card: css(el, "--card", "#ffffff") };
      drawn = latest;
      const { c1, c2, faint, outline, card } = colours;
      g.setTransform(dpr, 0, 0, dpr, 0, 0);
      g.clearRect(0, 0, width, height);
      g.font = "11px Lexend, system-ui, sans-serif";
      g.textBaseline = "middle";
      g.fillStyle = faint;
      // Hairlines at round frequencies.
      g.strokeStyle = outline;
      g.globalAlpha = 0.5;
      g.lineWidth = 1;
      for (const hz of [60, 80, 100, 150, 200, 300, 400, 500]) {
        if (hz <= lo || hz >= hi) continue;
        g.beginPath(); g.moveTo(left, y(hz)); g.lineTo(width, y(hz)); g.stroke();
        g.globalAlpha = 1;
        g.fillText(`${hz}`, 4, y(hz));
        g.globalAlpha = 0.5;
      }
      g.globalAlpha = 1;
      if (band) {
        g.fillStyle = c2;
        g.globalAlpha = 0.14;
        g.fillRect(left, y(band.high), width - left, y(band.low) - y(band.high));
        g.globalAlpha = 0.9;
        g.strokeStyle = c2;
        g.setLineDash([4, 4]);
        for (const edge of [band.low, band.high]) { g.beginPath(); g.moveTo(left, y(edge)); g.lineTo(width, y(edge)); g.stroke(); }
        g.setLineDash([]);
        g.globalAlpha = 1;
      }
      if (target) {
        g.strokeStyle = c2;
        g.lineWidth = 2;
        g.beginPath(); g.moveTo(left, y(target)); g.lineTo(width, y(target)); g.stroke();
      }
      const end = latest;
      const x = (t: number) => left + ((t - (end - TRACE_SECONDS)) / TRACE_SECONDS) * (width - left - 10);
      g.strokeStyle = c1;
      g.lineWidth = 3;
      g.lineJoin = "round";
      g.lineCap = "round";
      g.beginPath();
      let drawing = false;
      for (const p of points) {
        if (p.hz == null) { drawing = false; continue; }
        if (drawing) g.lineTo(x(p.t), y(p.hz));
        else { g.moveTo(x(p.t), y(p.hz)); drawing = true; }
      }
      g.stroke();
      const last = points[points.length - 1];
      if (last?.hz != null) {
        g.fillStyle = c1;
        g.strokeStyle = card;
        g.lineWidth = 3;
        g.beginPath(); g.arc(x(last.t), y(last.hz), 6, 0, Math.PI * 2); g.fill(); g.stroke();
      }
    };
    draw();
    return () => cancelAnimationFrame(frame);
  }, [width, height, bandLow, bandHigh, target, trace]);

  const hz = now?.pitchHz;
  const where = hz ? bandWord(hz, band, bandName) : null;
  return (
    <div className="voice-meter voice-pitch">
      <div className="voice-meter-head">
        <span className="voice-meter-label">{label}</span>
        <span className="voice-meter-value">
          {hz ? <><strong>{Math.round(hz)} Hz</strong> <span className="voice-note">{noteName(hz)}</span>{where && <span className={`voice-where ${where.startsWith("in ") || where.startsWith("on ") ? "is-in" : ""}`}>{where}</span>}</> : <span className="muted">Listening…</span>}
        </span>
      </div>
      <div ref={box} className="voice-trace" style={{ height }}>
        <canvas ref={canvas} style={{ width: "100%", height }} aria-hidden="true" />
      </div>
      {band && <div className="voice-meter-foot muted small">{bandName === "your range" ? "Your range" : "Within a semitone"}: {Math.round(band.low)}–{Math.round(band.high)} Hz ({noteName(band.low)}–{noteName(band.high)})</div>}
    </div>
  );
}

/** A value on a 0–100 scale between two words, with an aim marked if there is one. */
export function ScaleMeter({ label, value, aim, left, right, word, color }: {
  label: string; value: number | null | undefined; aim?: number; left: string; right: string; word: (v: number) => string; color: string;
}) {
  const known = value != null;
  const near = known && aim != null ? Math.abs(value - aim) <= 8 : false;
  return (
    <div className="voice-meter">
      <div className="voice-meter-head">
        <span className="voice-meter-label">{label}</span>
        <span className="voice-meter-value">
          {known ? <><strong>{Math.round(value)}</strong> <span>{word(value)}</span>{aim != null && <span className={`voice-where ${near ? "is-in" : ""}`}>{near ? "right around your aim" : `your aim: ${Math.round(aim)}`}</span>}</> : <span className="muted">Listening…</span>}
        </span>
      </div>
      <div className="voice-scale" role="meter" aria-label={label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={known ? Math.round(value) : undefined}
        aria-valuetext={known ? `${Math.round(value)}, ${word(value)}${aim != null ? `; your aim is ${Math.round(aim)}` : ""}` : "Listening"}>
        {aim != null && <span className="voice-aim" style={{ left: `${aim}%` }}><span>your aim</span></span>}
        {known && <span className="voice-dot" style={{ left: `${value}%`, background: color }} />}
      </div>
      <div className="voice-scale-ends muted small"><span>{left}</span><span>{right}</span></div>
    </div>
  );
}

export const ResonanceMeter = ({ value, aim }: { value: number | null | undefined; aim?: number }) => (
  <ScaleMeter label="Resonance" value={value} aim={aim} left="Darker" right="Brighter" word={resonanceWord} color="var(--c1)" />
);

export const WeightMeter = ({ value }: { value: number | null | undefined }) => (
  <ScaleMeter label="Vocal weight" value={value} left="Lighter" right="Heavier" word={weightWord} color="var(--c3)" />
);

/** How much the pitch has moved in the last few seconds, in semitones. */
export function IntonationMeter({ spread }: { spread: number | null }) {
  const width = spread == null ? 0 : Math.min(100, (spread / 6) * 100);
  return (
    <div className="voice-meter">
      <div className="voice-meter-head">
        <span className="voice-meter-label">Intonation</span>
        <span className="voice-meter-value">{spread != null ? <><strong>{spread.toFixed(1)}</strong> semitones · {intonationWord(spread)}</> : <span className="muted">Listening…</span>}</span>
      </div>
      <div className="meter voice-bar" role="meter" aria-label="Intonation, in semitones" aria-valuemin={0} aria-valuemax={6} aria-valuenow={spread ?? undefined}
        aria-valuetext={spread != null ? `${spread.toFixed(1)} semitones, ${intonationWord(spread)}` : "Listening"} style={{ ["--meter-color" as string]: "var(--c2)" }}>
        <div className="meter-fill" style={{ width: `${width}%` }} />
      </div>
      <div className="voice-scale-ends muted small"><span>Steady</span><span>Lively</span></div>
    </div>
  );
}

const loudnessWord = (db: number) => (db < -42 ? "quiet" : db < -18 ? "comfortable" : "strong");

/** Loudness, with a gentle word when it's been pushed for a while. */
export function LoudnessMeter({ db, pushing }: { db: number | null; pushing: boolean }) {
  const shown = db != null && db > -100;
  const width = shown ? Math.min(100, Math.max(0, ((db + 60) / 60) * 100)) : 0;
  return (
    <div className={`voice-meter ${pushing ? "is-pushing" : ""}`}>
      <div className="voice-meter-head">
        <span className="voice-meter-label">Loudness</span>
        <span className="voice-meter-value">{shown ? <><strong>{Math.round(db)}</strong> dBFS · {loudnessWord(db)}</> : <span className="muted">Listening…</span>}</span>
      </div>
      <div className="meter voice-bar" role="meter" aria-label="Loudness" aria-valuemin={-60} aria-valuemax={0} aria-valuenow={shown ? Math.round(db) : undefined}
        aria-valuetext={shown ? `${loudnessWord(db)}, ${Math.round(db)} decibels` : "Listening"} style={{ ["--meter-color" as string]: pushing ? "var(--c3)" : "var(--c1)" }}>
        <div className="meter-fill" style={{ width: `${width}%`, transition: "width 0.1s linear" }} />
      </div>
      {pushing && <p className="voice-push small" role="status">You've been quite loud for a while. Try easing off: the microphone hears you well at a comfortable level.</p>}
    </div>
  );
}

/** A polite live region that speaks at most every [every] ms, and only when something changed. */
export function Announcer({ text, every = 6000 }: { text: string; every?: number }) {
  const [said, setSaid] = useState("");
  const latest = useRef(text);
  latest.current = text;
  useEffect(() => {
    const id = setInterval(() => setSaid((was) => (latest.current !== was ? latest.current : was)), every);
    return () => clearInterval(id);
  }, [every]);
  return <p className="sr-only" aria-live="polite">{said}</p>;
}
