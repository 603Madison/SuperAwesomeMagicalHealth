import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { Camera, ExternalLink, ImagePlus, Plus, Trash2 } from "lucide-react";
import { api } from "../lib/api";
import { useFoodNumbers } from "../lib/fields";
import { num } from "../lib/format";
import { useSession } from "../lib/session";
import { useReadOnly } from "../lib/viewing";
import type { EstimateConfidence, Food, FoodPhotoEstimate, FoodPhotoItem, Meal, Nutrients } from "../lib/types";
import { Button, Field, IconButton, Modal, Spinner, errorToast, toast } from "../components/ui";
import { useAiStatus } from "./Assistant";
import { scaled } from "../lib/micros";

/*
 * Food photo estimates: snap a photo of a meal and the assistant estimates what's in it, to check
 * and change before anything is logged. Offered only when the assistant is on, the person said
 * yes, and Food is among what they let it see; never while viewing someone's share. The photo is
 * shrunk and drawn again here (which drops its location and camera details) before it's sent.
 */

const SIDE = 1024;

/** Whether to offer a photo: "ready", "settings" (one line pointing there), or nothing. */
export function usePhotoOffer(): "ready" | "settings" | null {
  const status = useAiStatus();
  const readOnly = useReadOnly();
  if (readOnly || !status?.available) return null;
  if (!status.consent?.acknowledged || !status.consent.modules.includes("NUTRITION")) return "settings";
  return "ready";
}

/** The add-food dialog's "Snap a photo", or a line saying where to turn it on. */
export function SnapPhoto({ onSnap }: { onSnap: () => void }) {
  const offer = usePhotoOffer();
  if (offer === "ready") return <Button onClick={onSnap}><Camera size={18} /> Snap a photo</Button>;
  if (offer === "settings") {
    return <p className="muted small photo-links">To estimate a meal from a photo, let the assistant see Food in <Link to="/settings#assistant">Settings › AI assistant</Link>.</p>;
  }
  return null;
}

const CONFIDENCE: Record<EstimateConfidence, string> = { LOW: "A rough guess", MEDIUM: "A fair guess", HIGH: "A close guess" };

export function scaleNutrients(n: Nutrients, factor: number): Nutrients {
  const f = Number.isFinite(factor) && factor > 0 ? factor : 0;
  // Fibre, sugar, saturated fat and sodium from the estimate; micros from a food of their own.
  return scaled(n, f);
}

/** One row on the review: the estimate as it came, and what the person made of it. */
interface Row {
  key: number;
  item: FoodPhotoItem;
  name: string;
  amount: string;
  /** For a row they added: its calories. */
  calories: string;
  useMine: boolean;
  added: boolean;
}

const parse = (v: string) => Number(v.replace(",", ".")) || 0;

/** A row's own food, if they chose it: how many servings that is. */
function servingsOf(row: Row, mine: Food): number {
  const amount = parse(row.amount);
  return mine.servingGrams ? amount / mine.servingGrams : amount;
}

/** What a row holds now: the estimate scaled to its amount, or their own food's. */
export function rowNutrients(row: Row): Nutrients {
  const mine = row.useMine ? row.item.match : undefined;
  if (mine) return scaleNutrients(mine.nutrients, servingsOf(row, mine));
  if (row.added) return { calories: parse(row.calories), protein: 0, carbs: 0, fat: 0 };
  return scaleNutrients(row.item.nutrients, row.item.grams > 0 ? parse(row.amount) / row.item.grams : 0);
}

const sum = (list: Nutrients[]): Nutrients => list.reduce((a, b) => ({
  calories: a.calories + b.calories, protein: a.protein + b.protein, carbs: a.carbs + b.carbs, fat: a.fat + b.fat,
}), { calories: 0, protein: 0, carbs: 0, fat: 0 });

/** Shrinks a picture to [side] on its long side and writes it again as a JPEG: no location, no camera details. */
export async function shrinkPhoto(file: Blob, side = SIDE): Promise<Blob> {
  let source: ImageBitmap | HTMLImageElement;
  try {
    source = await createImageBitmap(file, { imageOrientation: "from-image" });
  } catch {
    source = await new Promise<HTMLImageElement>((resolve, reject) => {
      const url = URL.createObjectURL(file);
      const img = new Image();
      img.onload = () => { URL.revokeObjectURL(url); resolve(img); };
      img.onerror = () => { URL.revokeObjectURL(url); reject(new Error("That picture couldn't be read. Try a JPEG or PNG.")); };
      img.src = url;
    });
  }
  const w = source.width;
  const h = source.height;
  const scale = Math.min(1, side / Math.max(w, h));
  const canvas = document.createElement("canvas");
  canvas.width = Math.max(1, Math.round(w * scale));
  canvas.height = Math.max(1, Math.round(h * scale));
  const ctx = canvas.getContext("2d");
  if (!ctx) throw new Error("This browser can't prepare the photo");
  ctx.fillStyle = "#fff";
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.drawImage(source, 0, 0, canvas.width, canvas.height);
  if ("close" in source) source.close();
  return new Promise((resolve, reject) => canvas.toBlob((b) => (b ? resolve(b) : reject(new Error("The photo couldn't be prepared"))), "image/jpeg", 0.85));
}

const noticeKey = (userId: string) => `samh.foodPhotoNotice.${userId}`;

function noticeSeen(userId: string): boolean {
  try { return localStorage.getItem(noticeKey(userId)) === "1"; } catch { return false; }
}

/** From the photo to the review, inside the add-food dialog. */
export function FoodPhotoFlow({ date, meal, mealLabel, onBack, onClose, onAdded }: {
  date: string; meal: Meal; mealLabel: string; onBack: () => void; onClose: () => void; onAdded: () => void;
}) {
  const user = useSession((s) => s.user);
  const [noticeDone, setNoticeDone] = useState(() => !user || noticeSeen(user.id));
  const [estimate, setEstimate] = useState<FoodPhotoEstimate | null>(null);
  if (!noticeDone) {
    return <Notice onBack={onBack} onClose={onClose} onDone={() => {
      try { if (user) localStorage.setItem(noticeKey(user.id), "1"); } catch { /* remembered for this visit only */ }
      setNoticeDone(true);
    }} />;
  }
  if (estimate && estimate.items.length > 0 && !estimate.refused) {
    return <Review estimate={estimate} date={date} meal={meal} mealLabel={mealLabel} onRetake={() => setEstimate(null)} onClose={onClose} onAdded={onAdded} />;
  }
  return <Pick meal={meal} mealLabel={mealLabel} problem={estimate ? estimate.notes : null} onBack={onBack} onClose={onClose} onEstimate={setEstimate} />;
}

/** The first time only: where the photo goes. */
function Notice({ onBack, onClose, onDone }: { onBack: () => void; onClose: () => void; onDone: () => void }) {
  const status = useAiStatus();
  const [understood, setUnderstood] = useState(false);
  return (
    <Modal title="Snap a photo" onClose={onClose} footer={<>
      <Button variant="ghost" onClick={onBack}>Back</Button>
      <Button variant="primary" disabled={!understood} onClick={onDone}>Carry on</Button>
    </>}>
      <div className="form-stack" style={{ gap: 12 }}>
        <p>The photo is sent to {status?.provider ?? "Anthropic"} to estimate what's in it. It isn't kept on this server or on {status?.provider ?? "Anthropic"}'s side by this app, and you can check and change everything before it's logged.</p>
        {status && (
          <p className="muted small photo-links">
            {status.provider} handles it under its <a href={status.termsUrl} target="_blank" rel="noreferrer">commercial terms <ExternalLink size={12} /></a> and <a href={status.privacyUrl} target="_blank" rel="noreferrer">privacy policy <ExternalLink size={12} /></a>.
          </p>
        )}
        <label className="check-row">
          <input type="checkbox" checked={understood} onChange={(e) => setUnderstood(e.target.checked)} />
          I understand
        </label>
      </div>
    </Modal>
  );
}

/** Taking or choosing the photo, a note if it helps, and sending it. */
function Pick({ meal, mealLabel, problem, onBack, onClose, onEstimate }: {
  meal: Meal; mealLabel: string; problem: string | null; onBack: () => void; onClose: () => void; onEstimate: (e: FoodPhotoEstimate) => void;
}) {
  const input = useRef<HTMLInputElement>(null);
  const [photo, setPhoto] = useState<Blob | null>(null);
  const [preview, setPreview] = useState<string | null>(null);
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [preparing, setPreparing] = useState(false);
  useEffect(() => () => { if (preview) URL.revokeObjectURL(preview); }, [preview]);
  const chosen = async (file: File | undefined) => {
    if (!file) return;
    setPreparing(true);
    try {
      const small = await shrinkPhoto(file);
      setPhoto(small);
      setPreview(URL.createObjectURL(small));
    } catch (e) { errorToast(e); }
    setPreparing(false);
    if (input.current) input.current.value = "";
  };
  const send = async () => {
    if (!photo) return;
    setBusy(true);
    try {
      const estimate = await api.aiFoodPhoto(photo, meal, note);
      setBusy(false);
      // Couldn't make it out: the photo goes, and the problem shows here, for another try.
      if (estimate.refused || estimate.items.length === 0) { setPhoto(null); setPreview(null); }
      onEstimate(estimate);
    } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal title={`Snap a photo of your ${mealLabel.toLowerCase()}`} onClose={onClose} wide footer={<>
      <Button variant="ghost" onClick={onBack}>Back</Button>
      <Button variant="primary" disabled={!photo} busy={busy} onClick={send}>Estimate</Button>
    </>}>
      <input ref={input} type="file" accept="image/*" capture="environment" hidden onChange={(e) => void chosen(e.target.files?.[0])} />
      {problem && <div className="notice notice-warn" role="status">{problem}</div>}
      {busy ? (
        <div className="photo-busy" role="status"><Spinner size={28} /><p className="muted">Looking at your photo…</p></div>
      ) : (
        <div className="form-stack" style={{ gap: 14 }}>
          {preview ? (
            <button type="button" className="photo-preview" onClick={() => input.current?.click()} aria-label="Take or choose another photo">
              <img src={preview} alt="Your photo" />
            </button>
          ) : (
            <button type="button" className="photo-drop" onClick={() => input.current?.click()} disabled={preparing}>
              {preparing ? <Spinner /> : <ImagePlus size={28} />}
              <span>Take or choose a photo</span>
              <span className="muted small">The whole plate, from a little above, works best</span>
            </button>
          )}
          <Field label="Anything to know?" help="Optional, like “large plate” or “half eaten”">
            <input value={note} maxLength={200} onChange={(e) => setNote(e.target.value)} />
          </Field>
        </div>
      )}
    </Modal>
  );
}

/** Checking the estimate: names and amounts can change, rows can go or be added; then it's logged. */
function Review({ estimate, date, meal, mealLabel, onRetake, onClose, onAdded }: {
  estimate: FoodPhotoEstimate; date: string; meal: Meal; mealLabel: string; onRetake: () => void; onClose: () => void; onAdded: () => void;
}) {
  // In eating-disorder recovery, foods and amounts only: no calorie or macro numbers here (they're still logged).
  // The estimate says so, and so do the person's fields.
  const foodNumbers = useFoodNumbers();
  const hide = estimate.hideNumbers || !foodNumbers;
  const next = useRef(estimate.items.length);
  const [rows, setRows] = useState<Row[]>(() => estimate.items.map((item, key) => ({
    key, item, name: item.name, amount: String(Math.round(item.grams)), calories: "", useMine: false, added: false,
  })));
  const [busy, setBusy] = useState(false);
  const change = (key: number, patch: Partial<Row>) => setRows((all) => all.map((r) => (r.key === key ? { ...r, ...patch } : r)));
  const ready = rows.filter((r) => r.name.trim() && parse(r.amount) > 0);
  const total = sum(ready.map(rowNutrients));
  const add = () => setRows((all) => [...all, {
    key: next.current++, added: true, name: "", amount: "100", calories: "", useMine: false,
    item: { name: "", grams: 100, nutrients: { calories: 0, protein: 0, carbs: 0, fat: 0 }, confidence: "LOW", assumption: "", unit: "g" },
  }]);
  const log = async () => {
    setBusy(true);
    let done = 0;
    try {
      for (const r of ready) {
        const mine = r.useMine ? r.item.match : undefined;
        if (mine) {
          await api.logFood({ date, meal, servings: servingsOf(r, mine), food: mine });
        } else {
          const n = rowNutrients(r);
          await api.logFood({ date, meal, servings: 1, name: r.name.trim(), nutrients: n, serving: `about ${num(parse(r.amount))} ${r.item.unit}` });
        }
        done++;
      }
      toast(`Logged ${done} to ${mealLabel.toLowerCase()}`);
      onAdded();
      onClose();
    } catch (e) {
      errorToast(e);
      if (done > 0) { setRows((all) => all.filter((r) => !ready.slice(0, done).includes(r))); onAdded(); }
      setBusy(false);
    }
  };
  return (
    <Modal title="Check what's on the plate" onClose={onClose} wide footer={<>
      <Button variant="ghost" onClick={onRetake}>Another photo</Button>
      <Button variant="primary" busy={busy} disabled={ready.length === 0} onClick={log}>Log to {mealLabel.toLowerCase()}</Button>
    </>}>
      <p className="notice notice-info" role="note">These are estimates from a photo, so they can be off. Check the {hide ? "foods and amounts" : "amounts"} before you log them.</p>
      {estimate.notes && <p className="muted small">{estimate.notes}</p>}
      {!hide && <p className="muted small">Vitamins and minerals can't be judged from a photo.</p>}
      <ul className="photo-items">
        {rows.map((r) => {
          const mine = r.useMine ? r.item.match : undefined;
          const n = rowNutrients(r);
          const unit = mine ? (mine.servingGrams ? "g" : `× ${mine.serving}`) : r.item.unit;
          return (
            <li key={r.key} className="photo-item">
              <div className="photo-item-head">
                <input className="photo-name" value={r.name} placeholder="What was it?" aria-label="Name" onChange={(e) => change(r.key, { name: e.target.value })} />
                <label className="photo-amount">
                  <input inputMode="decimal" value={r.amount} aria-label={mine && !mine.servingGrams ? "Servings" : r.item.unit === "ml" ? "Millilitres" : "Grams"} onChange={(e) => change(r.key, { amount: e.target.value })} />
                  <span className="muted">{unit}</span>
                </label>
                <IconButton label={`Remove ${r.name || "this"}`} onClick={() => setRows((all) => all.filter((x) => x.key !== r.key))}><Trash2 size={18} /></IconButton>
              </div>
              {!r.added && (
                <p className="muted small">{CONFIDENCE[r.item.confidence]}{r.item.assumption ? `: ${r.item.assumption}` : ""}</p>
              )}
              {r.added && !hide && (
                <label className="photo-amount photo-kcal">
                  <input inputMode="decimal" value={r.calories} placeholder="0" aria-label="Calories" onChange={(e) => change(r.key, { calories: e.target.value })} />
                  <span className="muted">kcal</span>
                </label>
              )}
              {r.item.match && (
                <label className="check-row">
                  <input type="checkbox" checked={r.useMine} onChange={(e) => {
                    const m = r.item.match!;
                    const amount = e.target.checked && !m.servingGrams ? "1" : e.target.checked ? String(Math.round(parse(r.amount))) : String(Math.round(r.item.grams));
                    change(r.key, { useMine: e.target.checked, amount });
                  }} />
                  Use my {r.item.match.name}
                </label>
              )}
              {!hide && (
                <p className="small photo-numbers">{num(n.calories)} kcal · P {num(n.protein)} g · C {num(n.carbs)} g · F {num(n.fat)} g</p>
              )}
            </li>
          );
        })}
      </ul>
      <Button variant="ghost" onClick={add}><Plus size={16} /> Add something it missed</Button>
      {!hide && ready.length > 0 && (
        <div className="photo-total">
          <span>Total</span>
          <strong>{num(total.calories)} kcal</strong>
          <span className="muted small">P {num(total.protein)} g · C {num(total.carbs)} g · F {num(total.fat)} g</span>
        </div>
      )}
    </Modal>
  );
}
