import { useEffect, useRef, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import {
  Activity, ArrowDown, ArrowUp, ArrowUpDown, BellRing, Download, Droplet, Layers, Minus, Moon, Package, Plus, Printer, Settings as SettingsIcon, Sparkles,
  Timer, Trash2, TrendingDown, TrendingUp,
} from "lucide-react";
import { api } from "../lib/api";
import { clock, isoDate, num, shortDate, today } from "../lib/format";
import type {
  ContinenceCheckIn, ContinenceEntry, ContinenceKind, ContinenceProgress, ContinenceSettings, ContinenceWear, ContinenceWearing, LeakSize,
  ProgressMetric, Supply, Verdict,
} from "../lib/types";
import { AWARENESS, FULLNESS, LEAK_CONTENTS, REACHED_TOILET, VERDICT_LABEL, supplyKindLabel } from "../lib/types";
import { F, useFields } from "../lib/fields";
import { trainingMinutes } from "../lib/today";
import { useReadOnly } from "../lib/viewing";
import { BarChart, LineChart } from "../components/charts";
import { Button, Card, ErrorNotice, Field, Modal, PageHeader, Spinner, Stat, Toggle, errorToast, toast, useLoad } from "../components/ui";
import { useArrange, type Section } from "../components/Arrange";
import { layoutKey } from "../lib/types";
import { useWater } from "../lib/fields";
import { useModules } from "../App";
import { ChipPicker, CustomFieldInputs, CustomFieldsBuilder, FieldsEditor, FullnessPicker, OneOf, ScaleRow, WordList } from "../components/FieldKit";
import { INSIGHT_ICONS } from "../components/LogDialogs";
import {
  changeAfter, changeCount, changeDraft, changeDetail, changeTitle, diaryRows, inWhatsOn, isAccident, newEntry, putOnAlone, recentPutOns, wearLine,
} from "../lib/continence";
import { useScreenLayout } from "../lib/layout";
import { ChangeSheet, FullnessBadge, ProductsCard, WearingCard, putOn, tracksProducts } from "./Wearing";

export const KINDS: { id: ContinenceKind; label: string; short: string }[] = [
  { id: "URINATION", label: "Toilet trip", short: "Trip" },
  { id: "LEAK", label: "Leak", short: "Leak" },
  { id: "BOWEL", label: "Bowel movement", short: "Bowel" },
  { id: "PAD_CHANGE", label: "Change", short: "Change" },
  { id: "BOWEL_LEAK", label: "Bowel accident", short: "Bowel accident" },
  { id: "CATHETER", label: "Catheter", short: "Catheter" },
  { id: "BEDWETTING", label: "Wet night", short: "Wet night" },
];
export const kindLabel = (k: ContinenceKind) => KINDS.find((x) => x.id === k)?.label ?? "Pelvic floor exercise";

const TRIGGERS = ["cough", "sneeze", "laugh", "lifting", "exercise", "urge", "on the way", "running water", "key in the door", "asleep", "standing up", "cold"];
const ACTIVITIES = ["sitting", "standing up", "walking", "running", "bending", "lifting", "coughing", "laughing", "on the way to the toilet", "asleep", "sex"];
const PLACES = ["home", "work", "out", "in bed", "travelling", "at the gym"];
const UTI_SIGNS = ["burning", "cloudy", "strong smell", "going more often", "fever", "back or side pain"];
const BRISTOL = ["Separate hard lumps", "Lumpy and sausage-like", "A sausage with cracks", "Smooth and soft", "Soft blobs", "Mushy, ragged edges", "Liquid"];
const LEAKS: { id: LeakSize; label: string }[] = [
  { id: "DROPS", label: "A few drops" }, { id: "SMALL", label: "Small" }, { id: "MEDIUM", label: "Medium" }, { id: "LARGE", label: "Large" }, { id: "FULL", label: "All of it" },
];
const DEFAULT_SETTINGS: ContinenceSettings = {
  quickButtons: ["URINATION", "LEAK", "BOWEL", "PAD_CHANGE"], triggers: [], activities: [], locations: [], customFields: [],
  bladderTraining: { enabled: false, intervalMinutes: 60, from: "08:00", to: "21:00", stepMinutes: 15, stepEveryDays: 7, goalMinutes: 180 },
};


export function Continence() {
  const { has } = useFields();
  const readOnly = useReadOnly();
  const entries = useLoad(() => api.continence(3), []);
  const summary = useLoad(() => api.continenceSummary(14), []);
  const settings = useLoad(() => api.continenceSettings().catch(() => DEFAULT_SETTINGS), []);
  // Loaded whatever the fields say: the field layout may still be loading, and the list is small.
  const supplies = useLoad(() => api.supplies().catch(() => [] as Supply[]), []);
  const progress = useLoad(() => api.continenceProgress(12).catch(() => null), []);
  const wearing = useLoad(() => api.continenceWearing().catch(() => undefined), []);
  const products = useLoad(() => api.continenceProducts(90).catch(() => []), []);
  // Grouped by what was worn; an older server doesn't have it, and the day shows as a plain list.
  const diaryDay = useLoad(() => api.continenceDiary().catch(() => null), []);
  const [detail, setDetail] = useState<ContinenceEntry | null>(null);
  const [changing, setChanging] = useState<ContinenceEntry | null>(null);
  const [exercise, setExercise] = useState(false);
  const [diary, setDiary] = useState(false);
  const reload = () => { entries.reload(); summary.reload(); progress.reload(); supplies.reload(); wearing.reload(); products.reload(); diaryDay.reload(); };
  const s0 = settings.data ?? DEFAULT_SETTINGS;
  const recent = recentPutOns(entries.data ?? []);
  /** The Change sheet, starting from what's on now. */
  const change = () => setChanging(changeDraft(wearing.data, s0, supplies.data ?? [], recent));
  const quick = async (kind: ContinenceKind) => {
    // A change is one step with what came off and what went on: its sheet, quick to finish.
    if (kind === "PAD_CHANGE") { change(); return; }
    // An accident goes with what's on.
    try { await api.saveContinence(newEntry(kind, wearing.data, s0)); toast(`${kindLabel(kind)} logged`); reload(); } catch (e) { errorToast(e); }
  };
  const open = (e: ContinenceEntry) => (e.kind === "PAD_CHANGE" ? setChanging(e) : setDetail(e));
  const todays = (entries.data ?? []).filter((e) => e.date === today());
  const s = summary.data;
  // While Wearing now shows what's on (with its own Change), the quick row leaves Change out.
  const layout = useScreenLayout(layoutKey("bladder"));
  const wearingShown = tracksProducts(has, wearing.data) && !(layout?.hidden ?? []).includes("wearing");
  const somethingOn = wearingShown && !!wearing.data?.supplyId;
  const buttons = s0.quickButtons.filter((k) => (k !== "BOWEL_LEAK" || has(F.CONTINENCE_BOWEL_ACCIDENTS)) && (k !== "CATHETER" || has(F.CONTINENCE_CATHETER)) && !(k === "PAD_CHANGE" && somethingOn));
  const rows = diaryDay.data ? diaryRows(diaryDay.data, wearingShown) : null;
  const sup = supplies.data ?? [];
  const accidentRows = (list: ContinenceEntry[]) => <AccidentRows list={list} supplies={sup} readOnly={readOnly} onOpen={open} />;
  const lowSupplies = (supplies.data ?? []).filter((x) => x.active && !x.reusable && x.lowAt != null && x.onHand <= x.lowAt);
  const water = useWater(useModules());
  const hasDays = !!s && s.days.some((d) => d.voids + d.leaks > 0);
  const sections: Section[] = [
    { id: "wearing", label: "Wearing now", when: tracksProducts(has, wearing.data), node: wearing.data !== undefined || !wearing.loading ? (
      <WearingCard wearing={wearing.data} supplies={sup} settings={s0} recent={recent} readOnly={readOnly} onChange={change}
        onPutOn={async (id) => { try { await putOn(id, sup); reload(); } catch (e) { errorToast(e); } }}
        onAccident={() => setDetail(newEntry("LEAK", wearing.data, s0))}
        onEditOn={wearing.data?.wear?.on ? () => setChanging(wearing.data!.wear!.on!) : undefined}>
        {accidentRows(wearing.data?.wear?.accidents ?? [])}
      </WearingCard>
    ) : null },
    { id: "quick", label: "Quick buttons", when: !readOnly, node: (
      <div className="quick span-2" role="group" aria-label="Log with one tap">
        {buttons.map((k) => <button key={k} className="chip" onClick={() => quick(k)}><Plus size={18} /> {kindLabel(k)}</button>)}
        <button className="chip" onClick={() => setDetail(newEntry("URINATION", wearing.data, s0))}><Plus size={18} /> With details</button>
        {has(F.CONTINENCE_PELVIC_FLOOR) && <button className="chip" onClick={() => setExercise(true)}><Activity size={18} /> Pelvic floor exercise</button>}
      </div>
    ) },
    { id: "supplies", label: "Supplies running low", when: has(F.CONTINENCE_SUPPLIES), node: lowSupplies.length > 0 && (
      <div className="notice notice-warn notice-row span-2">
        <span><Package size={16} /> Running low: {lowSupplies.map((x) => `${x.name} (${x.onHand} left)`).join(", ")}</span>
        <Link className="btn btn-ghost" to="/supplies">Restock</Link>
      </div>
    ) },
    { id: "today", label: "Today", node: (
      <Card title="Today" icon={<Droplet size={18} />}>
        {entries.error && <ErrorNotice message={entries.error} onRetry={entries.reload} />}
        {todays.length === 0 && !(rows ?? []).length ? <p className="muted">Nothing logged today.</p> : rows ? (
          <>
            <TodayCounts entries={todays} />
            <ul className="rows diary-rows">
              {rows.map((i) => (i.wear
                ? <WearRow key={`w${i.wear.on?.id ?? i.wear.off?.id ?? i.at}`} w={i.wear} readOnly={readOnly} onOpen={open}>{accidentRows(i.wear.accidents ?? [])}</WearRow>
                : i.entry && <EntryRow key={i.entry.id} e={i.entry} supplies={sup} readOnly={readOnly} onOpen={open} onDelete={reload} />))}
            </ul>
          </>
        ) : (
          <ul className="rows">
            {todays.map((e) => <EntryRow key={e.id} e={e} supplies={sup} readOnly={readOnly} onOpen={open} onDelete={reload} />)}
          </ul>
        )}
      </Card>
    ) },
    { id: "weeks", label: "The last two weeks", node: (
      <Card title="The last two weeks">
        {!s && summary.loading && <Spinner />}
        {s && (
          <>
            <div className="stats">
              <Stat label="Trips a day" value={s.averageVoids != null ? num(s.averageVoids, 1) : "–"} />
              <Stat label="At night" value={s.averageNightVoids != null ? num(s.averageNightVoids, 1) : "–"} />
              <Stat label="Leaks a day" value={s.averageLeaks != null ? num(s.averageLeaks, 1) : "–"} />
              <Stat label="Longest gap" value={s.averageInterval != null ? `${Math.floor(s.averageInterval / 60)}h ${s.averageInterval % 60}m` : "–"} sub="while awake, on average" />
            </div>
            {s.triggers.length > 0 && <div className="tag-list">{s.triggers.map((t) => <span key={t.name} className="chip is-static">{t.name} <span className="muted">{t.count}</span></span>)}</div>}
          </>
        )}
      </Card>
    ) },
    { id: "progress", label: "How it's going", node: progress.data && <ProgressCard progress={progress.data} /> },
    { id: "products", label: "How your products go", when: tracksProducts(has, wearing.data), node: <ProductsCard stats={products.data ?? []} className="span-2" /> },
    { id: "bother", label: "How much it bothers you", when: has(F.CONTINENCE_BOTHER), node: <CheckInsCard readOnly={readOnly} onSaved={progress.reload} /> },
    { id: "training", label: "Bladder training", when: has(F.CONTINENCE_BLADDER_TRAINING) && s0.bladderTraining.enabled, node: <TrainingCard settings={s0} /> },
    { id: "trips", label: "Toilet trips", node: s && hasDays && (
      <Card title="Toilet trips">
        <BarChart label="Toilet trips each day" color="var(--c1)" labelEvery={3} formatY={(v) => `${v} trips`}
          bars={s.days.map((d) => ({ key: d.date, label: shortDate(d.date), value: d.voids || null, detail: `${shortDate(d.date)} · ${d.nightVoids} at night${water ? ` · ${num(d.fluidMl)} ml water` : ""}` }))} />
      </Card>
    ) },
    { id: "leaks", label: "Leaks", node: s && hasDays && (
      <Card title="Leaks">
        <BarChart label="Leaks each day" color="var(--c3)" labelEvery={3} formatY={(v) => `${v} leaks`}
          bars={s.days.map((d) => ({ key: d.date, label: shortDate(d.date), value: d.leaks || null, detail: shortDate(d.date) }))} />
      </Card>
    ) },
    { id: "patterns", label: "Worth knowing", node: s && s.patterns.length > 0 && (
      <Card title="Worth knowing" icon={<Sparkles size={18} />} className="span-2">
        <div className="insights" style={{ margin: 0 }}>
          {s.patterns.map((p, i) => {
            const Icon = INSIGHT_ICONS[p.icon] ?? Sparkles;
            return <article key={i} className={`insight tone-${p.tone}`}><div className="insight-icon"><Icon size={20} /></div><div><h4>{p.title}</h4><p>{p.body}</p></div></article>;
          })}
        </div>
        <p className="muted small">This diary doesn't diagnose anything, but it's exactly what a doctor, continence nurse or pelvic health physiotherapist will want to see.</p>
      </Card>
    ) },
  ];
  const arranged = useArrange(layoutKey("bladder"), "Bladder & bowel", sections);
  return (
    <div className="page">
      <PageHeader title="Bladder & bowel" subtitle="A private diary: what happens, when, and what helps" actions={<>
        {has(F.CONTINENCE_SUPPLIES) && <Link className="btn btn-tonal" to="/supplies"><Package size={18} /> Supplies</Link>}
        <Button variant="tonal" onClick={() => setDiary(true)}><Printer size={18} /> 3-day diary</Button>
        {!readOnly && <Link className="btn btn-ghost" to="/bladder/settings" aria-label="Bladder and bowel settings" title="Bladder and bowel settings"><SettingsIcon size={18} /></Link>}
        {arranged.button}
      </>} />
      {arranged.render()}
      {s && !s.days.some((d) => d.voids + d.leaks + d.bowel > 0) && (
        <p className="muted small section-gap">Log every toilet trip and any leaks for three days or more: it's the first thing any specialist asks for, and it shows patterns quickly.</p>
      )}
      {detail && <EntryDialog entry={detail} settings={s0} supplies={sup} wearing={wearing.data} onClose={() => setDetail(null)} onSaved={reload}
        onPadChange={() => { setDetail(null); change(); }}
        onSavedThenChange={(accident) => { setDetail(null); setChanging(changeAfter(accident, wearing.data, s0, sup, recent)); }} />}
      {changing && <ChangeSheet initial={changing} supplies={sup} settings={s0} recent={recent} onClose={() => setChanging(null)} onSaved={reload}
        accidentsWhile={wearing.data?.wear?.accidents?.length ?? 0} />}
      {exercise && <PelvicFloor onClose={() => setExercise(false)} onLogged={reload} />}
      {diary && <Diary onClose={() => setDiary(false)} />}
    </div>
  );
}

/** After a change's time: " · Full · worn 7 h · changed bedding · about 40 g". */
function describeChange(e: ContinenceEntry): string {
  const parts = [
    ...changeDetail(e),
    e.lossG != null ? `about ${num(e.lossG)} g` : "",
    e.changedClothes ? "changed clothes" : "", e.changedBedding ? "changed bedding" : "",
    e.skin ? ["", "skin a little sore", "skin sore", "skin very sore"][e.skin] : "",
    e.count && e.count > 1 ? `${e.count} at once` : "", e.note ?? "",
  ].filter(Boolean);
  return parts.length ? ` · ${parts.join(" · ")}` : "";
}

function describe(e: ContinenceEntry, supplies: Supply[]): string {
  const supply = supplies.find((x) => x.id === e.supplyId);
  const parts = [
    e.night ? "at night" : "",
    e.volumeMl ? `${e.volumeMl} ml` : "",
    e.urgency != null ? ["no urgency", "mild urgency", "strong urgency", "couldn't wait"][e.urgency] : "",
    e.contents && e.contents !== "URINE" ? LEAK_CONTENTS.find((c) => c.id === e.contents)?.label.toLowerCase() : "",
    e.leak ? `leak: ${LEAKS.find((l) => l.id === e.leak)?.label.toLowerCase()}` : "",
    e.fullness ? `${supply?.name ?? "product"} ${FULLNESS.find((f) => f.id === e.fullness)?.label.toLowerCase()}` : "",
    e.lossG != null ? `about ${num(e.lossG)} g` : "",
    e.count && e.count > 1 ? `${e.count} changed` : "",
    [...e.triggers, ...(e.activity ?? [])].join(", "),
    e.reachedToilet ? REACHED_TOILET.find((r) => r.id === e.reachedToilet)?.label.toLowerCase() : "",
    e.location ?? "",
    e.bristol ? `type ${e.bristol}` : "",
    e.reps ? `${e.reps} × ${e.holdSeconds ?? 5}s` : "",
    e.changedClothes ? "changed clothes" : "", e.changedBedding ? "changed bedding" : "", e.leakedThrough ? "leaked through" : "",
    e.pain ? "pain" : "", e.blood ? "blood" : "", (e.utiSigns ?? []).join(", "), e.note ?? "",
  ].filter(Boolean);
  return parts.length ? ` · ${parts.join(" · ")}` : "";
}

/** Today's counts: trips, leaks and bowel movements, then the rest that happened. */
function TodayCounts({ entries }: { entries: ContinenceEntry[] }) {
  const n = (k: ContinenceKind) => entries.filter((e) => e.kind === k).length;
  const leaks = n("LEAK") + n("BEDWETTING");
  const say = (c: number, one: string, many: string) => `${c} ${c === 1 ? one : many}`;
  const more = [
    n("BOWEL_LEAK") > 0 && say(n("BOWEL_LEAK"), "bowel accident", "bowel accidents"),
    changeCount(entries) > 0 && say(changeCount(entries), "change", "changes"),
    n("CATHETER") > 0 && say(n("CATHETER"), "catheter", "catheters"),
  ].filter(Boolean);
  return (
    <div className="diary-counts">
      <Stat label={n("URINATION") === 1 ? "Toilet trip" : "Toilet trips"} value={String(n("URINATION"))} />
      <Stat label={leaks === 1 ? "Leak" : "Leaks"} value={String(leaks)} />
      <Stat label={n("BOWEL") === 1 ? "Bowel movement" : "Bowel movements"} value={String(n("BOWEL"))} />
      {more.length > 0 && <p className="muted small diary-more">{more.join(" · ")}</p>}
    </div>
  );
}

/** "7:48 pm", or "Yesterday 11:30 pm" / "Tue 11:30 pm" for an accident on another day. */
function when(ms: number): string {
  const d = new Date(ms);
  if (isoDate(d) === today()) return clock(ms);
  const y = new Date(); y.setDate(y.getDate() - 1);
  return `${isoDate(d) === isoDate(y) ? "Yesterday" : d.toLocaleDateString(undefined, { weekday: "short" })} ${clock(ms)}`;
}

/** An entry on its own in the day's list. */
function EntryRow({ e, supplies, readOnly, onOpen, onDelete }: { e: ContinenceEntry; supplies: Supply[]; readOnly: boolean; onOpen: (e: ContinenceEntry) => void; onDelete: () => void }) {
  return (
    <li className="row">
      <div className="row-icon">{e.night ? <Moon size={18} /> : e.kind === "PELVIC_FLOOR" ? <Activity size={18} /> : e.kind === "PAD_CHANGE" ? <Layers size={18} /> : <Droplet size={18} />}</div>
      <button className="row-main link-row" onClick={() => !readOnly && onOpen(e)} disabled={readOnly}>
        <div className="row-title">{e.kind === "PAD_CHANGE" ? changeTitle(e, supplies) : kindLabel(e.kind)}</div>
        <div className="row-sub">{clock(e.at)}{e.kind === "PAD_CHANGE" ? describeChange(e) : describe(e, supplies)}</div>
      </button>
      {!readOnly && (
        <div className="row-actions">
          <Button variant="ghost" aria-label="Delete" onClick={async () => { try { await api.deleteContinence(e.id!); onDelete(); } catch (err) { errorToast(err); } }}><Trash2 size={16} /></Button>
        </div>
      )}
    </li>
  );
}

/** The accidents while something was on, small, under it: each opens to edit. */
function AccidentRows({ list, supplies, readOnly, onOpen }: { list: ContinenceEntry[]; supplies: Supply[]; readOnly: boolean; onOpen: (e: ContinenceEntry) => void }) {
  if (list.length === 0) return null;
  return (
    <ul className="wear-accidents">
      {list.map((a) => (
        <li key={a.id ?? a.at}>
          <button type="button" className="link-row wear-accident" onClick={() => !readOnly && onOpen(a)} disabled={readOnly}>
            <span className="wear-accident-icon" aria-hidden="true">{a.kind === "BEDWETTING" ? <Moon size={14} /> : <Droplet size={14} />}</span>
            <span className="wear-accident-text">
              <span className="wear-accident-title">{kindLabel(a.kind)}</span>
              <span className="wear-accident-sub">{when(a.at)}{describe({ ...a, supplyId: undefined, fullness: undefined }, supplies)}</span>
            </span>
            {a.fullness && <span className="wear-accident-full">{FULLNESS.find((f) => f.id === a.fullness)?.label}</span>}
          </button>
        </li>
      ))}
    </ul>
  );
}

/** A product worn, as one row: how it was when it came off, then the accidents while it was on. */
function WearRow({ w, readOnly, onOpen, children }: { w: ContinenceWear; readOnly: boolean; onOpen: (e: ContinenceEntry) => void; children: ReactNode }) {
  const head = w.off ?? w.on;
  const alone = putOnAlone(w);
  const badge = !!w.off || !!w.current;
  // The pad beside it says how full it was; the line says the rest.
  const shown: ContinenceWear = { ...w, off: w.current ? undefined : w.off, fullness: badge ? undefined : w.fullness, leakedThrough: !!w.leakedThrough && w.fullness !== "LEAKED" };
  return (
    <li className="row wear-row">
      <div className="row-icon"><Layers size={18} /></div>
      <div className="row-main">
        <button type="button" className="link-row wear-head" onClick={() => !readOnly && head && onOpen(head)} disabled={readOnly || !head}>
          <span className="wear-head-text">
            <span className="row-title">{w.name ?? "A product"}{w.current && <span className="wearing-kind">On now</span>}</span>
            <span className="row-sub wraps">{w.end != null && <>Off at {clock(w.end)} · </>}{wearLine(shown, clock)}</span>
          </span>
          {badge && <FullnessBadge fullness={w.fullness} />}
        </button>
        {children}
        {alone && (
          <button type="button" className="link-row wear-puton" onClick={() => !readOnly && onOpen(alone)} disabled={readOnly}>Put on at {clock(alone.at)}</button>
        )}
      </div>
    </li>
  );
}

/** Everything about one entry, showing only the fields this person uses. */
function EntryDialog({ entry, settings, supplies, wearing, onClose, onSaved, onPadChange, onSavedThenChange }: {
  entry: ContinenceEntry; settings: ContinenceSettings; supplies: Supply[]; wearing?: ContinenceWearing; onClose: () => void; onSaved: () => void;
  /** A change has its own sheet. */
  onPadChange: () => void;
  /** "Save and change": the accident is saved, then the Change sheet opens from it. */
  onSavedThenChange?: (saved: ContinenceEntry) => void;
}) {
  const { has } = useFields();
  const [e, setE] = useState(entry);
  const [busy, setBusy] = useState(false);
  // A new accident in what's on: one line says so, with the picker a tap away.
  const [picking, setPicking] = useState(false);
  const inOn = inWhatsOn(e, wearing) && !picking;
  const save = async (then?: "change") => {
    setBusy(true);
    try {
      const saved = await api.saveContinence(e);
      toast("Saved");
      onSaved();
      if (then === "change" && onSavedThenChange) onSavedThenChange(saved); else onClose();
    } catch (err) { errorToast(err); setBusy(false); }
  };
  const local = (ms: number) => { const d = new Date(ms); return `${isoDate(d)}T${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`; };
  const isLeak = e.kind === "LEAK" || e.kind === "BOWEL_LEAK" || e.kind === "BEDWETTING";
  const isVoid = e.kind === "URINATION" || e.kind === "CATHETER";
  const worn = e.kind === "PAD_CHANGE" || isLeak;
  const kinds = KINDS.filter((k) => (k.id !== "BOWEL_LEAK" || has(F.CONTINENCE_BOWEL_ACCIDENTS)) && (k.id !== "CATHETER" || has(F.CONTINENCE_CATHETER)));
  const supply = supplies.find((x) => x.id === e.supplyId);
  const lost = e.padWeightG != null && supply?.dryWeightG != null ? Math.max(0, e.padWeightG - supply.dryWeightG) : null;
  const wearable = supplies.filter((x) => x.active && ["LINER", "PAD", "BOOSTER", "PULL_UP", "BRIEF", "BED_PAD", "REUSABLE_UNDERWEAR", "OTHER"].includes(x.kind));
  // What it was in: first, for an accident in what's on now; otherwise with the rest of the detail.
  const product = worn && has(F.CONTINENCE_PROTECTION) ? (

        <div className="subsection">
          {inOn ? (
            <p className="in-whats-on">In your <strong>{supply?.name ?? "product"}</strong> <button type="button" className="link-btn" onClick={() => setPicking(true)}>Not this</button></p>
          ) : <h4 className="subsection-title">{e.kind === "PAD_CHANGE" ? "What you changed" : "What you were wearing"}</h4>}
          {inOn ? null : wearable.length > 0 ? (
            <Field label="Product">
              <select value={e.supplyId ?? ""} onChange={(ev) => setE({ ...e, supplyId: ev.target.value || undefined })}>
                <option value="">Nothing / not saying</option>
                {wearable.map((x) => <option key={x.id} value={x.id}>{x.name} · {supplyKindLabel(x.kind)}{x.absorbency ? ` · ${x.absorbency}` : ""}</option>)}
              </select>
            </Field>
          ) : <p className="muted small">Add your pads, briefs or pull-ups on the <Link to="/supplies">Supplies</Link> page to pick them here and count them down.</p>}
          <FullnessPicker label={inOn ? "How full it is now" : undefined} value={e.fullness} onChange={(fullness) => setE({ ...e, fullness })} />
          {e.kind === "PAD_CHANGE" && (
            <Field label="How many"><input inputMode="numeric" value={e.count ?? 1} onChange={(ev) => setE({ ...e, count: Math.max(1, Number(ev.target.value) || 1) })} /></Field>
          )}
          {has(F.CONTINENCE_PAD_WEIGHT) && (
            <Field label="Weighed (grams)" help={supply?.dryWeightG != null ? `Dry it weighs ${num(supply.dryWeightG)} g` : "Add the dry weight to the product to see what was lost"}>
              <input inputMode="decimal" value={e.padWeightG ?? ""} onChange={(ev) => setE({ ...e, padWeightG: ev.target.value ? Number(ev.target.value.replace(",", ".")) : undefined })} />
            </Field>
          )}
          {lost != null && <p className="loss-line" aria-live="polite">About <strong>{num(lost)} g</strong> lost (roughly {num(lost)} ml).</p>}
        </div>
  ) : null;
  return (
    <Modal wide title={entry.id ? "Edit entry" : "Add to the diary"} onClose={onClose} footer={<>
      <Button variant="ghost" onClick={onClose}>Cancel</Button>
      {!entry.id && isAccident(e.kind) && !!e.supplyId && onSavedThenChange && <Button variant="tonal" disabled={busy} onClick={() => save("change")}>Save and change</Button>}
      <Button variant="primary" busy={busy} onClick={() => save()}>Save</Button>
    </>}>
      <div className="tag-list" role="radiogroup" aria-label="What happened">
        {kinds.map((k) => <button key={k.id} type="button" role="radio" aria-checked={e.kind === k.id} className={`chip ${e.kind === k.id ? "is-on" : ""}`} onClick={() => (k.id === "PAD_CHANGE" && !entry.id ? onPadChange() : setE({ ...e, kind: k.id }))}>{k.label}</button>)}
      </div>
      <Field label="When"><input type="datetime-local" value={local(e.at)} onChange={(ev) => setE({ ...e, at: new Date(ev.target.value).getTime() })} /></Field>
      {inOn && product}

      {isLeak && e.kind !== "BEDWETTING" && <OneOf label="What leaked" options={LEAK_CONTENTS} value={e.contents} onChange={(contents) => setE({ ...e, contents })} />}
      {(isVoid || isLeak) && has(F.CONTINENCE_URGENCY) && (
        <OneOf label="Urgency" options={["None", "Mild", "Strong", "Couldn't wait"].map((u, i) => ({ id: i, label: u }))} value={e.urgency} onChange={(urgency) => setE({ ...e, urgency })} />
      )}
      {isVoid && has(F.CONTINENCE_VOLUME) && <Field label="Volume (ml)" help={e.kind === "CATHETER" ? "What the catheter drained" : "If you measure it, with a jug or a hat"}><input inputMode="numeric" value={e.volumeMl ?? ""} onChange={(ev) => setE({ ...e, volumeMl: Number(ev.target.value) || undefined })} /></Field>}
      {(isLeak || e.kind === "URINATION") && has(F.CONTINENCE_LEAK_SIZE) && (
        <OneOf label={isLeak ? "How much" : "Any leak on the way?"} options={LEAKS} value={e.leak} onChange={(leak) => setE({ ...e, leak })} />
      )}
      {(isLeak || e.leak) && has(F.CONTINENCE_WARNING) && (
        <Field label="Warning you had" help="Seconds from feeling the urge to the leak">
          <div className="tag-list">
            {[{ s: 0, l: "None" }, { s: 10, l: "Seconds" }, { s: 60, l: "About a minute" }, { s: 300, l: "A few minutes" }].map((w) => (
              <button key={w.s} type="button" className={`chip ${e.warningSeconds === w.s ? "is-on" : ""}`} onClick={() => setE({ ...e, warningSeconds: e.warningSeconds === w.s ? undefined : w.s })}>{w.l}</button>
            ))}
          </div>
        </Field>
      )}
      {(isLeak || e.leak) && has(F.CONTINENCE_AWARENESS) && <OneOf label="Did you feel it?" options={AWARENESS} value={e.awareness} onChange={(awareness) => setE({ ...e, awareness })} />}
      {(isLeak || e.leak) && has(F.CONTINENCE_REACHED_TOILET) && <OneOf label="Did you reach the toilet?" options={REACHED_TOILET} value={e.reachedToilet} onChange={(reachedToilet) => setE({ ...e, reachedToilet })} />}
      {(isLeak || e.leak) && has(F.CONTINENCE_TRIGGERS) && (
        <ChipPicker label="What set it off" options={[...TRIGGERS, ...settings.triggers]} value={e.triggers} onChange={(triggers) => setE({ ...e, triggers })} />
      )}
      {(isLeak || e.leak) && has(F.CONTINENCE_ACTIVITY) && (
        <ChipPicker label="What you were doing" options={[...ACTIVITIES, ...settings.activities]} value={e.activity ?? []} onChange={(activity) => setE({ ...e, activity })} />
      )}
      {has(F.CONTINENCE_LOCATION) && (
        <ChipPicker label="Where you were" multi={false} options={[...PLACES, ...settings.locations]} value={e.location ? [e.location] : []} onChange={(v) => setE({ ...e, location: v[0] })} />
      )}

      {!inOn && product}
      {(isLeak || e.kind === "PAD_CHANGE") && has(F.CONTINENCE_CHANGES) && (
        <div className="two-col">
          <Toggle label="Changed clothes" checked={!!e.changedClothes} onChange={(changedClothes) => setE({ ...e, changedClothes })} />
          <Toggle label="Changed bedding" checked={!!e.changedBedding} onChange={(changedBedding) => setE({ ...e, changedBedding })} />
          <Toggle label="It leaked through" checked={!!e.leakedThrough} onChange={(leakedThrough) => setE({ ...e, leakedThrough })} />
        </div>
      )}
      {(worn || isLeak) && has(F.CONTINENCE_SKIN) && (
        <ScaleRow label="Skin" min={0} max={3} low="Fine" high="Sore" value={e.skin} onChange={(skin) => setE({ ...e, skin })} />
      )}

      {(e.kind === "BOWEL" || e.kind === "BOWEL_LEAK") && has(F.CONTINENCE_BRISTOL) && (
        <Field label="Type (Bristol scale)">
          <div className="bristol">{BRISTOL.map((b, i) => <button key={i} type="button" className={`chip ${e.bristol === i + 1 ? "is-on" : ""}`} onClick={() => setE({ ...e, bristol: e.bristol === i + 1 ? undefined : i + 1 })}><strong>{i + 1}</strong> {b}</button>)}</div>
        </Field>
      )}
      {(isVoid || e.kind === "BOWEL") && (
        <div className="two-col">
          <Toggle label="Pain or burning" checked={e.pain} onChange={(pain) => setE({ ...e, pain })} />
          <Toggle label="Blood" checked={e.blood} onChange={(blood) => setE({ ...e, blood })} />
        </div>
      )}
      {isVoid && has(F.CONTINENCE_UTI_SIGNS) && (
        <ChipPicker label="Signs of an infection" allowOwn={false} options={UTI_SIGNS} value={e.utiSigns ?? []} onChange={(utiSigns) => setE({ ...e, utiSigns })} />
      )}
      {(e.pain || e.blood || (e.utiSigns ?? []).length > 0) && <p className="muted small">Pain, burning, blood or signs of an infection are worth checking with a doctor soon.</p>}
      <CustomFieldInputs fields={settings.customFields} values={e.details ?? {}} onChange={(details) => setE({ ...e, details })} />
      <Field label="Note"><input value={e.note ?? ""} onChange={(ev) => setE({ ...e, note: ev.target.value || undefined })} /></Field>
    </Modal>
  );
}

const ARROW: Record<Verdict, typeof TrendingUp> = { IMPROVING: TrendingUp, STEADY: Minus, WORSENING: TrendingDown, NOT_ENOUGH: Minus };

/** Getting better or worse: the latest weeks against the ones before, number by number. */
function ProgressCard({ progress }: { progress: ContinenceProgress }) {
  const Icon = ARROW[progress.verdict];
  const weeks = progress.weeks.filter((w) => w.days > 0);
  const series = [
    { key: "leaksPerDay" as const, label: "Leaks a day", color: "var(--c3)" },
    { key: "voidsPerDay" as const, label: "Trips a day", color: "var(--c1)" },
    { key: "nightVoidsPerDay" as const, label: "Trips at night", color: "var(--c2)" },
  ].filter((x) => weeks.some((w) => w[x.key] != null));
  return (
    <Card title="How it's going" icon={<Icon size={18} />} className="span-2">
      <div className={`verdict verdict-${progress.verdict.toLowerCase()}`}>
        <span className="verdict-label">{VERDICT_LABEL[progress.verdict]}</span>
        <span>{progress.headline}</span>
      </div>
      {progress.metrics.length > 0 && (
        <ul className="progress-metrics">
          {progress.metrics.map((m) => <MetricRow key={m.key} m={m} />)}
        </ul>
      )}
      {weeks.length >= 2 && series.length > 0 && (
        <div className="progress-charts">
          {series.map((x) => (
            <div key={x.key}>
              <h4 className="subsection-title">{x.label}</h4>
              <LineChart label={`${x.label}, week by week`} color={x.color} dots height={140}
                points={weeks.filter((w) => w[x.key] != null).map((w) => ({ x: new Date(`${w.weekStart}T12:00`).getTime(), y: w[x.key] as number }))}
                formatX={(t) => shortDate(isoDate(new Date(t)))} formatY={(v) => num(v, 1)} />
            </div>
          ))}
        </div>
      )}
      {progress.notes.length > 0 && <ul className="plain-list">{progress.notes.map((n, i) => <li key={i}>{n}</li>)}</ul>}
      <p className="muted small">The last {progress.recentWeeks} weeks against the {progress.recentWeeks} before. Small changes count: share this with whoever helps you.</p>
    </Card>
  );
}

function MetricRow({ m }: { m: ProgressMetric }) {
  const better = m.verdict === "IMPROVING";
  const worse = m.verdict === "WORSENING";
  const up = m.before != null && m.after != null && m.after > m.before;
  const Arrow = m.before == null || m.after == null || m.after === m.before ? ArrowUpDown : up ? ArrowUp : ArrowDown;
  const fmt = (v?: number) => (v == null ? "–" : `${num(v, v < 10 ? 1 : 0)}${m.unit ? ` ${m.unit}` : ""}`);
  return (
    <li className={`progress-metric ${better ? "is-better" : worse ? "is-worse" : ""}`}>
      <span className="progress-label">{m.label}</span>
      <span className="progress-values">{fmt(m.before)} <Arrow size={14} aria-hidden="true" /> {fmt(m.after)}</span>
      <span className="progress-verdict">{VERDICT_LABEL[m.verdict]}</span>
    </li>
  );
}

const IMPACT = ["sleep", "work", "going out", "exercise", "sex", "travel", "socialising", "mood"];
const COMPARED = ["Much better", "A bit better", "The same", "A bit worse", "Much worse"];

/** Once a week: how much it bothers you, and what it gets in the way of. */
function CheckInsCard({ readOnly, onSaved }: { readOnly: boolean; onSaved: () => void }) {
  const list = useLoad(() => api.continenceCheckIns(180).catch(() => [] as ContinenceCheckIn[]), []);
  const [draft, setDraft] = useState<ContinenceCheckIn | null>(null);
  const items = [...(list.data ?? [])].sort((a, b) => b.date.localeCompare(a.date));
  const last = items[0];
  const due = !last || (Date.now() - new Date(`${last.date}T12:00`).getTime()) > 6 * 86_400_000;
  const save = async () => {
    if (!draft) return;
    try { await api.addContinenceCheckIn(draft); toast("Check-in saved"); setDraft(null); list.reload(); onSaved(); } catch (e) { errorToast(e); }
  };
  return (
    <Card title="How much it bothers you" icon={<Sparkles size={18} />}>
      {items.length > 1 && (
        <LineChart label="Bother, week by week (0 to 10)" color="var(--c1)" dots height={120}
          points={[...items].reverse().map((c) => ({ x: new Date(`${c.date}T12:00`).getTime(), y: c.bother }))}
          formatX={(t) => shortDate(isoDate(new Date(t)))} formatY={(v) => `${num(v)} of 10`} />
      )}
      {last ? <p className="muted">Last time ({shortDate(last.date)}): {last.bother} of 10{last.impact.length ? `, getting in the way of ${last.impact.join(", ")}` : ""}.</p>
        : <p className="muted">A quick weekly check-in shows whether things are getting easier, alongside the numbers.</p>}
      {!readOnly && (due ? <Button variant="primary" onClick={() => setDraft({ date: today(), bother: 5, impact: [] })}>Check in for this week</Button>
        : <Button variant="ghost" onClick={() => setDraft({ date: today(), bother: last?.bother ?? 5, impact: [] })}>Check in again</Button>)}
      {draft && (
        <Modal title="This week" onClose={() => setDraft(null)} footer={<><Button variant="ghost" onClick={() => setDraft(null)}>Cancel</Button><Button variant="primary" onClick={save}>Save</Button></>}>
          <Field label={`How much it bothered you: ${draft.bother} of 10`}>
            <input type="range" min={0} max={10} value={draft.bother} onChange={(e) => setDraft({ ...draft, bother: Number(e.target.value) })} style={{ ["--fill" as string]: `${draft.bother * 10}%` }} aria-label="Bother, 0 not at all to 10 a great deal" />
            <div className="range-ends"><span>Not at all</span><span>A great deal</span></div>
          </Field>
          <OneOf label="Compared with a month ago" options={COMPARED.map((c, i) => ({ id: i - 2, label: c }))} value={draft.compared} onChange={(compared) => setDraft({ ...draft, compared })} />
          <ChipPicker label="It got in the way of" options={IMPACT} value={draft.impact} onChange={(impact) => setDraft({ ...draft, impact })} />
          <Field label="Note"><input value={draft.note ?? ""} onChange={(e) => setDraft({ ...draft, note: e.target.value || undefined })} /></Field>
        </Modal>
      )}
    </Card>
  );
}

/** Today's gap between trips, if bladder training is on. */
export function trainingInterval(s: ContinenceSettings, date = today()): number {
  return trainingMinutes(s.bladderTraining, date);
}

function TrainingCard({ settings }: { settings: ContinenceSettings }) {
  const t = settings.bladderTraining;
  const now = trainingInterval(settings);
  const hm = (m: number) => (m >= 60 ? `${Math.floor(m / 60)}h${m % 60 ? ` ${m % 60}m` : ""}` : `${m} min`);
  return (
    <Card title="Bladder training" icon={<Timer size={18} />}>
      <div className="stats">
        <Stat label="Between trips now" value={hm(now)} sub={`${t.from}–${t.to}`} />
        <Stat label="Aiming for" value={hm(t.goalMinutes)} sub={`+${t.stepMinutes} min every ${t.stepEveryDays} days`} />
      </div>
      <p className="muted small">Reminders come at each interval while you're awake. When an urge comes early, try to wait it out: sit still, squeeze your pelvic floor a few times, breathe slowly. It gets easier.</p>
    </Card>
  );
}

/** Squeeze and relax, with a count: a common routine is 10 slow holds and 10 quick squeezes. */
function PelvicFloor({ onClose, onLogged }: { onClose: () => void; onLogged: () => void }) {
  const [reps, setReps] = useState(10);
  const [hold, setHold] = useState(5);
  const [running, setRunning] = useState(false);
  const [rep, setRep] = useState(0);
  const [phase, setPhase] = useState<"squeeze" | "relax">("squeeze");
  const [left, setLeft] = useState(hold);
  const timer = useRef<number | undefined>(undefined);
  useEffect(() => {
    if (!running) return;
    timer.current = window.setInterval(() => {
      setLeft((l) => {
        if (l > 1) return l - 1;
        setPhase((p) => {
          if (p === "squeeze") return "relax";
          setRep((r) => r + 1);
          return "squeeze";
        });
        return hold;
      });
    }, 1000);
    return () => window.clearInterval(timer.current);
  }, [running, hold]);
  const done = rep >= reps;
  useEffect(() => { if (done) setRunning(false); }, [done]);
  const log = async () => {
    try {
      await api.saveContinence({ at: Date.now(), kind: "PELVIC_FLOOR", reps: Math.max(1, rep), holdSeconds: hold, triggers: [], pain: false, blood: false, night: false });
      toast("Exercise logged");
      onLogged();
      onClose();
    } catch (e) { errorToast(e); }
  };
  return (
    <Modal title="Pelvic floor exercise" onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Close</Button>{(done || rep > 0) && <Button variant="primary" onClick={log}>Log it</Button>}</>}>
      <p className="muted">Squeeze as if stopping wind and the flow of urine at the same time, lift, hold, then let go fully. Keep breathing, and keep your stomach, thighs and bottom relaxed.</p>
      <div className="breath" aria-live="polite">
        <div className="breath-circle" style={{ transform: `scale(${running && phase === "squeeze" ? 1 : 0.6})`, transitionDuration: "0.8s" }} />
        <div className="breath-text">
          <strong>{done ? "Done!" : running ? (phase === "squeeze" ? "Squeeze and hold" : "Relax") : "Ready when you are"}</strong>
          {running && <span>{left}</span>}
          <small>{Math.min(rep, reps)} of {reps}</small>
        </div>
      </div>
      {!running && !done && (
        <div className="two-col">
          <Field label="Repetitions"><input inputMode="numeric" value={reps} onChange={(e) => setReps(Number(e.target.value) || 10)} /></Field>
          <Field label="Hold (seconds)"><input inputMode="numeric" value={hold} onChange={(e) => { setHold(Number(e.target.value) || 5); setLeft(Number(e.target.value) || 5); }} /></Field>
        </div>
      )}
      <div className="button-row" style={{ justifyContent: "center" }}>
        {!done && <Button variant="primary" onClick={() => setRunning(!running)}>{running ? "Pause" : rep > 0 ? "Carry on" : "Start"}</Button>}
      </div>
      <p className="muted small">A pelvic health physiotherapist can check you're doing them right; it makes a big difference.</p>
    </Modal>
  );
}

/** The last three days as a bladder diary, to print or download for a doctor. */
function Diary({ onClose }: { onClose: () => void }) {
  const entries = useLoad(() => api.continence(3), []);
  const summary = useLoad(() => api.continenceSummary(3), []);
  const rows = [...(entries.data ?? [])].reverse();
  const water = useWater(useModules());
  const csv = () => {
    const lines = [["Date", "Time", "Type", "Volume (ml)", "Urgency (0-3)", "Leak", "Fullness", "Lost (g)", "Triggers", "Activity", "Bristol", "Night", "Note"].join(",")];
    rows.forEach((e) => lines.push([e.date, clock(e.at), kindLabel(e.kind), e.volumeMl ?? "", e.urgency ?? "", e.leak ?? "", e.fullness ?? "", e.lossG ?? "",
      e.triggers.join("; "), (e.activity ?? []).join("; "), e.bristol ?? "", e.night ? "yes" : "", (e.note ?? "").replace(/[",\n]/g, " ")].join(",")));
    if (water) (summary.data?.days ?? []).forEach((d) => lines.push(["", "", `Water on ${d.date}`, d.fluidMl, "", "", "", "", "", "", "", "", ""].join(",")));
    const blob = new Blob([lines.join("\n")], { type: "text/csv" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `bladder-diary-${today()}.csv`;
    a.click();
    URL.revokeObjectURL(a.href);
  };
  return (
    <Modal wide title="3-day bladder and bowel diary" onClose={onClose} footer={<>
      <Button variant="ghost" onClick={csv}><Download size={16} /> Download CSV</Button>
      <Button variant="primary" onClick={() => window.print()}><Printer size={16} /> Print</Button>
    </>}>
      {entries.loading ? <Spinner /> : rows.length === 0 ? <p className="muted">Nothing logged in the last three days.</p> : (
        <div className="table-wrap printable">
          <table className="table">
            <thead><tr><th>Date</th><th>Time</th><th>Type</th><th>ml</th><th>Urgency</th><th>Leak</th><th>Fullness</th><th>Triggers</th><th>Night</th></tr></thead>
            <tbody>
              {rows.map((e) => (
                <tr key={e.id}>
                  <td>{shortDate(e.date!)}</td><td>{clock(e.at)}</td><td>{kindLabel(e.kind)}{e.bristol ? ` (type ${e.bristol})` : ""}</td>
                  <td>{e.volumeMl ?? e.lossG ?? ""}</td><td>{e.urgency ?? ""}</td><td>{e.leak ? LEAKS.find((l) => l.id === e.leak)?.label : ""}</td>
                  <td>{e.fullness ? FULLNESS.find((f) => f.id === e.fullness)?.label : ""}</td><td>{[...e.triggers, ...(e.activity ?? [])].join(", ")}</td><td>{e.night ? "✓" : ""}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {water && summary.data && <p className="muted small">Water logged: {summary.data.days.map((d) => `${shortDate(d.date)} ${num(d.fluidMl)} ml`).join(" · ")}</p>}
        </div>
      )}
    </Modal>
  );
}

/** Settings › Bladder & bowel: quick buttons, your own words, your own fields, bladder training, which details show. */
export function ContinenceSettingsPage() {
  const settings = useLoad(() => api.continenceSettings().catch(() => DEFAULT_SETTINGS), []);
  const supplies = useLoad(() => api.supplies().catch(() => [] as Supply[]), []);
  const [s, setS] = useState<ContinenceSettings | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => { if (settings.data && !s) setS(settings.data); }, [settings.data, s]);
  if (!s) return <div className="page"><PageHeader title="Bladder & bowel settings" /><Spinner /></div>;
  const save = async () => {
    setBusy(true);
    try { setS(await api.saveContinenceSettings(s)); toast("Settings saved"); } catch (e) { errorToast(e); } finally { setBusy(false); }
  };
  const move = (i: number, by: number) => {
    const list = [...s.quickButtons];
    const j = i + by;
    if (j < 0 || j >= list.length) return;
    [list[i], list[j]] = [list[j], list[i]];
    setS({ ...s, quickButtons: list });
  };
  const t = s.bladderTraining;
  return (
    <div className="page">
      <PageHeader title="Bladder & bowel settings" subtitle="Make the diary yours: what's one tap, your own words, and what to record"
        actions={<><Link className="btn btn-ghost" to="/bladder">Back</Link><Button variant="primary" busy={busy} onClick={save}>Save</Button></>} />
      <div className="grid grid-2">
        <Card title="Quick buttons">
          <p className="muted small">One tap logs it now. In the order shown:</p>
          <ul className="rows">
            {s.quickButtons.map((k, i) => (
              <li key={k} className="row">
                <div className="row-main"><div className="row-title">{kindLabel(k)}</div></div>
                <div className="row-actions">
                  <Button variant="ghost" aria-label={`Move ${kindLabel(k)} up`} disabled={i === 0} onClick={() => move(i, -1)}><ArrowUp size={16} /></Button>
                  <Button variant="ghost" aria-label={`Move ${kindLabel(k)} down`} disabled={i === s.quickButtons.length - 1} onClick={() => move(i, 1)}><ArrowDown size={16} /></Button>
                  <Button variant="ghost" aria-label={`Remove ${kindLabel(k)}`} onClick={() => setS({ ...s, quickButtons: s.quickButtons.filter((x) => x !== k) })}><Trash2 size={16} /></Button>
                </div>
              </li>
            ))}
          </ul>
          <div className="tag-list">
            {KINDS.filter((k) => !s.quickButtons.includes(k.id)).map((k) => <button key={k.id} type="button" className="chip" onClick={() => setS({ ...s, quickButtons: [...s.quickButtons, k.id] })}><Plus size={14} /> {k.label}</button>)}
          </div>
        </Card>
        <Card title="Your own words">
          <WordList label="Triggers" words={s.triggers} onChange={(triggers) => setS({ ...s, triggers })} placeholder="e.g. cold weather" />
          <WordList label="What you were doing" words={s.activities} onChange={(activities) => setS({ ...s, activities })} placeholder="e.g. gardening" />
          <WordList label="Places" words={s.locations} onChange={(locations) => setS({ ...s, locations })} placeholder="e.g. the car" />
        </Card>
        <Card title="Your usual product">
          <Field label="Picked first when logging">
            <select value={s.defaultSupplyId ?? ""} onChange={(e) => setS({ ...s, defaultSupplyId: e.target.value || undefined })}>
              <option value="">None</option>
              {(supplies.data ?? []).filter((x) => x.active).map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
            </select>
          </Field>
          <div className="two-col">
            <Field label="Night starts" help="If not from your recorded sleep"><input type="time" value={s.nightFrom ?? ""} onChange={(e) => setS({ ...s, nightFrom: e.target.value || undefined })} /></Field>
            <Field label="Night ends"><input type="time" value={s.nightTo ?? ""} onChange={(e) => setS({ ...s, nightTo: e.target.value || undefined })} /></Field>
          </div>
        </Card>
        <Card title="Change reminder" icon={<BellRing size={18} />}>
          <Toggle label="Remind me to check" help={'A quiet nudge that only says "Time for a check", never what you\'re wearing'} checked={s.changeReminderHours != null}
            onChange={(on) => setS({ ...s, changeReminderHours: on ? s.changeReminderHours ?? 4 : undefined })} />
          {s.changeReminderHours != null && (
            <>
              <Field label="After (hours)" help="From when something goes on">
                <input inputMode="numeric" value={s.changeReminderHours} onChange={(e) => setS({ ...s, changeReminderHours: Math.min(24, Math.max(1, Number(e.target.value) || 1)) })} />
              </Field>
              <Toggle label="At night too" help="Otherwise it waits until your night ends" checked={!!s.changeReminderAtNight} onChange={(changeReminderAtNight) => setS({ ...s, changeReminderAtNight })} />
            </>
          )}
        </Card>
        <Card title="Bladder training" icon={<Timer size={18} />}>
          <Toggle label="Remind me to go at set times" help="Stretching the gap a little at a time helps an overactive bladder" checked={t.enabled}
            onChange={(enabled) => setS({ ...s, bladderTraining: { ...t, enabled, startedOn: enabled ? t.startedOn ?? today() : t.startedOn } })} />
          {t.enabled && (
            <>
              <div className="two-col">
                <Field label="Every (minutes)"><input inputMode="numeric" value={t.intervalMinutes} onChange={(e) => setS({ ...s, bladderTraining: { ...t, intervalMinutes: Number(e.target.value) || 60 } })} /></Field>
                <Field label="Aiming for (minutes)"><input inputMode="numeric" value={t.goalMinutes} onChange={(e) => setS({ ...s, bladderTraining: { ...t, goalMinutes: Number(e.target.value) || 180 } })} /></Field>
              </div>
              <div className="two-col">
                <Field label="From"><input type="time" value={t.from} onChange={(e) => setS({ ...s, bladderTraining: { ...t, from: e.target.value } })} /></Field>
                <Field label="Until"><input type="time" value={t.to} onChange={(e) => setS({ ...s, bladderTraining: { ...t, to: e.target.value } })} /></Field>
              </div>
              <div className="two-col">
                <Field label="Add (minutes)"><input inputMode="numeric" value={t.stepMinutes} onChange={(e) => setS({ ...s, bladderTraining: { ...t, stepMinutes: Number(e.target.value) || 15 } })} /></Field>
                <Field label="Every (days)"><input inputMode="numeric" value={t.stepEveryDays} onChange={(e) => setS({ ...s, bladderTraining: { ...t, stepEveryDays: Number(e.target.value) || 7 } })} /></Field>
              </div>
              <p className="muted small">Today: every {trainingInterval(s)} minutes. A continence nurse or physio can help you set this up.</p>
            </>
          )}
        </Card>
        <Card title="Fields of your own" className="span-2">
          <p className="muted small">Anything else you want to record with each entry.</p>
          <CustomFieldsBuilder fields={s.customFields} onChange={(customFields) => setS({ ...s, customFields })} />
        </Card>
        <Card title="What to record" className="span-2">
          <p className="muted small">Your conditions decide what shows to start with; show or hide any of it. Saved straight away.</p>
          <FieldsEditor module="CONTINENCE" />
        </Card>
      </div>
    </div>
  );
}
