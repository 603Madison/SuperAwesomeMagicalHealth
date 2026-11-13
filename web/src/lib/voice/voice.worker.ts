/*
 * The voice trainer's listening, off the page's thread: sound comes in from the capture worklet
 * (or from the page, in browsers without worklets), and frames go back to the page. A recording,
 * when the person keeps one, is gathered here and handed back at 16 kHz. Nothing leaves the browser.
 */

import { RECORDING_RATE, Smoother, VoiceAnalyzer, resample, toInt16, type Smoothed, type VoiceFrame } from "./dsp";

export type ToWorker =
  | { type: "start"; sampleRate: number; port?: MessagePort }
  | { type: "audio"; samples: Float32Array }
  | { type: "record" }
  | { type: "stopRecording"; id: number };

export type FromWorker =
  | { type: "frames"; frames: VoiceFrame[]; smooth: Smoothed[] }
  | { type: "recording"; id: number; pcm: Int16Array | null; sampleRate: number };

const scope = self as unknown as {
  onmessage: ((e: MessageEvent<ToWorker>) => void) | null;
  postMessage: (message: FromWorker, transfer?: Transferable[]) => void;
};

let analyzer: VoiceAnalyzer | null = null;
let smoother = new Smoother();
let sampleRate = 48000;
let recording: Float32Array[] | null = null;
/** At most ten minutes of recording, so a forgotten one can't fill the memory. */
const MAX_RECORDING_SECONDS = 600;
let recorded = 0;

function feed(samples: Float32Array) {
  if (!analyzer) return;
  if (recording && recorded < MAX_RECORDING_SECONDS * sampleRate) {
    recording.push(samples);
    recorded += samples.length;
  }
  const frames = analyzer.push(samples);
  if (frames.length) scope.postMessage({ type: "frames", frames, smooth: frames.map((f) => smoother.push(f)) });
}

scope.onmessage = (e) => {
  const m = e.data;
  switch (m.type) {
    case "start":
      sampleRate = m.sampleRate;
      analyzer = new VoiceAnalyzer(sampleRate);
      smoother = new Smoother();
      if (m.port) m.port.onmessage = (ev: MessageEvent<Float32Array>) => feed(ev.data);
      break;
    case "audio":
      feed(m.samples);
      break;
    case "record":
      recording = [];
      recorded = 0;
      break;
    case "stopRecording": {
      const pieces = recording;
      recording = null;
      if (!pieces || recorded === 0) {
        scope.postMessage({ type: "recording", id: m.id, pcm: null, sampleRate: RECORDING_RATE });
        break;
      }
      const all = new Float32Array(recorded);
      let at = 0;
      for (const p of pieces) { all.set(p, at); at += p.length; }
      const pcm = toInt16(resample(all, sampleRate, RECORDING_RATE));
      scope.postMessage({ type: "recording", id: m.id, pcm, sampleRate: RECORDING_RATE }, [pcm.buffer]);
      break;
    }
  }
};
