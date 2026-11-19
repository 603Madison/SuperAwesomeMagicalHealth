import { useEffect, useRef, useState } from "react";
import { Camera, ImagePlus, Package, X } from "lucide-react";
import { api } from "../lib/api";
import { portionFood, portionLine, portionServings } from "../lib/portion";
import type { Food } from "../lib/types";
import { Button, Field, IconButton, Spinner, errorToast } from "../components/ui";
import { shrinkPhoto, usePhotoOffer } from "./FoodPhoto";

/*
 * "How much did I have?" in the add-food dialog: a photo of what's being eaten (and the package,
 * if she likes) goes to the assistant with the food's name and serving, and the answer prefills
 * the servings field. Nothing is logged until she adds it. Shown only when the assistant is on,
 * she said yes with Food among what it may see, and it isn't paused (usePhotoOffer); the dialog's
 * "Snap a photo" already says where to turn it on. The photos are shrunk and drawn again here
 * (no location or camera details) and the server never keeps them.
 */

type Shot = { blob: Blob; url: string };

export function PortionPhoto({ food, onServings }: { food: Food; onServings: (servings: number) => void }) {
  const offer = usePhotoOffer();
  const [open, setOpen] = useState(false);
  const [photo, setPhoto] = useState<Shot | null>(null);
  const [pack, setPack] = useState<Shot | null>(null);
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [preparing, setPreparing] = useState(false);
  const [line, setLine] = useState<string | null>(null);
  const photoInput = useRef<HTMLInputElement>(null);
  const packInput = useRef<HTMLInputElement>(null);
  useEffect(() => () => { if (photo) URL.revokeObjectURL(photo.url); }, [photo]);
  useEffect(() => () => { if (pack) URL.revokeObjectURL(pack.url); }, [pack]);
  if (offer !== "ready") return null;

  const chosen = async (file: File | undefined, set: (s: Shot) => void, input: HTMLInputElement | null) => {
    if (!file) return;
    setPreparing(true);
    try {
      const blob = await shrinkPhoto(file);
      set({ blob, url: URL.createObjectURL(blob) });
    } catch (e) { errorToast(e); }
    setPreparing(false);
    if (input) input.value = "";
  };
  const estimate = async () => {
    if (!photo) return;
    setBusy(true);
    try {
      const e = await api.aiPortion(photo.blob, pack?.blob ?? null, portionFood(food), note);
      const servings = portionServings(e);
      if (servings) { onServings(Number(servings)); setOpen(false); }
      setLine(portionLine(e, food.serving));
    } catch (e) { errorToast(e); }
    setBusy(false);
  };

  if (!open) {
    return (
      <div className="portion-photo">
        <Button onClick={() => { setOpen(true); setLine(null); }}><Camera size={18} /> How much did I have?</Button>
        {line && <p className="muted small" role="status">{line}</p>}
      </div>
    );
  }
  return (
    <div className="portion-photo form-stack" style={{ gap: 10 }}>
      <input ref={photoInput} type="file" accept="image/*" capture="environment" hidden onChange={(e) => void chosen(e.target.files?.[0], setPhoto, photoInput.current)} />
      <input ref={packInput} type="file" accept="image/*" capture="environment" hidden onChange={(e) => void chosen(e.target.files?.[0], setPack, packInput.current)} />
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between" }}>
        <strong>Estimate from a photo</strong>
        <IconButton label="Close" onClick={() => setOpen(false)}><X size={18} /></IconButton>
      </div>
      {line && <div className="notice notice-warn" role="status">{line}</div>}
      {busy ? (
        <div className="photo-busy" role="status"><Spinner size={24} /><p className="muted">Looking at your photo…</p></div>
      ) : (
        <>
          <div className="photo-previews">
            {photo && <img src={photo.url} alt="What you're eating" />}
            {pack && <img src={pack.url} alt="The package" />}
          </div>
          <div className="button-row">
            <Button onClick={() => photoInput.current?.click()} disabled={preparing}>
              {preparing ? <Spinner size={16} /> : <ImagePlus size={18} />} {photo ? "Another photo" : "Photo of what you're eating"}
            </Button>
            <Button variant="ghost" onClick={() => packInput.current?.click()} disabled={preparing}>
              <Package size={18} /> {pack ? "Another of the package" : "Add the package (optional)"}
            </Button>
          </div>
          <Field label="Anything to know?" help="Optional, like “half eaten” or “I had all of it”">
            <input value={note} maxLength={200} onChange={(e) => setNote(e.target.value)} />
          </Field>
          <p className="muted small">The photos go to Anthropic to estimate this, and aren't kept.</p>
          <Button variant="primary" disabled={!photo || preparing} onClick={estimate}>Estimate servings</Button>
        </>
      )}
    </div>
  );
}
