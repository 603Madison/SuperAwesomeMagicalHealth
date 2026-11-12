import { useEffect, useState } from "react";
import { MapPin, X } from "lucide-react";
import { api } from "../lib/api";
import { session, useSession } from "../lib/session";
import type { Place } from "../lib/types";
import { Button, Field, errorToast, toast } from "./ui";

/**
 * Roughly where the person is, for the weather: searched through the server (the browser talks
 * only to this server), saved on the profile.
 */
export function PlacePicker() {
  const profile = useSession((s) => s.profile);
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<Place[] | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    const q = query.trim();
    if (q.length < 2) { setResults(null); return; }
    const timer = setTimeout(() => {
      api.places(q).then(setResults, () => setResults([]));
    }, 350);
    return () => clearTimeout(timer);
  }, [query]);
  if (!profile) return null;
  const save = async (place: Place | null) => {
    setBusy(true);
    try {
      session.setProfile(await api.saveProfile({ ...profile, place }));
      toast(place ? `Weather for ${place.name}` : "Place removed");
      setQuery("");
      setResults(null);
    } catch (e) { errorToast(e); }
    setBusy(false);
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
  return (
    <div className="place-picker">
      <Field label="Your town or city" help="Only to look up the weather (air pressure); the search goes through your server.">
        <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="e.g. Leeds" aria-autocomplete="list" />
      </Field>
      {results && (
        <ul className="place-results" role="listbox" aria-label="Places">
          {results.length === 0 && <li className="muted small">Nothing found</li>}
          {results.slice(0, 6).map((p) => (
            <li key={`${p.latitude},${p.longitude}`}>
              <button type="button" className="link-row" disabled={busy} onClick={() => save(p)}><MapPin size={16} /> {p.name}</button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
