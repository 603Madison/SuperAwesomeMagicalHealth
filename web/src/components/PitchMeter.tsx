import { useEffect, useRef } from "react";
import { Mic, MicOff } from "lucide-react";
import { Button } from "./ui";
import { noteName, summarise, type VoiceFrame } from "../lib/voice/dsp";
import { MIC_PROBLEM_TEXT, useMic } from "../lib/voice/mic";

/*
 * A pitch meter for logging voice practice by hand. The sound is analysed in this browser, the
 * same way as the voice trainer (lib/voice: YIN, in a worker), and never recorded or sent
 * anywhere: only the numbers (average, lowest, highest) are kept, and only if the person saves.
 */

export interface PitchResult {
  avg: number;
  min: number;
  max: number;
}

export function PitchMeter({ target, onResult }: { target?: number; onResult: (r: PitchResult | null) => void }) {
  const mic = useMic();
  const frames = useRef<VoiceFrame[]>([]);
  useEffect(() => mic.subscribe((list) => { frames.current.push(...list); }), [mic.subscribe]);
  const on = mic.status === "on";

  const start = async () => {
    frames.current = [];
    await mic.start();
  };

  const finish = () => {
    mic.stop();
    const s = summarise(frames.current);
    onResult(s.pitchAvgHz != null ? { avg: s.pitchAvgHz, min: s.pitchMinHz!, max: s.pitchMaxHz! } : null);
  };

  const now = on ? mic.now?.pitchHz ?? null : null;
  const position = now ? Math.min(100, Math.max(0, ((now - 60) / (400 - 60)) * 100)) : null;
  const targetPosition = target ? Math.min(100, Math.max(0, ((target - 60) / (400 - 60)) * 100)) : null;
  return (
    <div className="pitch">
      <div className="pitch-scale" aria-hidden="true">
        {targetPosition != null && <span className="pitch-target" style={{ left: `${targetPosition}%` }} />}
        {position != null && <span className="pitch-now" style={{ left: `${position}%` }} />}
      </div>
      <div className="pitch-ends muted small"><span>60 Hz</span><span aria-live="polite">{now ? `${Math.round(now)} Hz, ${noteName(now)}` : target ? `Aiming around ${target} Hz` : ""}</span><span>400 Hz</span></div>
      <div className="button-row">
        {!on ? <Button variant="tonal" busy={mic.status === "starting"} onClick={start}><Mic size={16} /> Measure my pitch</Button> : <Button variant="primary" onClick={finish}><MicOff size={16} /> Stop</Button>}
      </div>
      {mic.status === "problem" && mic.problem && <p className="notice">{MIC_PROBLEM_TEXT[mic.problem]}</p>}
      <p className="muted small">Your voice is analysed in this browser and never recorded or sent anywhere. Only the numbers are kept, if you save.</p>
    </div>
  );
}
