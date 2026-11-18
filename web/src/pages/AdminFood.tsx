import { useEffect, useState } from "react";
import { Download, KeyRound, RefreshCw, Trash2 } from "lucide-react";
import { api } from "../lib/api";
import type { AdminSettings, ServerSettings, UsdaLocalSettings, UsdaLocalStatus } from "../lib/types";
import { Meter } from "../components/charts";
import { Button, errorToast, Field, Segmented, Spinner, toast, Toggle, useLoad } from "../components/ui";
import { choiceText, copyLine, datesLine, workingLine } from "../lib/usdaLocal";

const NO_LOCAL: UsdaLocalSettings = { use: false, packaged: false, autoUpdate: false };

/** "3 Nov 2026". */
const day = (ms: number) => new Date(ms).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });

/**
 * Admin › Food search: USDA FoodData Central, from its online service (with its free key, never
 * shown again) or kept on this server, and Open Food Facts as an opt-in fallback. Saved on its own;
 * the server form keeps whatever's saved here.
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
  const local = s.usdaLocal ?? NO_LOCAL;
  const setLocal = (change: Partial<UsdaLocalSettings>) => save({ usdaLocal: { ...local, ...change } });
  const usdaOn = s.usdaFoods !== false;
  return (
    <section className="panel" id="food-search">
      <h2>Food search</h2>
      <p className="muted">Foods come from USDA FoodData Central, the US government's food database: everyday foods, and US packaged foods with their barcodes.</p>
      <div className="form-stack">
        <Toggle label="Search USDA FoodData Central" help={locked("SAMH_USDA_FOODS") || undefined} checked={usdaOn} onChange={(usdaFoods) => void save({ usdaFoods })} />
        {usdaOn && (
          <Segmented label="Where USDA's foods come from" value={local.use ? "local" : "online"} onChange={(v) => void setLocal({ use: v === "local" })} options={[
            { value: "online", label: "Ask USDA's online service (needs a free key)" },
            { value: "local", label: "Keep USDA's food list on this server" },
          ]} />
        )}
        {usdaOn && !local.use && (
          <>
            <p className="muted">
              Searches and scanned barcodes are sent to api.nal.usda.gov with this key, nothing about who asked.{" "}
              <a href="https://api.data.gov/signup/" target="_blank" rel="noreferrer">Get a free key at api.data.gov/signup</a>: it takes a minute.
            </p>
            <Field label="USDA API key" help={(data.usdaApiKeySet ? "Saved. It's never shown again; paste a new one to replace it." : "Not set: without one, food search only has people's own foods (and Open Food Facts, if it's on below).") + locked("SAMH_USDA_API_KEY")}>
              <div className="inline-form">
                <input type="password" autoComplete="off" value={key} onChange={(e) => setKey(e.target.value)} placeholder={data.usdaApiKeySet ? "••••••••" : ""} aria-label="USDA API key" />
                <Button busy={busy} onClick={() => void save({}, key.trim(), "Key saved")} disabled={!key.trim()}><KeyRound size={16} /> Save key</Button>
                {data.usdaApiKeySet && <Button variant="ghost" busy={busy} onClick={() => void save({}, "", "Key removed")}><Trash2 size={16} /> Remove</Button>}
              </div>
            </Field>
          </>
        )}
        {usdaOn && local.use && <KeptHere settings={local} onChange={setLocal} keySet={!!data.usdaApiKeySet} />}
        <Toggle label="Also ask Open Food Facts when USDA doesn't know a food"
          help={`A non-profit food database, asked only when USDA has nothing (or too little) for a search or a barcode. Searches and barcodes go to openfoodfacts.org.${locked("SAMH_FOOD_DATABASE")}`}
          checked={s.foodDatabase} onChange={(foodDatabase) => void save({ foodDatabase })} />
      </div>
    </section>
  );
}

/** USDA's food list kept on this server: what to keep, where the copy stands, and the buttons for it. */
function KeptHere({ settings, onChange, keySet }: { settings: UsdaLocalSettings; onChange: (c: Partial<UsdaLocalSettings>) => Promise<void>; keySet: boolean }) {
  const [status, setStatus] = useState<UsdaLocalStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const working = !!status?.working;
  useEffect(() => {
    let stop = false;
    const load = () => api.usdaLocal().then((s) => { if (!stop) setStatus(s); }, (e) => { if (!stop) errorToast(e); });
    void load();
    // While a download is under way, its progress every two seconds.
    const timer = working ? setInterval(load, 2000) : undefined;
    return () => { stop = true; if (timer) clearInterval(timer); };
  }, [working]);
  if (!status) return <Spinner />;
  const act = async (run: () => Promise<UsdaLocalStatus>, done?: string) => {
    setBusy(true);
    try { setStatus(await run()); if (done) toast(done); } catch (e) { errorToast(e); }
    setBusy(false);
  };
  const progress = workingLine(status);
  const dates = datesLine(status, day);
  return (
    <div className="form-stack">
      <p className="muted">Searches and scans are answered here and never leave the server, and no key is needed. The server downloads USDA's public-domain food list itself, which carries nothing about anyone.</p>
      <Segmented label="What to keep" value={settings.packaged ? "all" : "everyday"} onChange={(v) => void onChange({ packaged: v === "all" })} options={[
        { value: "everyday", label: "Everyday foods only" },
        { value: "all", label: "Everyday and packaged foods" },
      ]} />
      <p className="muted small">{choiceText(status, settings.packaged)}{status.ready && status.packaged !== settings.packaged ? " Download again to change the copy kept here." : ""}</p>
      <div>
        <strong>{copyLine(status)}</strong>
        {dates && <p className="muted small">{dates}</p>}
      </div>
      {progress && (
        <div>
          <p className="muted small">{progress}{status.ready ? ". The copy in use keeps working meanwhile." : ""}</p>
          <Meter value={status.progress ?? 0} max={1} label="Download progress" />
        </div>
      )}
      {!status.ready && !working && <p className="muted small">{keySet ? "Until it's here, searches ask USDA's online service with your key." : "Until it's here, searches only have people's own foods (and Open Food Facts, if it's on)."}</p>}
      {status.newer && !working && <div className="notice">A newer release is available ({status.newer}).</div>}
      {status.error && !working && <div className="notice notice-error">{status.error}{status.errorAt ? ` (${day(status.errorAt)})` : ""}</div>}
      <div className="button-row">
        <Button variant="primary" busy={busy} disabled={working} onClick={() => void act(api.downloadUsdaLocal)}>
          <Download size={16} /> {status.ready ? "Update" : "Download now"}
        </Button>
        {status.ready && <Button variant="ghost" busy={busy} disabled={working} onClick={() => void act(api.checkUsdaLocal, "Checked")}><RefreshCw size={16} /> Check for a newer release</Button>}
        {(status.ready || working) && <Button variant="ghost" busy={busy} onClick={() => void act(api.removeUsdaLocal, "Removed")}><Trash2 size={16} /> Remove</Button>}
      </div>
      <Toggle label="Update automatically when USDA publishes a new release" help="Checked once a month. Off: you'll see here when there's a newer one."
        checked={settings.autoUpdate} onChange={(autoUpdate) => void onChange({ autoUpdate })} />
    </div>
  );
}
