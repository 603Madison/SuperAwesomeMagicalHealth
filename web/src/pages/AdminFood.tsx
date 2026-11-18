import { useState } from "react";
import { KeyRound, Trash2 } from "lucide-react";
import { api } from "../lib/api";
import type { AdminSettings, ServerSettings } from "../lib/types";
import { Button, errorToast, Field, Spinner, toast, Toggle, useLoad } from "../components/ui";

/**
 * Admin › Food search: USDA FoodData Central (with its free key, which is never shown again) and
 * Open Food Facts as an opt-in fallback. Saved on its own; the server form keeps whatever's saved here.
 */
export function FoodSearchPanel() {
  const { data, error, loading, setData } = useLoad(() => api.adminSettings(), []);
  const [key, setKey] = useState("");
  const [busy, setBusy] = useState(false);
  if (loading && !data) return <section className="panel" id="food-search"><h2>Food search</h2><Spinner /></section>;
  if (error || !data) return null;
  const env = new Set(data.fromEnvironment);
  const locked = (name: string) => (env.has(name) ? ` Set by ${name}, which wins at every start.` : "");
  const save = async (change: Partial<ServerSettings>, usdaApiKey?: string, done = "Saved") => {
    setBusy(true);
    try {
      // The rest of the server's settings as they are now, not as this page loaded them.
      const current: AdminSettings = await api.adminSettings();
      setData(await api.saveSettings({ ...current.settings, ...change }, usdaApiKey === undefined ? {} : { usdaApiKey }));
      if (usdaApiKey) setKey("");
      toast(done);
    } catch (e) { errorToast(e); }
    setBusy(false);
  };
  const s = data.settings;
  return (
    <section className="panel" id="food-search">
      <h2>Food search</h2>
      <p className="muted">
        Foods come from USDA FoodData Central, the US government's food database: everyday foods and US packaged foods with their barcodes.{" "}
        <a href="https://api.data.gov/signup/" target="_blank" rel="noreferrer">Get a free key at api.data.gov/signup</a>: it takes a minute.
      </p>
      <div className="form-stack">
        <Toggle label="Search USDA FoodData Central" help={`Searches and scanned barcodes are sent to api.nal.usda.gov with this key (nothing about who asked).${locked("SAMH_USDA_FOODS")}`}
          checked={s.usdaFoods !== false} onChange={(usdaFoods) => void save({ usdaFoods })} />
        <Field label="USDA API key" help={(data.usdaApiKeySet ? "Saved. It's never shown again; paste a new one to replace it." : "Not set: without one, food search only has people's own foods (and Open Food Facts, if it's on below).") + locked("SAMH_USDA_API_KEY")}>
          <div className="inline-form">
            <input type="password" autoComplete="off" value={key} onChange={(e) => setKey(e.target.value)} placeholder={data.usdaApiKeySet ? "••••••••" : ""} aria-label="USDA API key" />
            <Button busy={busy} onClick={() => void save({}, key.trim(), "Key saved")} disabled={!key.trim()}><KeyRound size={16} /> Save key</Button>
            {data.usdaApiKeySet && <Button variant="ghost" busy={busy} onClick={() => void save({}, "", "Key removed")}><Trash2 size={16} /> Remove</Button>}
          </div>
        </Field>
        <Toggle label="Also ask Open Food Facts when USDA doesn't know a food"
          help={`A non-profit food database, asked only when USDA has nothing (or too little) for a search or a barcode. Searches and barcodes go to openfoodfacts.org.${locked("SAMH_FOOD_DATABASE")}`}
          checked={s.foodDatabase} onChange={(foodDatabase) => void save({ foodDatabase })} />
      </div>
    </section>
  );
}
