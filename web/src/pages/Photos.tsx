import { type ImgHTMLAttributes, useEffect, useMemo, useRef, useState } from "react";
import { Camera, Columns2, EyeOff, Images, Lock, SplitSquareHorizontal, Trash2, Upload, X } from "lucide-react";
import { api, photoUrl, sharedPhotoBlobUrl } from "../lib/api";
import { localInput, shortDate, useUnits, weight } from "../lib/format";
import type { Photo, Pose } from "../lib/types";
import { POSES } from "../lib/types";
import { useReadOnly, useViewing } from "../lib/viewing";
import { Button, Card, DateTimeInput, Empty, ErrorNotice, Field, Modal, PageHeader, Segmented, Spinner, Toggle, errorToast, toast, useLoad } from "../components/ui";
import { useArrange, type Section } from "../components/Arrange";
import { layoutKey } from "../lib/types";
import { ChipPicker, OneOf } from "../components/FieldKit";

/*
 * Progress photos: private, encrypted on the server, re-encoded there to drop location and camera
 * details, never shown to AI, and seen by someone you share with only if you include photos.
 */

const DISCREET_KEY = "samh.photos.discreet";
const SERVER_TYPES = ["image/jpeg", "image/png", "image/webp"];

/** A photo's picture: its URL, or, while viewing someone's shared photos, a blob fetched with the view header. */
function PhotoImg({ id, thumb = false, ...rest }: { id: string; thumb?: boolean } & Omit<ImgHTMLAttributes<HTMLImageElement>, "src">) {
  const viewing = useViewing((s) => s.share);
  const [src, setSrc] = useState<string | undefined>(viewing ? undefined : photoUrl(id, thumb));
  useEffect(() => {
    if (!viewing) { setSrc(photoUrl(id, thumb)); return; }
    let url: string | undefined;
    let gone = false;
    sharedPhotoBlobUrl(id, thumb).then((u) => { url = u; if (!gone) setSrc(u); }).catch(() => undefined);
    return () => { gone = true; if (url) URL.revokeObjectURL(url); };
  }, [id, thumb, viewing]);
  return src ? <img src={src} {...rest} /> : <span className="photo-loading" aria-hidden="true" />;
}

function readDiscreet(): boolean {
  try { return localStorage.getItem(DISCREET_KEY) === "1"; } catch { return false; }
}

/** HEIC and other types the server doesn't take become a JPEG here first; nothing else changes. */
async function toUploadable(file: File): Promise<{ blob: Blob; name: string }> {
  if (SERVER_TYPES.includes(file.type)) return { blob: file, name: file.name };
  const url = URL.createObjectURL(file);
  try {
    const img = await new Promise<HTMLImageElement>((resolve, reject) => {
      const i = new Image();
      i.onload = () => resolve(i);
      i.onerror = () => reject(new Error("unreadable"));
      i.src = url;
    });
    const canvas = document.createElement("canvas");
    canvas.width = img.naturalWidth;
    canvas.height = img.naturalHeight;
    canvas.getContext("2d")!.drawImage(img, 0, 0);
    const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, "image/jpeg", 0.92));
    if (!blob) throw new Error("unreadable");
    return { blob, name: file.name.replace(/\.[^.]+$/, "") + ".jpg" };
  } catch {
    throw new Error("This browser can't read that picture. Try saving it as a JPEG first (on an iPhone: Settings › Camera › Formats › Most Compatible).");
  } finally {
    URL.revokeObjectURL(url);
  }
}

export function Photos() {
  const readOnly = useReadOnly();
  const units = useUnits();
  const albums = useLoad(() => api.photoAlbums(), []);
  const [album, setAlbum] = useState<string | null>(null);
  const photos = useLoad(() => api.photos(album ?? undefined), [album]);
  const [discreet, setDiscreet] = useState(readDiscreet);
  const [revealed, setRevealed] = useState<Set<string>>(new Set());
  const [uploading, setUploading] = useState(false);
  const [open, setOpen] = useState<Photo | null>(null);
  const [comparing, setComparing] = useState(false);
  const [picked, setPicked] = useState<string[]>([]);
  const [pose, setPose] = useState<Pose | "ALL">("ALL");
  useEffect(() => { try { localStorage.setItem(DISCREET_KEY, discreet ? "1" : "0"); } catch { /* per browser only */ } }, [discreet]);
  const list = useMemo(() => [...(photos.data ?? [])].filter((p) => pose === "ALL" || p.pose === pose).sort((a, b) => b.at - a.at), [photos.data, pose]);
  const byMonth = useMemo(() => {
    const groups = new Map<string, Photo[]>();
    list.forEach((p) => {
      const key = new Date(p.at).toLocaleDateString(undefined, { month: "long", year: "numeric" });
      groups.set(key, [...(groups.get(key) ?? []), p]);
    });
    return [...groups.entries()];
  }, [list]);
  const reload = () => { albums.reload(); photos.reload(); };
  const hidden = (p: Photo) => discreet && !revealed.has(p.id!);
  const pick = (p: Photo) => {
    if (!comparing) {
      if (hidden(p)) setRevealed(new Set([...revealed, p.id!]));
      else setOpen(p);
      return;
    }
    setPicked(picked.includes(p.id!) ? picked.filter((x) => x !== p.id) : [...picked.slice(-1), p.id!]);
  };
  const caption = (p: Photo) => [shortDate(p.date ?? new Date(p.at).toISOString().slice(0, 10)), p.weightKg != null ? weight(p.weightKg, units) : null, p.onHormones ? `${p.onHormones} on hormones` : null].filter(Boolean).join(" · ");
  const pair = picked.map((id) => list.find((p) => p.id === id)).filter(Boolean) as Photo[];
  const sections: Section[] = [
    { id: "filters", label: "Albums and filters", node: (
      <div className="photo-bar">
        <div className="tabs" role="tablist" aria-label="Albums">
          <button role="tab" aria-selected={album === null} className={album === null ? "is-active" : ""} onClick={() => setAlbum(null)}>All</button>
          {(albums.data ?? []).map((a) => <button key={a.name} role="tab" aria-selected={album === a.name} className={album === a.name ? "is-active" : ""} onClick={() => setAlbum(a.name)}>{a.name} <span className="muted">{a.count}</span></button>)}
        </div>
        <select value={pose} onChange={(e) => setPose(e.target.value as Pose | "ALL")} aria-label="Pose">
          <option value="ALL">Every pose</option>
          {POSES.map((p) => <option key={p.id} value={p.id}>{p.label}</option>)}
        </select>
        <Toggle label="Discreet" help="Blurred until tapped, in this browser" checked={discreet} onChange={(d) => { setDiscreet(d); setRevealed(new Set()); }} />
      </div>
    ) },
    { id: "privacy", label: "How they're kept", node: (
      <p className="privacy-line"><Lock size={14} /> Photos are re-encoded on the server to remove location and camera details, then kept encrypted. They're never shown to the assistant or AI connectors, and only someone you share with who you've given Photos can see them.</p>
    ) },
    { id: "photos", label: "Photos", node: photos.data && (list.length === 0 ? (
      <Card><Empty icon={<Camera size={28} />} title="No photos yet">Same spot, same light, same pose, every few weeks: the changes you can't see day to day show up side by side.</Empty></Card>
    ) : (
      <div>
        {byMonth.map(([month, items]) => (
          <section key={month}>
            <h3 className="section-title">{month}</h3>
            <div className="photo-grid">
              {items.map((p) => (
                <button key={p.id} className={`photo-tile ${picked.includes(p.id!) ? "is-picked" : ""}`} onClick={() => pick(p)}
                  aria-label={`${POSES.find((x) => x.id === p.pose)?.label} photo, ${caption(p)}${hidden(p) ? ", blurred: tap to show" : ""}`}>
                  <PhotoImg id={p.id!} thumb alt="" loading="lazy" className={hidden(p) ? "is-blurred" : ""} />
                  {hidden(p) && <span className="photo-veil"><EyeOff size={20} /> Tap to show</span>}
                  <span className="photo-caption">{POSES.find((x) => x.id === p.pose)?.label} · {caption(p)}</span>
                </button>
              ))}
            </div>
          </section>
        ))}
      </div>
    )) },
  ];
  const arranged = useArrange(layoutKey("photos"), "Progress photos", sections);
  return (
    <div className="page">
      <PageHeader title="Progress photos" subtitle="Yours alone: encrypted, never shown to AI, and shared only if you choose" actions={<>
        <Button variant={comparing ? "primary" : "tonal"} onClick={() => { setComparing(!comparing); setPicked([]); }}><Columns2 size={18} /> {comparing ? "Done comparing" : "Compare"}</Button>
        {!readOnly && <Button variant="primary" onClick={() => setUploading(true)}><Upload size={18} /> Add photos</Button>}
        {arranged.button}
      </>} />
      {comparing && <p className="notice notice-info">Pick two photos to compare. {picked.length === 1 ? "One more." : ""}</p>}
      {photos.error && <ErrorNotice message={photos.error} onRetry={photos.reload} />}
      {photos.loading && !photos.data && <Spinner />}
      {arranged.render("arranged-stack")}
      {uploading && <UploadDialog albums={(albums.data ?? []).map((a) => a.name)} defaultAlbum={album ?? undefined} onClose={() => setUploading(false)} onSaved={reload} />}
      {open && <PhotoDialog photo={open} caption={caption(open)} readOnly={readOnly} albums={(albums.data ?? []).map((a) => a.name)} onClose={() => setOpen(null)} onSaved={reload} />}
      {pair.length === 2 && <Compare a={pair[1].at < pair[0].at ? pair[1] : pair[0]} b={pair[1].at < pair[0].at ? pair[0] : pair[1]} caption={caption} onClose={() => setPicked([])} />}
    </div>
  );
}

function UploadDialog({ albums, defaultAlbum, onClose, onSaved }: { albums: string[]; defaultAlbum?: string; onClose: () => void; onSaved: () => void }) {
  const [files, setFiles] = useState<File[]>([]);
  const [previews, setPreviews] = useState<string[]>([]);
  const [album, setAlbum] = useState(defaultAlbum ?? albums[0] ?? "Progress");
  const [pose, setPose] = useState<Pose>("FRONT");
  const [at, setAt] = useState(localInput(Date.now()));
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  useEffect(() => {
    const urls = files.map((f) => URL.createObjectURL(f));
    setPreviews(urls);
    return () => urls.forEach((u) => URL.revokeObjectURL(u));
  }, [files]);
  const save = async () => {
    if (files.length === 0 || !album.trim()) return;
    setBusy(true);
    try {
      for (const f of files) {
        const { blob, name } = await toUploadable(f);
        const when = files.length === 1 || !f.lastModified ? new Date(at).getTime() : f.lastModified;
        await api.uploadPhoto(blob, { album: album.trim(), pose, at: when, note: note.trim() || undefined, tags: [] }, name);
      }
      toast(files.length === 1 ? "Photo saved" : `${files.length} photos saved`);
      onSaved();
      onClose();
    } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal wide title="Add photos" onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} disabled={files.length === 0} onClick={save}>Save</Button></>}>
      <button type="button" className="dropzone" onClick={() => input.current?.click()}
        onDragOver={(e) => e.preventDefault()} onDrop={(e) => { e.preventDefault(); setFiles([...e.dataTransfer.files].filter((f) => f.type.startsWith("image/") || /\.hei[cf]$/i.test(f.name))); }}>
        <Images size={28} />
        <span>{files.length ? `${files.length} chosen` : "Choose photos, or drop them here"}</span>
      </button>
      <input ref={input} type="file" accept="image/*,.heic,.heif" multiple hidden onChange={(e) => setFiles([...(e.target.files ?? [])])} />
      {previews.length > 0 && <div className="photo-previews">{previews.map((u, i) => <img key={u} src={u} alt={`Chosen photo ${i + 1}`} />)}</div>}
      <div className="two-col">
        <Field label="Album">
          <input list="photo-albums" value={album} onChange={(e) => setAlbum(e.target.value)} />
          <datalist id="photo-albums">{albums.map((a) => <option key={a} value={a} />)}</datalist>
        </Field>
        <Field label="When" help={files.length > 1 ? "Each photo keeps its own date where the file has one" : undefined}><input type="datetime-local" value={at} onChange={(e) => setAt(e.target.value)} /></Field>
      </div>
      <OneOf<Pose> label="Pose" options={POSES} value={pose} onChange={(p) => setPose(p ?? "FRONT")} />
      <Field label="Note"><input value={note} onChange={(e) => setNote(e.target.value)} /></Field>
      <p className="muted small"><Lock size={12} /> Location and camera details are removed on the server, and the photo is kept encrypted.</p>
    </Modal>
  );
}

function PhotoDialog({ photo, caption, readOnly, albums, onClose, onSaved }: { photo: Photo; caption: string; readOnly: boolean; albums: string[]; onClose: () => void; onSaved: () => void }) {
  const [p, setP] = useState(photo);
  const [confirm, setConfirm] = useState(false);
  const save = async () => {
    try { await api.savePhoto(p); toast("Saved"); onSaved(); onClose(); } catch (e) { errorToast(e); }
  };
  return (
    <Modal wide title={caption} onClose={onClose} footer={readOnly ? undefined : <>
      {confirm ? <Button variant="danger" onClick={async () => { try { await api.deletePhoto(photo.id!); toast("Photo deleted"); onSaved(); onClose(); } catch (e) { errorToast(e); } }}>Delete for good</Button>
        : <Button variant="ghost" onClick={() => setConfirm(true)}><Trash2 size={16} /> Delete</Button>}
      <Button variant="ghost" onClick={onClose}>Close</Button><Button variant="primary" onClick={save}>Save</Button>
    </>}>
      <PhotoImg className="photo-full" id={photo.id!} alt={`${POSES.find((x) => x.id === photo.pose)?.label} photo, ${caption}`} />
      {!readOnly && (
        <>
          <div className="two-col">
            <Field label="Album">
              <input list="photo-albums-edit" value={p.album} onChange={(e) => setP({ ...p, album: e.target.value })} />
              <datalist id="photo-albums-edit">{albums.map((a) => <option key={a} value={a} />)}</datalist>
            </Field>
            <Field label="When"><DateTimeInput value={p.at} onChange={(at) => setP({ ...p, at })} /></Field>
          </div>
          <OneOf<Pose> label="Pose" options={POSES} value={p.pose} onChange={(pose) => setP({ ...p, pose: pose ?? "FRONT" })} />
          <ChipPicker label="Tags" options={[]} value={p.tags} onChange={(tags) => setP({ ...p, tags })} />
          <Field label="Note"><input value={p.note ?? ""} onChange={(e) => setP({ ...p, note: e.target.value || undefined })} /></Field>
        </>
      )}
      {readOnly && p.note && <p>{p.note}</p>}
    </Modal>
  );
}

/** Two photos, earlier on the left: side by side, or one over the other with a slider. */
function Compare({ a, b, caption, onClose }: { a: Photo; b: Photo; caption: (p: Photo) => string; onClose: () => void }) {
  const [mode, setMode] = useState<"side" | "slider">("side");
  const [split, setSplit] = useState(50);
  const days = Math.round((b.at - a.at) / 86_400_000);
  return (
    <div className="fullscreen compare" role="dialog" aria-modal="true" aria-label="Compare photos">
      <div className="compare-head">
        <Segmented label="How to compare" value={mode} onChange={setMode} options={[{ value: "side", label: "Side by side" }, { value: "slider", label: "Slider" }]} />
        <span className="muted">{days === 0 ? "The same day" : `${days} days apart`}</span>
        <button className="icon-btn" aria-label="Close" onClick={onClose}><X size={22} /></button>
      </div>
      {mode === "side" ? (
        <div className="compare-side">
          {[a, b].map((p) => <figure key={p.id}><PhotoImg id={p.id!} alt={caption(p)} /><figcaption>{caption(p)}</figcaption></figure>)}
        </div>
      ) : (
        <div className="compare-slider">
          <div className="compare-stage">
            <PhotoImg id={b.id!} alt={caption(b)} />
            <PhotoImg id={a.id!} alt={caption(a)} className="compare-top" style={{ clipPath: `inset(0 ${100 - split}% 0 0)` }} />
            <span className="compare-line" style={{ left: `${split}%` }} aria-hidden="true" />
          </div>
          <input type="range" min={0} max={100} value={split} onChange={(e) => setSplit(Number(e.target.value))} aria-label="Slide between the two photos" style={{ ["--fill" as string]: `${split}%` }} />
          <div className="compare-captions"><span>{caption(a)}</span><SplitSquareHorizontal size={16} aria-hidden="true" /><span>{caption(b)}</span></div>
        </div>
      )}
    </div>
  );
}
