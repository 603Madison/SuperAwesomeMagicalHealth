/*
 * Sounds the trainer plays: notes to match (a sine with a few soft harmonics, so it's easy to hear
 * on small speakers), and recordings kept in this browser, played straight from their samples.
 */

let shared: AudioContext | null = null;

async function context(): Promise<AudioContext> {
  if (!shared || shared.state === "closed") shared = new AudioContext();
  if (shared.state === "suspended") await shared.resume().catch(() => undefined);
  return shared;
}

export interface Playing {
  /** Resolves when it has finished, or been stopped. */
  ended: Promise<void>;
  stop(): void;
}

/** A note at [hz] for [seconds], with a soft start and end. */
export async function playTone(hz: number, seconds = 1.2, volume = 0.22): Promise<Playing> {
  const ctx = await context();
  const real = new Float32Array([0, 1, 0.35, 0.15, 0.06]);
  const wave = ctx.createPeriodicWave(real, new Float32Array(real.length));
  const osc = ctx.createOscillator();
  osc.setPeriodicWave(wave);
  osc.frequency.value = hz;
  const gain = ctx.createGain();
  const t = ctx.currentTime;
  gain.gain.setValueAtTime(0, t);
  gain.gain.linearRampToValueAtTime(volume, t + 0.04);
  gain.gain.setValueAtTime(volume, t + Math.max(0.05, seconds - 0.15));
  gain.gain.linearRampToValueAtTime(0, t + seconds);
  osc.connect(gain).connect(ctx.destination);
  osc.start(t);
  osc.stop(t + seconds + 0.02);
  const ended = new Promise<void>((resolve) => { osc.onended = () => resolve(); });
  return { ended, stop: () => { try { osc.stop(); } catch { /* already stopped */ } } };
}

/** A recording's samples, played through the speakers. */
export async function playPcm(pcm: Int16Array, sampleRate: number): Promise<Playing> {
  const ctx = await context();
  const buffer = ctx.createBuffer(1, Math.max(1, pcm.length), sampleRate);
  const data = buffer.getChannelData(0);
  for (let i = 0; i < pcm.length; i++) data[i] = pcm[i] / 32768;
  const source = ctx.createBufferSource();
  source.buffer = buffer;
  source.connect(ctx.destination);
  source.start();
  const ended = new Promise<void>((resolve) => { source.onended = () => resolve(); });
  return { ended, stop: () => { try { source.stop(); } catch { /* already stopped */ } } };
}
