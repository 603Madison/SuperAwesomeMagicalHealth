import { useState } from "react";
import { MapPin, X } from "lucide-react";
import { api } from "../lib/api";
import { session, useSession } from "../lib/session";
import type { Place } from "../lib/types";
import { Button, Field, errorToast, toast } from "./ui";

/**
 * Roughly where the person is, for the weather: a US ZIP code, looked up on the server (nothing is
 * sent anywhere), saved on the profile.
 */
export function PlacePicker() {
  const profile = useSession((s) => s.profile);
  const [zip, setZip] = useState("");
  const [busy, setBusy] = useState(false);
  if (!profile) return null;
  const save = async (place: Place | null) => {
    setBusy(true);
    try {
      session.setProfile(await api.saveProfile({ ...profile, place }));
      toast(place ? `Weather for ${place.name}` : "Place removed");
      setZip("");
    } catch (e) { errorToast(e); }
    setBusy(false);
  };
  const find = async () => {
    setBusy(true);
    let place: Place | null = null;
    try { place = await api.zipPlace(zip.trim()); } catch (e) { errorToast(e); }
    setBusy(false);
    if (place) await save(place);
  };
  if (profile.place) {
    return (
      <div className="place-chosen">
        <MapPin size={18} />
        <span><strong>{profile.place.name}</strong><span className="muted small"> · for the weather</span></span>
        <Button variant="ghost" busy={busy} onClick={() => save(null)} aria-label="Remove your place"><X size={16} /> Remove</Button>
      </div>
    );
  }
  const valid = /^\d{5}$/.test(zip.trim());
  return (
    <form className="place-picker" onSubmit={(e) => { e.preventDefault(); if (valid) void find(); }}>
      <Field label="Your ZIP code" help="US only. Looked up on your server; the weather service only gets a rough area.">
        <input value={zip} onChange={(e) => setZip(e.target.value.replace(/[^\d]/g, "").slice(0, 5))} placeholder="e.g. 03820" inputMode="numeric" autoComplete="postal-code" />
      </Field>
      <Button type="submit" busy={busy} disabled={!valid}><MapPin size={16} /> Use this ZIP code</Button>
    </form>
  );
}
