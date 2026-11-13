import { useEffect, useRef, useState } from "react";
import { Mic, MicOff } from "lucide-react";
import { Button } from "./ui";

/*
 * A pitch meter for voice practice. The sound is analysed here in the browser and never
 * recorded or sent anywhere: only the numbers (average, lowest, highest) are kept, and only if
 * the person saves the session.
 */

export interface PitchResult {
  avg: number;
  min: number;
  max: number;
}

/** The fundamental frequency of a frame by autocorrelation, or null when it's silence or noise. */
export function detectPitch(buffer: Float32Array, sampleRate: number): number | null {
  const n = buffer.length;
  let rms = 0;
  for (let i = 0; i < n; i++) rms += buffer[i] * buffer[i];
  rms = Math.sqrt(rms / n);
  if (rms < 0.01) return null;
  // Speaking voices sit between about 60 and 500 Hz.
  const minLag = Math.floor(sampleRate / 500);
  const maxLag = Math.floor(sampleRate / 60);
  let best = -1;
  let bestCorrelation = 0;
  for (let lag = minLag; lag <= maxLag; lag++) {
    let sum = 0;
    for (let i = 0; i < n - lag; i++) sum += buffer[i] * buffer[i + lag];
    const c = sum / (n - lag);
    if (c > bestCorrelation) { bestCorrelation = c; best = lag; }
  }
  if (best < 0 || bestCorrelation < rms * rms * 0.3) return null;
  return sampleRate / best;
}

export function PitchMeter({ target, onResult }: { target?: number; onResult: (r: PitchResult | null) => void }) {
  const [on, setOn] = useState(false);
  const [now, setNow] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const readings = useRef<number[]>([]);
  const stop = useRef<(() => void) | null>(null);

  useEffect(() => () => stop.current?.(), []);

  const start = async () => {
    setError(null);
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: false, noiseSuppression: false } });
      const context = new AudioContext();
      const source = context.createMediaStreamSource(stream);
      const analyser = context.createAnalyser();
      analyser.fftSize = 2048;
      source.connect(analyser);
      const buffer = new Float32Array(analyser.fftSize);
      readings.current = [];
      let frame = 0;
      const tick = () => {
        analyser.getFloatTimeDomainData(buffer);
        const pitch = detectPitch(buffer, context.sampleRate);
        if (pitch) { readings.current.push(pitch); setNow(pitch); }
        frame = requestAnimationFrame(tick);
      };
      tick();
      stop.current = () => {
        cancelAnimationFrame(frame);
        stream.getTracks().forEach((t) => t.stop());
        void context.close();
      };
      setOn(true);
    } catch {
      setError("The microphone couldn't start. Check the browser's permission for this site.");
    }
  };

  const finish = () => {
    stop.current?.();
    stop.current = null;
    setOn(false);
    const r = readings.current;
    if (r.length < 10) { onResult(null); return; }
    // Trim the outliers at each end so one squeak doesn't set the range.
    const sorted = [...r].sort((a, b) => a - b);
    const trimmed = sorted.slice(Math.floor(sorted.length * 0.05), Math.ceil(sorted.length * 0.95));
    onResult({ avg: trimmed.reduce((t, v) => t + v, 0) / trimmed.length, min: trimmed[0], max: trimmed[trimmed.length - 1] });
  };

  const position = now ? Math.min(100, Math.max(0, ((now - 60) / (400 - 60)) * 100)) : null;
  const targetPosition = target ? Math.min(100, Math.max(0, ((target - 60) / (400 - 60)) * 100)) : null;
  return (
    <div className="pitch">
      <div className="pitch-scale" aria-hidden="true">
        {targetPosition != null && <span className="pitch-target" style={{ left: `${targetPosition}%` }} />}
        {position != null && on && <span className="pitch-now" style={{ left: `${position}%` }} />}
      </div>
      <div className="pitch-ends muted small"><span>60 Hz</span><span aria-live="polite">{on && now ? `${Math.round(now)} Hz` : target ? `Aiming around ${target} Hz` : ""}</span><span>400 Hz</span></div>
      <div className="button-row">
        {!on ? <Button variant="tonal" onClick={start}><Mic size={16} /> Measure my pitch</Button> : <Button variant="primary" onClick={finish}><MicOff size={16} /> Stop</Button>}
      </div>
      {error && <p className="notice notice-error">{error}</p>}
      <p className="muted small">Your voice is analysed in this browser and never recorded or sent anywhere. Only the numbers are kept, if you save.</p>
    </div>
  );
}
