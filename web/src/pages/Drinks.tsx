import { useState } from "react";
import { Beer, Coffee, CupSoda, GlassWater, Leaf, Martini, Plus, Trash2, Wine, Zap } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { api } from "../lib/api";
import { clock, localInput, num, today } from "../lib/format";
import type { Drink, DrinkKind, DrinksDay } from "../lib/types";
import { Button, Card, Field, Modal, errorToast, toast } from "../components/ui";

/** Each kind of drink, as the server's DrinkKind has them: usual size, caffeine per 100 ml, alcohol %. */
export const DRINKS: Record<DrinkKind, { label: string; ml: number; caffeine: number; alcohol: number; icon: LucideIcon }> = {
  WATER: { label: "Water", ml: 250, caffeine: 0, alcohol: 0, icon: GlassWater },
  COFFEE: { label: "Coffee", ml: 240, caffeine: 40, alcohol: 0, icon: Coffee },
  ESPRESSO: { label: "Espresso", ml: 30, caffeine: 210, alcohol: 0, icon: Coffee },
  TEA: { label: "Tea", ml: 240, caffeine: 20, alcohol: 0, icon: Leaf },
  GREEN_TEA: { label: "Green tea", ml: 240, caffeine: 12, alcohol: 0, icon: Leaf },
  ENERGY_DRINK: { label: "Energy drink", ml: 250, caffeine: 32, alcohol: 0, icon: Zap },
  SODA: { label: "Cola", ml: 330, caffeine: 10, alcohol: 0, icon: CupSoda },
  DECAF: { label: "Decaf", ml: 240, caffeine: 1, alcohol: 0, icon: Coffee },
  BEER: { label: "Beer", ml: 355, caffeine: 0, alcohol: 5, icon: Beer },
  WINE: { label: "Wine", ml: 150, caffeine: 0, alcohol: 12, icon: Wine },
  SPIRITS: { label: "Spirits", ml: 45, caffeine: 0, alcohol: 40, icon: Martini },
  COCKTAIL: { label: "Cocktail", ml: 150, caffeine: 0, alcohol: 15, icon: Martini },
  OTHER: { label: "Other", ml: 250, caffeine: 0, alcohol: 0, icon: CupSoda },
};

/** Grams of ethanol in a drink: volume × strength × 0.789 g/ml. */
export function alcoholGrams(ml: number, percent: number): number {
  return Math.round(ml * (percent / 100) * 0.789 * 10) / 10;
}

export function drinkFor(kind: DrinkKind, ml?: number, at = Date.now()): Drink {
  const d = DRINKS[kind];
  const volume = ml ?? d.ml;
  return { at, kind, volumeMl: volume, caffeineMg: Math.round((volume * d.caffeine) / 100), alcoholG: alcoholGrams(volume, d.alcohol) };
}

/** A drink logged with one tap: now, or at noon on another day. */
export async function quickDrink(kind: DrinkKind, date: string): Promise<void> {
  const at = date === today() ? Date.now() : new Date(`${date}T12:00`).getTime();
  await api.saveDrink(drinkFor(kind, undefined, at));
  toast(`${DRINKS[kind].label} logged`);
}

const QUICK: DrinkKind[] = ["COFFEE", "TEA", "WATER", "BEER", "WINE"];

/** The day's drinks: caffeine, what's left of it at bedtime, alcohol, and one-tap buttons. */
export function DrinksCard({ day, date, onChanged, readOnly, className = "" }: { day?: DrinksDay; date: string; onChanged: () => void; readOnly?: boolean; className?: string }) {
  const [adding, setAdding] = useState(false);
  const drinks = day?.drinks ?? [];
  const add = async (kind: DrinkKind) => { try { await quickDrink(kind, date); onChanged(); } catch (e) { errorToast(e); } };
  return (
    <Card title="Drinks" icon={<Coffee size={18} />} className={className}>
      <div className="stats">
        <div className="stat"><div className="stat-label">Caffeine</div><div className="stat-value">{num(day?.caffeineMg ?? 0)}<span className="stat-unit">mg</span></div><div className="stat-sub">{day?.lastCaffeineAt ? `last at ${clock(day.lastCaffeineAt)}` : "none yet"}</div></div>
        {day?.caffeineAtBedtimeMg != null && day.caffeineMg > 0 && <div className="stat"><div className="stat-label">Still in you at bedtime</div><div className="stat-value">~{num(day.caffeineAtBedtimeMg)}<span className="stat-unit">mg</span></div><div className="stat-sub">{day.caffeineAtBedtimeMg >= 50 ? "may keep you up" : "about a quarter of a coffee or less"}</div></div>}
        <div className="stat"><div className="stat-label">Alcohol</div><div className="stat-value">{num(day?.standardDrinks ?? 0, 1)}<span className="stat-unit">{day?.standardDrinks === 1 ? "drink" : "drinks"}</span></div><div className="stat-sub">standard drinks (14 g)</div></div>
      </div>
      {!readOnly && (
        <div className="button-row">
          {QUICK.map((k) => { const D = DRINKS[k]; return <Button key={k} onClick={() => add(k)}><D.icon size={16} /> {D.label}</Button>; })}
          <Button variant="ghost" onClick={() => setAdding(true)}><Plus size={16} /> Other</Button>
        </div>
      )}
      {drinks.length > 0 && (
        <ul className="rows">
          {[...drinks].sort((a, b) => b.at - a.at).map((d) => {
            const D = DRINKS[d.kind];
            return (
              <li key={d.id ?? d.at} className="row">
                <div className="row-icon"><D.icon size={18} /></div>
                <div className="row-main">
                  <div className="row-title">{d.name ?? D.label} · {d.volumeMl} ml</div>
                  <div className="row-sub">{clock(d.at)}{d.caffeineMg ? ` · ${num(d.caffeineMg)} mg caffeine` : ""}{d.alcoholG ? ` · ${num(d.alcoholG / 14, 1)} standard drinks` : ""}</div>
                </div>
                {!readOnly && d.id && <div className="row-actions"><Button variant="ghost" aria-label="Delete this drink" onClick={async () => { try { await api.deleteDrink(d.id!); onChanged(); } catch (e) { errorToast(e); } }}><Trash2 size={16} /></Button></div>}
              </li>
            );
          })}
        </ul>
      )}
      <p className="muted small">Everything but alcohol counts towards your water.</p>
      {adding && <DrinkDialog date={date} onClose={() => setAdding(false)} onSaved={onChanged} />}
    </Card>
  );
}

export function DrinkDialog({ date, onClose, onSaved }: { date: string; onClose: () => void; onSaved: () => void }) {
  const [kind, setKind] = useState<DrinkKind>("COFFEE");
  const [ml, setMl] = useState(String(DRINKS.COFFEE.ml));
  const [caffeine, setCaffeine] = useState(String(drinkFor("COFFEE").caffeineMg));
  const [alcohol, setAlcohol] = useState("0");
  const [name, setName] = useState("");
  const [at, setAt] = useState(localInput(date === today() ? Date.now() : new Date(`${date}T12:00`).getTime()));
  const [busy, setBusy] = useState(false);
  const choose = (k: DrinkKind, volume = DRINKS[k].ml) => {
    const d = drinkFor(k, volume);
    setKind(k);
    setMl(String(volume));
    setCaffeine(String(d.caffeineMg));
    setAlcohol(String(d.alcoholG));
  };
  const save = async () => {
    const volume = Number(ml);
    if (!volume) return;
    setBusy(true);
    try {
      await api.saveDrink({ at: new Date(at).getTime(), kind, volumeMl: volume, caffeineMg: Number(caffeine.replace(",", ".")) || 0, alcoholG: Number(alcohol.replace(",", ".")) || 0, name: name.trim() || undefined });
      toast("Logged");
      onSaved();
      onClose();
    } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal title="A drink" onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} disabled={!Number(ml)} onClick={save}>Save</Button></>}>
      <Field label="What">
        <div className="tag-list" role="radiogroup" aria-label="Kind of drink">
          {(Object.keys(DRINKS) as DrinkKind[]).map((k) => <button key={k} type="button" role="radio" aria-checked={kind === k} className={`chip ${kind === k ? "is-on" : ""}`} onClick={() => choose(k)}>{DRINKS[k].label}</button>)}
        </div>
      </Field>
      <div className="two-col">
        <Field label="How much (ml)"><input inputMode="numeric" value={ml} onChange={(e) => choose(kind, Number(e.target.value) || 0)} /></Field>
        <Field label="Name" help="Optional, e.g. Flat white"><input value={name} onChange={(e) => setName(e.target.value)} /></Field>
      </div>
      <div className="two-col">
        <Field label="Caffeine (mg)" help="A typical amount; change it if you know better"><input inputMode="decimal" value={caffeine} onChange={(e) => setCaffeine(e.target.value)} /></Field>
        <Field label="Alcohol (g)" help={Number(alcohol) ? `${num(Number(alcohol) / 14, 1)} standard drinks` : "A standard drink is 14 g"}><input inputMode="decimal" value={alcohol} onChange={(e) => setAlcohol(e.target.value)} /></Field>
      </div>
      <Field label="When"><input type="datetime-local" value={at} onChange={(e) => setAt(e.target.value)} max={localInput(Date.now() + 3_600_000)} /></Field>
    </Modal>
  );
}
