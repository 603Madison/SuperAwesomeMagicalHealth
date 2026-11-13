/*
 * On the audio thread: hands the microphone's sound to the analysis worker in pieces of 2048
 * samples (about 45 ms), straight through a MessagePort, so the page's own work never holds it up.
 * Nothing is played or kept here.
 */

interface ProcessorShape {
  readonly port: MessagePort;
}
declare const AudioWorkletProcessor: { prototype: ProcessorShape; new (): ProcessorShape };
declare function registerProcessor(name: string, processor: new () => ProcessorShape): void;

const SIZE = 2048;

class Capture extends AudioWorkletProcessor {
  private out: MessagePort | null = null;
  private buffer = new Float32Array(SIZE);
  private filled = 0;

  constructor() {
    super();
    this.port.onmessage = (e: MessageEvent) => {
      if (e.data?.port) this.out = e.data.port as MessagePort;
    };
  }

  process(inputs: Float32Array[][]): boolean {
    const channel = inputs[0]?.[0];
    if (channel && this.out) {
      for (let i = 0; i < channel.length; i++) {
        this.buffer[this.filled++] = channel[i];
        if (this.filled === SIZE) {
          this.out.postMessage(this.buffer, [this.buffer.buffer]);
          this.buffer = new Float32Array(SIZE);
          this.filled = 0;
        }
      }
    }
    return true;
  }
}

registerProcessor("samh-voice-capture", Capture);
