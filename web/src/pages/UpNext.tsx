import { useState, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { CalendarClock, Layers, ListChecks, ListTodo, Package, Pill, Timer } from "lucide-react";
import { api } from "../lib/api";
import { F, useFields } from "../lib/fields";
import { clock, hm, isoDate, shiftDate, today } from "../lib/format";
import { appointmentsToday, dosesDue, nextTrip, orderUpNext, trainingMinutes, type UpNext } from "../lib/today";
import type { DaySummary, Module, SupplyStatus } from "../lib/types";
import { Button, Card, errorToast, toast, useLoad } from "../components/ui";
import { RestockDialog } from "./Supplies";
import { QuickChange } from "./Wearing";
import { changeDue, hoursShort } from "../lib/continence";

/*
 * "Up next" on Today: what needs doing now, most urgent first, each a row with one thing to do.
 * Doses due or overdue (Take or Skip), today's appointment, a routine still to do, the next
 * bladder-training trip, a change once its reminder time has passed, and a supply to restock. Only on today, and nothing from a section the
 * person hid (Medications, Appointments, Autism & ADHD, Bladder & bowel, Supplies). While viewing
 * someone's share the rows only say what's due.
 */

/** Up next's rows for [day] ([hidden]: Today's hidden sections), and a reload for the bladder-training trip. */
export function useUpNext(day: DaySummary | undefined, date: string, on: Module[], hidden: Set<string>, readOnly: boolean): { items: UpNext[]; reload: () => void } {
  const { has } = useFields();
  const isToday = date === today();
  // The training's settings and today's trips are the person's own: not asked for while viewing a share.
  const training = isToday && !readOnly && on.includes("CONTINENCE") && has(F.CONTINENCE_BLADDER_TRAINING) && !hidden.has("CONTINENCE");
  const trips = useLoad(() => (training ? Promise.all([api.continenceSettings(), api.continence(1)]).catch(() => null) : Promise.resolve(null)), [training, date]);
  if (!day || !isToday) return { items: [], reload: trips.reload };
  const now = Date.now();
  const m = day.modules;
  const items: UpNext[] = [];
  if (on.includes("MEDICATIONS") && !hidden.has("MEDS")) {
    for (const d of dosesDue(m.meds?.due ?? [], now)) items.push({ kind: "dose", key: `dose-${d.medicationId}-${d.scheduledFor}`, at: d.scheduledFor, dose: d, overdue: d.scheduledFor < now });
  }
  if (on.includes("APPOINTMENTS") && !hidden.has("APPOINTMENTS")) {
    const from = new Date(`${date}T00:00`).getTime();
    const to = new Date(`${shiftDate(date, 1)}T00:00`).getTime();
    for (const a of appointmentsToday(m.appointments ?? [], from, to, now)) items.push({ kind: "appointment", key: `appointment-${a.id ?? a.at}`, at: a.at, appointment: a });
  }
  if (on.includes("NEURO") && !hidden.has("NEURO")) {
    for (const name of m.neuro?.routinesDue ?? []) items.push({ kind: "routine", key: `routine-${name}`, name });
  }
  // Time to change, once the person's reminder time has passed (what's on comes with the day, so a share shows it too).
  const wearing = m.continence?.wearing;
  if (on.includes("CONTINENCE") && !hidden.has("CONTINENCE") && wearing && changeDue(wearing, now)) {
    items.push({ kind: "change", key: "change", at: wearing.changeDueAt!, name: wearing.name ?? "what you have on", since: wearing.since! });
  }
  if (trips.data) {
    const [settings, entries] = trips.data;
    const times = entries.filter((e) => (e.kind === "URINATION" || e.kind === "CATHETER") && isoDate(new Date(e.at)) === date).map((e) => e.at);
    const next = nextTrip(settings.bladderTraining, date, times, now);
    if (next != null) items.push({ kind: "trip", key: "trip", at: next, every: trainingMinutes(settings.bladderTraining, date) });
  }
  if ((on.includes("CONTINENCE") || on.includes("CYCLE")) && !hidden.has("SUPPLIES")) {
    for (const s of m.lowSupplies ?? []) items.push({ kind: "restock", key: `restock-${s.supply.id ?? s.supply.name}`, supply: s });
  }
  return { items: orderUpNext(items, now), reload: trips.reload };
}

/** The Up next card; nothing at all when there's nothing to do. */
export function UpNextCard({ items, readOnly, onChanged }: { items: UpNext[]; readOnly: boolean; onChanged: () => void }) {
  const navigate = useNavigate();
  const [restock, setRestock] = useState<SupplyStatus | null>(null);
  const [changing, setChanging] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  if (items.length === 0) return null;
  const now = Date.now();

  const run = async (key: string, work: () => Promise<unknown>, done: string) => {
    setBusy(key);
    try { await work(); toast(done); onChanged(); } catch (e) { errorToast(e); }
    setBusy(null);
  };

  const row = (item: UpNext): { icon: ReactNode; title: string; sub: string; late?: boolean; actions?: ReactNode } => {
    const working = busy === item.key;
    switch (item.kind) {
      case "dose": {
        const d = item.dose;
        const log = (status: "TAKEN" | "SKIPPED") => run(item.key, () => api.logDose({
          medicationId: d.medicationId, at: Date.now(), status, scheduledFor: d.scheduledFor, site: status === "TAKEN" ? d.nextSite : undefined,
        }), status === "TAKEN" ? `${d.name} taken` : `${d.name} skipped`);
        return {
          icon: <Pill size={18} />, title: d.name, late: item.overdue,
          sub: [d.dose, item.overdue ? `was due at ${clock(d.scheduledFor)}` : `due at ${clock(d.scheduledFor)}`, d.nextSite && `${d.nextSite} next`].filter(Boolean).join(" · "),
          actions: <>
            <Button variant="ghost" className="btn-small up-next-skip" disabled={working} onClick={() => log("SKIPPED")} aria-label={`Skip ${d.name}`}>Skip</Button>
            <Button className="btn-small btn-soft" busy={working} onClick={() => log("TAKEN")} aria-label={`Take ${d.name}`}>Take</Button>
          </>,
        };
      }
      case "appointment": {
        const a = item.appointment;
        return {
          icon: <CalendarClock size={18} />, title: a.title, sub: [clock(a.at), a.with, a.place].filter(Boolean).join(" · "),
          actions: <Button className="btn-small btn-soft" onClick={() => navigate("/appointments")} aria-label={`Details of ${a.title}`}>Details</Button>,
        };
      }
      case "routine":
        return {
          icon: <ListChecks size={18} />, title: item.name, sub: "Routine, still to do today",
          actions: <Button className="btn-small btn-soft" onClick={() => navigate("/neuro#routines")} aria-label={`Start ${item.name}`}>Start</Button>,
        };
      case "trip":
        return {
          icon: <Timer size={18} />, title: "Toilet trip", late: item.at < now - 15 * 60_000,
          sub: `${item.at <= now ? "Now" : `Around ${clock(item.at)}`} · every ${hm(item.every)}`,
          actions: <Button className="btn-small btn-soft" busy={working} aria-label="Log a toilet trip" onClick={() => run(item.key, () => api.saveContinence({
            at: Date.now(), kind: "URINATION", triggers: [], pain: false, blood: false, night: false,
          }), "Trip logged")}>Log trip</Button>,
        };
      case "change":
        return {
          // "pants, 6 h" stays together, so the hours never sit on a line alone.
          icon: <Layers size={18} />, title: `Time to change: ${item.name},\u00a0${hoursShort((now - item.since) / 60_000).replace(" ", "\u00a0")}`,
          sub: `On since ${clock(item.since)}`,
          actions: <Button className="btn-small btn-soft" onClick={() => setChanging(true)} aria-label={`Change ${item.name}`}>Change</Button>,
        };
      case "restock": {
        const s = item.supply;
        return {
          icon: <Package size={18} />, title: s.supply.name,
          sub: [`${s.supply.onHand} left`, s.daysLeft != null && `about ${s.daysLeft} ${s.daysLeft === 1 ? "day" : "days"}`].filter(Boolean).join(" · "),
          actions: !s.supply.reusable && <Button className="btn-small btn-soft" onClick={() => setRestock(s)} aria-label={`Restock ${s.supply.name}`}>Restock</Button>,
        };
      }
    }
  };

  return (
    <Card title="Up next" icon={<ListTodo size={18} />} heading="h2" className="up-next">
      <ul className="up-next-rows">
        {items.map((item) => {
          const r = row(item);
          return (
            <li key={item.key} className={`up-next-row ${r.late ? "is-late" : ""}`}>
              <span className="up-next-icon" aria-hidden="true">{r.icon}</span>
              <span className="up-next-text">
                <span className="up-next-title">{r.title}</span>
                <span className="up-next-sub">{r.sub}</span>
              </span>
              {!readOnly && r.actions && <span className="up-next-actions">{r.actions}</span>}
            </li>
          );
        })}
      </ul>
      {restock && <RestockDialog status={restock} onClose={() => setRestock(null)} onSaved={onChanged} />}
      {changing && <QuickChange onClose={() => setChanging(false)} onSaved={onChanged} />}
    </Card>
  );
}
