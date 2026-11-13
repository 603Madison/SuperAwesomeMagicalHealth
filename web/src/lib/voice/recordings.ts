import { create } from "zustand";

/*
 * Voice recordings, only in this browser (IndexedDB), only when the person turns them on (off by
 * default), and never uploaded. One database per account, so two people sharing a browser don't
 * hear each other's. The list (small) and the sound (large) are kept apart so listing is quick.
 */

export interface RecordingInfo {
  id: string;
  at: number;
  /** The first one, from setting up: the one to compare with. */
  kind: "baseline" | "practice";
  title: string;
  lessonId?: string;
  exerciseId?: string;
  seconds: number;
  bytes: number;
  pitchAvgHz?: number;
  resonance?: number;
}

export interface RecordingSound {
  id: string;
  pcm: Int16Array;
  sampleRate: number;
}

const INFO = "info";
const SOUND = "sound";

function openDb(user: string): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    if (typeof indexedDB === "undefined") { reject(new Error("This browser can't keep recordings.")); return; }
    const request = indexedDB.open(`samh-voice-${user}`, 1);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(INFO)) db.createObjectStore(INFO, { keyPath: "id" });
      if (!db.objectStoreNames.contains(SOUND)) db.createObjectStore(SOUND, { keyPath: "id" });
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error ?? new Error("This browser can't keep recordings."));
  });
}

async function run<T>(user: string, stores: string[], mode: IDBTransactionMode, work: (tx: IDBTransaction) => IDBRequest<T> | void): Promise<T | undefined> {
  const db = await openDb(user);
  try {
    return await new Promise<T | undefined>((resolve, reject) => {
      const tx = db.transaction(stores, mode);
      const request = work(tx);
      tx.oncomplete = () => resolve(request ? request.result : undefined);
      tx.onerror = () => reject(tx.error ?? new Error("Couldn't reach this browser's storage."));
      tx.onabort = () => reject(tx.error ?? new Error("Couldn't reach this browser's storage."));
    });
  } finally {
    db.close();
  }
}

export async function listRecordings(user: string): Promise<RecordingInfo[]> {
  const all = (await run<RecordingInfo[]>(user, [INFO], "readonly", (tx) => tx.objectStore(INFO).getAll())) ?? [];
  return all.sort((a, b) => b.at - a.at);
}

export async function loadSound(user: string, id: string): Promise<RecordingSound | null> {
  return (await run<RecordingSound>(user, [SOUND], "readonly", (tx) => tx.objectStore(SOUND).get(id))) ?? null;
}

export async function saveRecording(user: string, info: Omit<RecordingInfo, "id" | "bytes" | "seconds">, pcm: Int16Array, sampleRate: number): Promise<RecordingInfo> {
  const id = typeof crypto.randomUUID === "function" ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  const full: RecordingInfo = { ...info, id, seconds: Math.round(pcm.length / sampleRate), bytes: pcm.byteLength };
  await run(user, [INFO, SOUND], "readwrite", (tx) => {
    // A new baseline replaces the old one.
    if (info.kind === "baseline") {
      const index = tx.objectStore(INFO).getAll();
      index.onsuccess = () => {
        for (const old of index.result as RecordingInfo[]) {
          if (old.kind === "baseline") { tx.objectStore(INFO).delete(old.id); tx.objectStore(SOUND).delete(old.id); }
        }
        tx.objectStore(INFO).put(full);
        tx.objectStore(SOUND).put({ id, pcm, sampleRate });
      };
      return;
    }
    tx.objectStore(INFO).put(full);
    tx.objectStore(SOUND).put({ id, pcm, sampleRate });
  });
  useRecordings.setState((s) => ({ version: s.version + 1 }));
  return full;
}

export async function deleteRecording(user: string, id: string): Promise<void> {
  await run(user, [INFO, SOUND], "readwrite", (tx) => { tx.objectStore(INFO).delete(id); tx.objectStore(SOUND).delete(id); });
  useRecordings.setState((s) => ({ version: s.version + 1 }));
}

export async function deleteAllRecordings(user: string): Promise<void> {
  await run(user, [INFO, SOUND], "readwrite", (tx) => { tx.objectStore(INFO).clear(); tx.objectStore(SOUND).clear(); });
  useRecordings.setState((s) => ({ version: s.version + 1 }));
}

const KEEP = "samh.voice.keepRecordings";

function keepFor(user: string): boolean {
  try {
    return localStorage.getItem(`${KEEP}.${user}`) === "1";
  } catch {
    return false;
  }
}

/** Whether this browser keeps recordings (per account), and a counter that changes when they do. */
export const useRecordings = create<{ version: number; keep: Record<string, boolean> }>(() => ({ version: 0, keep: {} }));

export function useKeepRecordings(user: string): [boolean, (keep: boolean) => void] {
  const keep = useRecordings((s) => s.keep[user] ?? keepFor(user));
  const set = (value: boolean) => {
    try {
      if (value) localStorage.setItem(`${KEEP}.${user}`, "1");
      else localStorage.removeItem(`${KEEP}.${user}`);
    } catch {
      // Kept for this visit only.
    }
    useRecordings.setState((s) => ({ keep: { ...s.keep, [user]: value } }));
  };
  return [keep, set];
}
