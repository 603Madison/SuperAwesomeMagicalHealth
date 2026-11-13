import { useEffect, useRef, useState } from "react";
import { HardDrive, Pause, Play, Trash2 } from "lucide-react";
import { useSession } from "../../lib/session";
import { when } from "../../lib/format";
import { noteName } from "../../lib/voice/dsp";
import { resonanceWord } from "../../lib/voice/program";
import { deleteAllRecordings, deleteRecording, listRecordings, loadSound, useKeepRecordings, useRecordings, type RecordingInfo } from "../../lib/voice/recordings";
import { playPcm, type Playing } from "../../lib/voice/sound";
import { Button, Card, IconButton, Modal, Toggle, errorToast, toast } from "../ui";

/*
 * Recordings, if the person keeps them: off by default, only in this browser (IndexedDB), never
 * uploaded. Each plays beside the baseline, so the change is easy to hear.
 */

const size = (bytes: number) => (bytes > 1_000_000 ? `${(bytes / 1_000_000).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1000))} KB`);

export function RecordingsCard() {
  const user = useSession((s) => s.user)!;
  const [keep, setKeep] = useKeepRecordings(user.id);
  const version = useRecordings((s) => s.version);
  const [list, setList] = useState<RecordingInfo[] | null>(null);
  const [playing, setPlaying] = useState<string | null>(null);
  const [clearing, setClearing] = useState(false);
  const current = useRef<Playing | null>(null);

  useEffect(() => {
    let live = true;
    listRecordings(user.id).then((l) => { if (live) setList(l); }).catch(() => { if (live) setList([]); });
    return () => { live = false; };
  }, [user.id, version]);
  useEffect(() => () => current.current?.stop(), []);

  const play = async (r: RecordingInfo) => {
    current.current?.stop();
    if (playing === r.id) { setPlaying(null); return; }
    try {
      const sound = await loadSound(user.id, r.id);
      if (!sound) return;
      setPlaying(r.id);
      const p = await playPcm(sound.pcm, sound.sampleRate);
      current.current = p;
      await p.ended;
      setPlaying((now) => (now === r.id ? null : now));
    } catch (e) {
      setPlaying(null);
      errorToast(e);
    }
  };

  const baseline = list?.find((r) => r.kind === "baseline");
  const practice = list?.filter((r) => r.kind !== "baseline") ?? [];
  const total = (list ?? []).reduce((t, r) => t + r.bytes, 0);
  const row = (r: RecordingInfo) => (
    <li key={r.id} className={`row recording ${r.kind === "baseline" ? "is-baseline" : ""}`}>
      <IconButton label={playing === r.id ? `Stop ${r.title}` : `Play ${r.title}`} className="card-primary" onClick={() => void play(r)}>
        {playing === r.id ? <Pause size={18} /> : <Play size={18} />}
      </IconButton>
      <div className="row-main">
        <div className="row-title">{r.title}</div>
        <div className="row-sub">{when(r.at)} · {r.seconds}s{r.pitchAvgHz ? ` · ${Math.round(r.pitchAvgHz)} Hz (${noteName(r.pitchAvgHz)})` : ""}{r.resonance != null ? ` · resonance ${Math.round(r.resonance)}, ${resonanceWord(r.resonance)}` : ""}</div>
      </div>
      <div className="row-actions">
        <IconButton label={`Delete ${r.title}`} onClick={async () => { try { await deleteRecording(user.id, r.id); } catch (e) { errorToast(e); } }}><Trash2 size={16} /></IconButton>
      </div>
    </li>
  );

  return (
    <Card title="Recordings" icon={<HardDrive size={18} />} className="span-2">
      <Toggle checked={keep} onChange={setKeep} label="Keep recordings of practice"
        help="Only in this browser, on this device: never uploaded, and not on your other devices. Clearing the browser's data removes them." />
      {list && list.length > 0 && (
        <>
          {baseline && (
            <div className="recording-pair">
              <span className="muted small">Your baseline, to listen to beside the others</span>
              <ul className="rows">{row(baseline)}</ul>
            </div>
          )}
          {practice.length > 0 && <ul className="rows">{practice.slice(0, 30).map(row)}</ul>}
          <div className="button-row recordings-foot">
            <span className="muted small">{list.length} {list.length === 1 ? "recording" : "recordings"}, {size(total)}, only in this browser</span>
            <Button variant="ghost" className="btn-small" onClick={() => setClearing(true)}><Trash2 size={14} /> Delete all</Button>
          </div>
        </>
      )}
      {list && list.length === 0 && <p className="muted small">{keep ? "Your practice will be kept here as you go." : "Hearing yourself over the weeks can be lovely. Turn this on if you'd like that."}</p>}
      {clearing && (
        <Modal title="Delete all recordings?" onClose={() => setClearing(false)} footer={<>
          <Button variant="ghost" onClick={() => setClearing(false)}>Keep them</Button>
          <Button variant="danger" onClick={async () => { try { await deleteAllRecordings(user.id); toast("Recordings deleted from this browser"); setClearing(false); } catch (e) { errorToast(e); } }}>Delete all</Button>
        </>}>
          <p>They're only in this browser, so once they're gone they can't come back. Your practice numbers stay.</p>
        </Modal>
      )}
    </Card>
  );
}
