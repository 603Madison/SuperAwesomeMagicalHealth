import { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { ArrowRightLeft, Barcode, Copy, GlassWater, MoreHorizontal, Pencil, Plus, Salad, Search, Trash2, Undo2, Zap } from "lucide-react";
import { api } from "../lib/api";
import { dayLabel, num, shiftDate, today } from "../lib/format";
import type { Food as FoodItem, FoodDay, FoodEntry, Meal, Nutrients } from "../lib/types";
import { Meter } from "../components/charts";
import { Button, Card, CardAction, DateNav, ErrorNotice, Field, Menu, Modal, Segmented, Spinner, errorToast, toast, useLoad } from "../components/ui";
import { useArrange, useHashScroll, type Section } from "../components/Arrange";
import { layoutKey } from "../lib/types";
import { useWater } from "../lib/fields";
import { useModules } from "../App";
import { Macros } from "./Today";
import { DrinksCard } from "./Drinks";
import { useReadOnly } from "../lib/viewing";

const MEALS: { id: Meal; label: string }[] = [
  { id: "BREAKFAST", label: "Breakfast" },
  { id: "LUNCH", label: "Lunch" },
  { id: "DINNER", label: "Dinner" },
  { id: "SNACK", label: "Snacks" },
];

export function Food() {
  const [params, setParams] = useSearchParams();
  const date = params.get("date") ?? today();
  const setDate = (d: string) => setParams(d === today() ? {} : { date: d }, { replace: true });
  const { data, error, loading, reload, setData } = useLoad(() => api.foodDay(date), [date]);
  const drinks = useLoad(() => api.drinksDay(date).catch(() => undefined), [date]);
  const readOnly = useReadOnly();
  const water = useWater(useModules());
  const [adding, setAdding] = useState<Meal | null>(null);
  const [editing, setEditing] = useState<FoodEntry | null>(null);
  useHashScroll(!!data);

  const sections: Section[] = [
    { id: "totals", label: "Totals", node: data && <Totals day={data} /> },
    { id: "water", label: "Water", when: water, node: data && <WaterCard day={data} readOnly={readOnly} onChanged={reload} /> },
    { id: "drinks", label: "Drinks", node: data && <DrinksCard day={drinks.data} date={date} readOnly={readOnly} onChanged={() => { drinks.reload(); reload(); }} /> },
    ...MEALS.map((m) => ({
      id: m.id.toLowerCase(), label: m.label, node: data && (
        <MealCard
          meal={m} day={data} readOnly={readOnly}
          onAdd={() => setAdding(m.id)} onEdit={setEditing} onChanged={reload}
          onRemoved={(id) => setData({ ...data, entries: data.entries.filter((e) => e.id !== id) })}
        />
      ),
    })),
  ];
  const arranged = useArrange(layoutKey("food"), "Food", sections);

  return (
    <div className="page">
      <header className="page-header">
        <div>
          <h1>Food</h1>
          <p className="page-subtitle">What you eat, against what you burn</p>
        </div>
        <div className="page-actions">
          <DateNav date={date} label={dayLabel(date)} onChange={setDate} max={shiftDate(today(), 1)} />
          {arranged.button}
        </div>
      </header>
      {error && <ErrorNotice message={error} onRetry={reload} />}
      {!data && loading && <div className="center-pad"><Spinner size={28} /></div>}
      <div style={{ opacity: loading ? 0.6 : 1 }}>{arranged.render()}</div>
      {adding && <AddFood date={date} meal={adding} onClose={() => setAdding(null)} onAdded={reload} />}
      {editing && <EditEntry entry={editing} onClose={() => setEditing(null)} onSaved={reload} />}
    </div>
  );
}

function Totals({ day }: { day: FoodDay }) {
  const t = day.target;
  const left = t.calories - day.totals.calories;
  return (
    <Card title="Totals" icon={<Salad size={18} />} className="span-2">
      <div className="grid grid-2">
        <div className="form-stack" style={{ gap: 12 }}>
          <div className="big-number">{num(day.totals.calories)}<small>of {num(t.calories)} kcal</small></div>
          <Meter value={day.totals.calories} max={t.calories} color="var(--c1)" label="Calories against your target" over />
          <p className="muted small">
            {left >= 0 ? `${num(left)} kcal left` : `${num(-left)} kcal over`}
            {day.burned != null ? ` · ${num(day.burned)} burned so far` : ""}
          </p>
          <p className="muted small">{t.basis}</p>
        </div>
        <Macros eaten={day.totals} target={t} />
      </div>
      {day.totals.fiber != null && (
        <p className="muted small">Fibre {num(day.totals.fiber)} g{day.totals.sugar != null ? ` · sugar ${num(day.totals.sugar)} g` : ""}{day.totals.sodiumMg != null ? ` · sodium ${num(day.totals.sodiumMg)} mg` : ""}</p>
      )}
    </Card>
  );
}

/** Water against its goal, a glass at a time. Only while water shows (Settings › Features › Food). */
function WaterCard({ day, readOnly, onChanged }: { day: FoodDay; readOnly: boolean; onChanged: () => void }) {
  const t = day.target;
  const glasses = Math.max(8, Math.ceil(t.waterMl / 250));
  const full = Math.floor(day.waterMl / 250);
  const water = async (ml: number) => {
    try { await api.addWater(day.date, ml); onChanged(); } catch (e) { errorToast(e); }
  };
  const undoWater = async () => {
    const last = day.water[day.water.length - 1];
    if (!last?.id) return;
    try { await api.deleteWater(last.id); onChanged(); } catch (e) { errorToast(e); }
  };
  return (
    <Card title="Water" icon={<GlassWater size={18} />} action={<span className="muted small"><strong>{num(day.waterMl)}</strong> / {num(t.waterMl)} ml</span>}>
      <div className="water-glasses" aria-hidden>
        {Array.from({ length: glasses }, (_, i) => <span key={i} className={`glass ${i < full ? "is-full" : ""}`} />)}
      </div>
      {!readOnly && (
        <div className="button-row">
          <Button onClick={() => water(250)}><GlassWater size={16} /> +250 ml</Button>
          <Button onClick={() => water(500)}>+500 ml</Button>
          {day.water.length > 0 && <Button variant="ghost" onClick={undoWater}><Undo2 size={16} /> Undo</Button>}
        </div>
      )}
    </Card>
  );
}

function MealCard({ meal, day, readOnly, onAdd, onEdit, onChanged, onRemoved }: {
  meal: { id: Meal; label: string }; day: FoodDay; readOnly: boolean; onAdd: () => void; onEdit: (e: FoodEntry) => void; onChanged: () => void; onRemoved: (id: string) => void;
}) {
  const entries = day.entries.filter((e) => e.meal === meal.id);
  const kcal = entries.reduce((s, e) => s + e.nutrients.calories, 0);
  const copyYesterday = async () => {
    try {
      const { copied } = await api.copyMeal(shiftDate(day.date, -1), day.date, meal.id);
      toast(copied ? `Copied ${copied} from yesterday's ${meal.label.toLowerCase()}` : `Nothing in yesterday's ${meal.label.toLowerCase()}`);
      onChanged();
    } catch (e) { errorToast(e); }
  };
  return (
    <Card
      title={<>{meal.label}<span className="muted" style={{ fontWeight: 400, marginLeft: 8 }}>{entries.length ? `${num(kcal)} kcal` : ""}</span></>}
      action={!readOnly && <>
        <Menu label={`More for ${meal.label}`} trigger={<MoreHorizontal size={20} />} items={[{ label: `Copy yesterday's ${meal.label.toLowerCase()}`, icon: <Copy size={18} />, onSelect: copyYesterday }]} />
        <CardAction label={`Add to ${meal.label.toLowerCase()}`} onClick={onAdd}><Plus size={18} /></CardAction>
      </>}
    >
      {entries.length > 0 && (
        <ul className="rows">
          {entries.map((e) => (
            <li key={e.id} className="row">
              <div className="row-main">
                <div className="row-title">{e.name}</div>
                <div className="row-sub">{e.brand ? `${e.brand} · ` : ""}{num(e.servings, e.servings % 1 ? 2 : 0)} × {e.serving} · P {num(e.nutrients.protein)} · C {num(e.nutrients.carbs)} · F {num(e.nutrients.fat)}</div>
              </div>
              <div className="row-end">{num(e.nutrients.calories)}<div className="muted">kcal</div></div>
              <div className="row-actions">
                <Menu label={`More for ${e.name}`} trigger={<MoreHorizontal size={18} />} items={[
                  { label: "Change amount", icon: <Pencil size={18} />, onSelect: () => onEdit(e) },
                  { label: "Move to", icon: <ArrowRightLeft size={18} />, items: MEALS.filter((m) => m.id !== e.meal).map((m) => ({
                    label: m.label, onSelect: async () => { try { await api.changeEntry(e.id!, { meal: m.id }); onChanged(); } catch (err) { errorToast(err); } },
                  })) },
                  { label: "Delete", icon: <Trash2 size={18} />, danger: true, onSelect: async () => {
                    try { await api.deleteEntry(e.id!); onRemoved(e.id!); onChanged(); } catch (err) { errorToast(err); }
                  } },
                ]} />
              </div>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

function scaled(n: Nutrients, factor: number): Nutrients {
  return { calories: n.calories * factor, protein: n.protein * factor, carbs: n.carbs * factor, fat: n.fat * factor };
}

/** Adding something eaten: search (your foods, then Open Food Facts), a barcode, recent foods, a quick add, or a new food. */
function AddFood({ date, meal, onClose, onAdded }: { date: string; meal: Meal; onClose: () => void; onAdded: () => void }) {
  const [tab, setTab] = useState<"search" | "recent" | "quick" | "new">("search");
  const [chosen, setChosen] = useState<FoodItem | null>(null);
  const label = MEALS.find((m) => m.id === meal)!.label;
  if (chosen) return <Servings food={chosen} date={date} meal={meal} onBack={() => setChosen(null)} onClose={onClose} onAdded={onAdded} />;
  return (
    <Modal title={`Add to ${label.toLowerCase()}`} onClose={onClose} wide>
      <Segmented label="How to add" value={tab} onChange={setTab} options={[
        { value: "search", label: "Search" }, { value: "recent", label: "Recent" }, { value: "quick", label: "Quick add" }, { value: "new", label: "New food" },
      ]} />
      {tab === "search" && <SearchFoods onChoose={setChosen} />}
      {tab === "recent" && <RecentFoods onChoose={setChosen} />}
      {tab === "quick" && <QuickAdd date={date} meal={meal} onDone={() => { onAdded(); onClose(); }} />}
      {tab === "new" && <NewFood onSaved={setChosen} />}
    </Modal>
  );
}

function SearchFoods({ onChoose }: { onChoose: (f: FoodItem) => void }) {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<{ mine: FoodItem[]; online: FoodItem[]; onlineError?: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const isBarcode = /^\d{8,14}$/.test(query.trim());
  useEffect(() => {
    const q = query.trim();
    if (q.length < 2 || isBarcode) { setResults(null); return; }
    let cancelled = false;
    setBusy(true);
    const timer = setTimeout(() => {
      api.searchFoods(q).then((r) => { if (!cancelled) setResults(r); }, (e) => { if (!cancelled) errorToast(e); }).finally(() => { if (!cancelled) setBusy(false); });
    }, 350);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [query, isBarcode]);
  const lookUp = async () => {
    setBusy(true);
    try { onChoose(await api.barcode(query.trim())); } catch (e) { errorToast(e); }
    setBusy(false);
  };
  return (
    <>
      <form className="search-box" onSubmit={(e) => { e.preventDefault(); if (isBarcode) void lookUp(); }}>
        {isBarcode ? <Barcode size={20} /> : <Search size={20} />}
        <input autoFocus value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search foods, or type a barcode" aria-label="Search foods" />
        {busy && <Spinner size={18} />}
      </form>
      {isBarcode && <Button variant="primary" onClick={lookUp} busy={busy}><Barcode size={18} /> Look up barcode {query.trim()}</Button>}
      {results && (
        <div className="search-results">
          {results.mine.length > 0 && <div className="result-group-title">Your foods</div>}
          {results.mine.map((f) => <FoodRow key={f.id} food={f} onChoose={onChoose} />)}
          {results.online.length > 0 && <div className="result-group-title">Open Food Facts</div>}
          {results.online.map((f, i) => <FoodRow key={`${f.barcode}-${i}`} food={f} onChoose={onChoose} />)}
          {results.onlineError && <p className="muted small">{results.onlineError}</p>}
          {results.mine.length === 0 && results.online.length === 0 && !results.onlineError && <p className="muted">Nothing found. Try other words, or add it as a new food.</p>}
        </div>
      )}
    </>
  );
}

function RecentFoods({ onChoose }: { onChoose: (f: FoodItem) => void }) {
  const { data, loading, error } = useLoad(() => api.recentFoods(), []);
  if (loading) return <Spinner />;
  if (error) return <div className="notice notice-error">{error}</div>;
  if (!data?.length) return <p className="muted">Foods you log show up here, most recent first.</p>;
  return <div className="search-results">{data.map((f) => <FoodRow key={f.id} food={f} onChoose={onChoose} />)}</div>;
}

function FoodRow({ food, onChoose }: { food: FoodItem; onChoose: (f: FoodItem) => void }) {
  return (
    <button className="row" onClick={() => onChoose(food)}>
      <div className="row-main">
        <div className="row-title">{food.name}</div>
        <div className="row-sub">{food.brand ? `${food.brand} · ` : ""}{food.serving} · P {num(food.nutrients.protein)} · C {num(food.nutrients.carbs)} · F {num(food.nutrients.fat)}</div>
      </div>
      <div className="row-end">{num(food.nutrients.calories)}<div className="muted">kcal</div></div>
    </button>
  );
}

function Servings({ food, date, meal, onBack, onClose, onAdded }: { food: FoodItem; date: string; meal: Meal; onBack: () => void; onClose: () => void; onAdded: () => void }) {
  const [servings, setServings] = useState("1");
  const [grams, setGrams] = useState(food.servingGrams ? String(Math.round(food.servingGrams)) : "");
  const [busy, setBusy] = useState(false);
  const count = Number(servings.replace(",", ".")) || 0;
  const n = useMemo(() => scaled(food.nutrients, count), [food, count]);
  const save = async () => {
    if (count <= 0) return;
    setBusy(true);
    try {
      await api.logFood({ date, meal, servings: count, food });
      toast(`Added ${food.name}`);
      onAdded();
      onClose();
    } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal title={food.name} onClose={onClose} footer={<><Button variant="ghost" onClick={onBack}>Back</Button><Button variant="primary" busy={busy} onClick={save}>Add</Button></>}>
      {food.brand && <p className="muted">{food.brand}{food.source === "OPEN_FOOD_FACTS" ? " · from Open Food Facts" : ""}</p>}
      <form className="two-col" onSubmit={(e) => { e.preventDefault(); void save(); }}>
        <Field label={`Servings of ${food.serving}`}>
          <input autoFocus inputMode="decimal" value={servings} onChange={(e) => {
            setServings(e.target.value);
            const c = Number(e.target.value.replace(",", "."));
            if (food.servingGrams && c) setGrams(String(Math.round(c * food.servingGrams)));
          }} />
        </Field>
        {food.servingGrams ? (
          <Field label="Or grams">
            <input inputMode="decimal" value={grams} onChange={(e) => {
              setGrams(e.target.value);
              const g = Number(e.target.value.replace(",", "."));
              if (g && food.servingGrams) setServings(String(Math.round((g / food.servingGrams) * 100) / 100));
            }} />
          </Field>
        ) : <div />}
        <button type="submit" hidden />
      </form>
      <div className="amounts">
        <div className="stat"><div className="stat-label">Calories</div><div className="stat-value">{num(n.calories)}</div></div>
        <div className="stat"><div className="stat-label">Protein</div><div className="stat-value">{num(n.protein, 1)}<span className="stat-unit">g</span></div></div>
        <div className="stat"><div className="stat-label">Carbs</div><div className="stat-value">{num(n.carbs, 1)}<span className="stat-unit">g</span></div></div>
        <div className="stat"><div className="stat-label">Fat</div><div className="stat-value">{num(n.fat, 1)}<span className="stat-unit">g</span></div></div>
      </div>
    </Modal>
  );
}

function QuickAdd({ date, meal, onDone }: { date: string; meal: Meal; onDone: () => void }) {
  const [name, setName] = useState("");
  const [values, setValues] = useState({ calories: "", protein: "", carbs: "", fat: "" });
  const [busy, setBusy] = useState(false);
  const set = (k: keyof typeof values) => (e: React.ChangeEvent<HTMLInputElement>) => setValues({ ...values, [k]: e.target.value });
  const save = async () => {
    const n = (v: string) => Number(v.replace(",", ".")) || 0;
    if (!n(values.calories)) return;
    setBusy(true);
    try {
      await api.logFood({ date, meal, servings: 1, name: name.trim() || "Quick add", nutrients: { calories: n(values.calories), protein: n(values.protein), carbs: n(values.carbs), fat: n(values.fat) } });
      toast("Added");
      onDone();
    } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <form className="form-stack" onSubmit={(e) => { e.preventDefault(); void save(); }}>
      <Field label="What it was" help="Optional"><input autoFocus value={name} onChange={(e) => setName(e.target.value)} placeholder="e.g. Lunch out" /></Field>
      <div className="amounts">
        <Field label="Calories"><input inputMode="decimal" value={values.calories} onChange={set("calories")} required /></Field>
        <Field label="Protein (g)"><input inputMode="decimal" value={values.protein} onChange={set("protein")} /></Field>
        <Field label="Carbs (g)"><input inputMode="decimal" value={values.carbs} onChange={set("carbs")} /></Field>
        <Field label="Fat (g)"><input inputMode="decimal" value={values.fat} onChange={set("fat")} /></Field>
      </div>
      <Button variant="primary" busy={busy} type="submit"><Zap size={16} /> Add</Button>
    </form>
  );
}

function NewFood({ onSaved }: { onSaved: (f: FoodItem) => void }) {
  const [food, setFood] = useState({ name: "", brand: "", serving: "1 serving", grams: "", calories: "", protein: "", carbs: "", fat: "", fiber: "" });
  const [busy, setBusy] = useState(false);
  const set = (k: keyof typeof food) => (e: React.ChangeEvent<HTMLInputElement>) => setFood({ ...food, [k]: e.target.value });
  const n = (v: string) => Number(v.replace(",", ".")) || 0;
  const save = async () => {
    setBusy(true);
    try {
      const saved = await api.saveFood({
        name: food.name.trim(), brand: food.brand.trim() || undefined, serving: food.serving.trim() || "1 serving",
        servingGrams: n(food.grams) || undefined, source: "CUSTOM", uses: 0,
        nutrients: { calories: n(food.calories), protein: n(food.protein), carbs: n(food.carbs), fat: n(food.fat), fiber: food.fiber ? n(food.fiber) : undefined },
      });
      toast("Saved to your foods");
      onSaved(saved);
    } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <form className="form-stack" onSubmit={(e) => { e.preventDefault(); void save(); }}>
      <div className="two-col">
        <Field label="Name"><input autoFocus value={food.name} onChange={set("name")} required /></Field>
        <Field label="Brand" help="Optional"><input value={food.brand} onChange={set("brand")} /></Field>
      </div>
      <div className="two-col">
        <Field label="Serving" help="e.g. 1 cup, 1 slice, 100 g"><input value={food.serving} onChange={set("serving")} /></Field>
        <Field label="Serving weight (g)" help="Optional: lets you log by grams"><input inputMode="decimal" value={food.grams} onChange={set("grams")} /></Field>
      </div>
      <p className="muted small">Per serving:</p>
      <div className="amounts">
        <Field label="Calories"><input inputMode="decimal" value={food.calories} onChange={set("calories")} required /></Field>
        <Field label="Protein (g)"><input inputMode="decimal" value={food.protein} onChange={set("protein")} /></Field>
        <Field label="Carbs (g)"><input inputMode="decimal" value={food.carbs} onChange={set("carbs")} /></Field>
        <Field label="Fat (g)"><input inputMode="decimal" value={food.fat} onChange={set("fat")} /></Field>
      </div>
      <Button variant="primary" busy={busy} type="submit">Save and choose amount</Button>
    </form>
  );
}

function EditEntry({ entry, onClose, onSaved }: { entry: FoodEntry; onClose: () => void; onSaved: () => void }) {
  const [servings, setServings] = useState(String(entry.servings));
  const [busy, setBusy] = useState(false);
  const save = async () => {
    const count = Number(servings.replace(",", "."));
    if (!count) return;
    setBusy(true);
    try { await api.changeEntry(entry.id!, { servings: count }); onSaved(); onClose(); } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal title={entry.name} onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} onClick={save}>Save</Button></>}>
      <form onSubmit={(e) => { e.preventDefault(); void save(); }}>
        <Field label={`Servings of ${entry.serving}`}><input autoFocus inputMode="decimal" value={servings} onChange={(e) => setServings(e.target.value)} /></Field>
      </form>
    </Modal>
  );
}
