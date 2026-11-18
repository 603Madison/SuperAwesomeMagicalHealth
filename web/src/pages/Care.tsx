import { useState, type ReactNode } from "react";
import { Link, useNavigate } from "react-router-dom";
import { ChevronRight, ClipboardList, Lock } from "lucide-react";
import { useModules } from "../App";
import { api } from "../lib/api";
import { careAreas, careOrder, type CareArea, type CareAreaInfo } from "../lib/care";
import { wearState, wornFor, onFor, WORN_KINDS } from "../lib/continence";
import { F, useFields, useFoodNumbers } from "../lib/fields";
import { clock, num, useToday } from "../lib/format";
import { useScreenLayout } from "../lib/layout";
import { NAMES } from "../lib/nav";

import { dosesLater, dosesDue } from "../lib/today";
import { LAYOUT_NAV, SENSITIVE, arrange, type DueDose, type Medication, type SupplyStatus } from "../lib/types";
import { useReadOnly, useViewing } from "../lib/viewing";
import { FEATURES } from "../components/features";
import { ArrangeButton, ArrangePanel } from "../components/Arrange";
import { useLogged } from "../components/LogSheet";
import { Button, Card, Empty, ErrorNotice, Spinner, errorToast, toast, useLoad } from "../components/ui";
import {
  cycleLine, foodLine, habitsLine, hormonesLine, lifeStageLine, lifeStageTitle, lineText, mindBudgetLine, mindLine, neuroLine, symptomsLine, transitionLine, vitalsLine,
} from "./CareLines";
import { useMealie } from "./Mealie";
import { QuickChange } from "./Wearing";

/*
 * Care: what the person looks after, one card per area, each saying what's going on now and
 * offering at most one thing to do. Related features sit together (hormone therapy in Medications,
 * supplies in Bladder & bowel, voice, measurements and photos in Transition). Whatever's due in the
 * next few hours comes first, then the person's own order (Layouts.NAV, shared with the phone app;
 * Arrange here). A card's header opens the area's page. While viewing someone's share: the areas
 * they share, to look at.
 */

export function Care() {
  const on = useModules();
  const readOnly = useReadOnly();
  const viewing = useViewing((s) => s.share);
  const date = useToday();
  const logged = useLogged();
  const { has } = useFields();
  const foodNumbers = useFoodNumbers();
  const navigate = useNavigate();
  const is = (m: string) => on.includes(m as never);
  const day = useLoad(() => api.day(date), [date, logged]);
  // What the cards say beyond the day's summary, for the areas that are on.
  const meds = useLoad(() => (is("MEDICATIONS") ? api.meds().catch(() => null) : Promise.resolve(null)), [is("MEDICATIONS"), logged]);
  const week = useLoad(() => (is("CONTINENCE") ? api.continenceSummary(7).catch(() => null) : Promise.resolve(null)), [is("CONTINENCE"), logged]);
  const supplies = useLoad(() => (is("CONTINENCE") || is("CYCLE") ? api.supplyStatus().catch(() => null) : Promise.resolve(null)), [is("CONTINENCE"), is("CYCLE"), logged]);
  const next = useLoad(() => (is("APPOINTMENTS") ? api.appointments(120, true).catch(() => null) : Promise.resolve(null)), [is("APPOINTMENTS"), logged]);
  const voice = useLoad(() => (is("TRANSITION") && has(F.TRANSITION_VOICE) ? api.voiceProgress().catch(() => null) : Promise.resolve(null)), [is("TRANSITION"), has(F.TRANSITION_VOICE), logged]);
  const photos = useLoad(() => (is("PHOTOS") ? api.photos().catch(() => null) : Promise.resolve(null)), [is("PHOTOS"), logged]);
  const food = useLoad(() => (is("NUTRITION") && !foodNumbers ? api.foodDay(date).catch(() => null) : Promise.resolve(null)), [is("NUTRITION"), foodNumbers, date, logged]);
  const mealie = useMealie();
  const plan = useLoad(() => (is("NUTRITION") && mealie?.connected && !mealie.paused ? api.mealiePlan(date).catch(() => null) : Promise.resolve(null)), [date, !!mealie?.connected, !!mealie?.paused]);
  const [changing, setChanging] = useState(false);
  const [arranging, setArranging] = useState(false);
  const [busy, setBusy] = useState(false);
  const layout = useScreenLayout(LAYOUT_NAV);

  const areas = careAreas(on);
  const hidden = new Set(layout?.hidden ?? []);
  const arranged = arrange(areas, (a) => a.id, layout).filter((a) => !hidden.has(a.id));
  const d = day.data;
  const now = Date.now();
  const due: Partial<Record<CareArea, number | null>> = d ? {
    MEDICATIONS: [...dosesDue(d.modules.meds?.due ?? [], now), ...dosesLater(d.modules.meds?.due ?? [], now)][0]?.scheduledFor ?? null,
    CONTINENCE: d.modules.continence?.wearing?.changeDueAt ?? null,
    APPOINTMENTS: next.data?.find((a) => a.at + (a.durationMinutes ?? 60) * 60_000 >= now)?.at ?? null,
  } : {};
  const ordered = careOrder(arranged, due, now);
  const changed = () => { day.reload(); meds.reload(); week.reload(); supplies.reload(); };

  const take = async (dose: DueDose) => {
    setBusy(true);
    try {
      await api.logDose({ medicationId: dose.medicationId, at: Date.now(), status: "TAKEN", scheduledFor: dose.scheduledFor, site: dose.nextSite });
      toast(`${dose.name} taken`);
      changed();
    } catch (e) { errorToast(e); }
    setBusy(false);
  };

  const card = (a: CareAreaInfo): ReactNode => {
    if (!d) return null;
    const m = d.modules;
    const props = { area: a, key: a.id };
    switch (a.id) {
      case "MEDICATIONS": {
        const list = m.meds?.due ?? [];
        const dose = [...dosesDue(list, now), ...dosesLater(list, now)][0];
        const taken = list.filter((x) => x.log?.status === "TAKEN").length;
        const hormones = is("HORMONES") ? lineText(hormonesLine(m.hormones)) : null;
        const low = m.meds?.lowStock ?? [];
        return (
          <AreaCard {...props}>
            {dose ? (
              <NowBox title={`${dose.name} · ${clock(dose.scheduledFor)}`} sub={[`${taken} of ${list.length} taken today`, stockLeft(dose, meds.data, list)].filter(Boolean).join(" · ")}
                action={!readOnly && <Button variant="primary" className="care-btn" busy={busy} onClick={() => void take(dose)} aria-label={`Take ${dose.name}`}>Take</Button>} />
            ) : (
              <p className="care-line">{list.length > 0 ? `All ${list.length} of today's doses done` : "Nothing due today"}</p>
            )}
            {low.length > 0 && <p className="care-line">Running low: {low.map((x) => x.name).join(", ")}</p>}
            {hormones && <Link to="/hormones" className="care-link">Hormone therapy: {hormones} <ChevronRight size={16} aria-hidden="true" /></Link>}
          </AreaCard>
        );
      }
      case "CONTINENCE": {
        const c = m.continence;
        const w = c?.wearing;
        const days = [...(week.data?.days ?? [])].sort((x, y) => x.date.localeCompare(y.date)).slice(-7);
        const weekLeaks = days.reduce((s, x) => s + x.leaks, 0);
        const worn = (supplies.data ?? []).filter((s) => WORN_KINDS.includes(s.supply.kind) && s.supply.active && !s.supply.reusable);
        const shortest = worn.sort((x, y) => (x.daysLeft ?? 1e9) - (y.daysLeft ?? 1e9))[0];
        return (
          <AreaCard {...props}>
            {w?.supplyId && !w.nothingOn && (
              <NowBox title={[`Wearing ${w.name ?? "something"}`, w.wear && wearState(w.wear).toLowerCase()].filter(Boolean).join(" · ")}
                sub={w.since != null ? `On since ${clock(w.since)} (${wornFor(onFor(w, now))})` : undefined}
                action={!readOnly && <Button variant="primary" className="care-btn" onClick={() => setChanging(true)} aria-label={`Change ${w.name ?? "what you have on"}`}>Change</Button>} />
            )}
            {days.length > 1 && <MiniBars values={days.map((x) => x.voids)} today={days[days.length - 1]?.date === date} label={`Toilet trips each day this week: ${days.map((x) => x.voids).join(", ")}`} />}
            {c && (
              <p className="care-line">
                {`${num(c.voids)} ${c.voids === 1 ? "trip" : "trips"} today, ${c.leaks > 0 ? `${c.leaks} ${c.leaks === 1 ? "leak" : "leaks"}` : "no leaks"}`}
                {days.length > 1 && ` · ${weekLeaks === 0 ? "no leaks" : `${weekLeaks} ${weekLeaks === 1 ? "leak" : "leaks"}`} this week`}
              </p>
            )}
            {shortest && <Link to="/supplies" className="care-link">{supplyText(shortest)} <ChevronRight size={16} aria-hidden="true" /></Link>}
          </AreaCard>
        );
      }
      case "APPOINTMENTS": {
        const a2 = next.data?.find((x) => x.at + (x.durationMinutes ?? 60) * 60_000 >= now);
        const questions = a2?.questions.filter((q) => !q.done).length ?? 0;
        return (
          <AreaCard {...props} title={a2 ? "Next appointment" : undefined} sub={a2 ? whenText(a2.at) : "Nothing booked"}>
            {a2 && <p className="care-line">{[a2.title, a2.with, questions > 0 && `${questions} ${questions === 1 ? "question" : "questions"} ready to ask`].filter(Boolean).join(" · ")}</p>}
          </AreaCard>
        );
      }
      case "FOOD": {
        const t = d.nutritionTarget;
        const dinner = plan.data?.entries.find((e) => e.meal === "DINNER")?.title;
        if (!foodNumbers) {
          return <AreaCard {...props} sub={[lineText(foodLine(d, false, food.data?.entries)) ?? "Nothing logged yet", dinner && `tonight: ${dinner}`].filter(Boolean).join(" · ")} />;
        }
        return (
          <AreaCard {...props}>
            {t && <span className="care-progress" role="meter" aria-label={`${num(d.nutrition.calories)} of ${num(t.calories)} kcal`} aria-valuenow={Math.round(d.nutrition.calories)} aria-valuemin={0} aria-valuemax={Math.round(t.calories)}>
              <span style={{ width: `${Math.min(100, (d.nutrition.calories / Math.max(1, t.calories)) * 100)}%` }} />
            </span>}
            <p className="care-line">{[lineText(foodLine(d, true, undefined)) ?? "Nothing logged yet", dinner && `tonight: ${dinner}`].filter(Boolean).join(" · ")}</p>
          </AreaCard>
        );
      }
      case "TRANSITION": {
        const v = voice.data;
        const line = lineText(transitionLine(m.transition));
        const pics = is("PHOTOS") && photos.data ? photos.data.length : null;
        return (
          <AreaCard {...props}>
            {v && (
              <NowBox title="Voice practice" sub={[v.streakDays > 0 && `${v.streakDays} ${v.streakDays === 1 ? "day" : "days"} in a row`, `${num(v.minutesThisWeek)} min this week`].filter(Boolean).join(" · ")}
                action={!readOnly && <Button variant="tonal" className="care-btn is-soft" onClick={() => navigate("/transition/voice")}>Start</Button>} />
            )}
            {line && <p className="care-line">{line}</p>}
            {pics != null && <Link to="/photos" className="care-link">{pics === 0 ? "Progress photos: none yet" : `${pics} progress ${pics === 1 ? "photo" : "photos"}`} <ChevronRight size={16} aria-hidden="true" /></Link>}
          </AreaCard>
        );
      }
      case "NEURO": {
        const n = m.neuro;
        const routines = n?.routinesDue ?? [];
        return <AreaCard {...props} sub={[lineText(neuroLine(n)), routines.length > 0 && `still to do: ${routines.slice(0, 2).join(", ")}`].filter(Boolean).join(" · ") || "Nothing logged today"} />;
      }
      // Mind's own energy budget (only without Autism & ADHD) says what's left with Mind's line.
      case "MIND": return <AreaCard {...props} sub={[lineText(mindLine(m.mind)), !is("NEURO") && mindBudgetLine(m.neuro)].filter(Boolean).join(" · ") || "Nothing logged today"} />;
      case "CYCLE": {
        const stage = m.lifeStage && m.lifeStage.stage !== "CYCLING" && m.lifeStage.stage !== "TRYING" ? m.lifeStage : null;
        const low = (supplies.data ?? []).filter((s) => !WORN_KINDS.includes(s.supply.kind) && s.low);
        return (
          <AreaCard {...props} sub={stage ? `${lifeStageTitle(stage)}: ${lineText(lifeStageLine(stage))}` : lineText(cycleLine(m.cycle)) ?? "Nothing logged today"}>
            {low.length > 0 && <Link to="/supplies" className="care-link">{supplyText(low[0])} <ChevronRight size={16} aria-hidden="true" /></Link>}
          </AreaCard>
        );
      }
      case "VITALS": return <AreaCard {...props} sub={lineText(vitalsLine(m.vitals, m.vitals?.targets.glucoseUnit ?? "MMOL_L")) ?? "No readings today"} />;
      case "SYMPTOMS": return <AreaCard {...props} sub={lineText(symptomsLine(m.symptoms)) ?? "Nothing logged today"} />;
      case "HABITS": return <AreaCard {...props} sub={lineText(habitsLine(m.habits ?? [])) ?? "None set up yet"} />;
      case "HORMONES": return <AreaCard {...props} sub={lineText(hormonesLine(m.hormones)) ?? "Nothing due"} />;
      case "PHOTOS": {
        const pics = photos.data?.length;
        return <AreaCard {...props} sub={pics == null ? undefined : pics === 0 ? "None yet" : `${pics} ${pics === 1 ? "photo" : "photos"}`} />;
      }
    }
  };

  const sensitive = areas.some((a) => SENSITIVE.includes(a.module));
  return (
    <div className="page care-page">
      <header className="page-header">
        <div className="page-titles">
          <h1>{NAMES.care}</h1>
          <p className="page-subtitle">{viewing ? `What ${viewing.ownerName} shares with you` : "What you look after, soonest first"}</p>
        </div>
        {!readOnly && areas.length > 0 && <div className="page-actions"><ArrangeButton title="Care" onClick={() => setArranging(true)} /></div>}
      </header>
      {day.error && <ErrorNotice message={day.error} onRetry={day.reload} />}
      {!d && day.loading && <div className="center-pad"><Spinner size={28} /></div>}
      {d && ordered.length > 0 && <div className="care-grid">{ordered.map(card)}</div>}
      {d && areas.length === 0 && (
        <Card>
          <Empty icon={<ClipboardList />} title={viewing ? "Nothing shared here" : "Nothing to look after yet"}>
            {viewing ? null : "Choose what you track, and what helps with it shows here."}
          </Empty>
        </Card>
      )}
      {d && areas.length > 0 && ordered.length === 0 && <p className="muted arranged-empty">Everything here is hidden. <button type="button" className="link-btn" onClick={() => setArranging(true)}>Arrange</button> to show it again.</p>}
      {!viewing && (
        <div className="care-foot">
          <Link to="/conditions" className="care-choose">{NAMES.chooseTracking}</Link>
          {sensitive && <p className="menu-hint"><Lock size={14} aria-hidden="true" /> Private areas are encrypted on your server, and AI connector links only see them if you allow it.</p>}
        </div>
      )}
      {changing && <QuickChange onClose={() => setChanging(false)} onSaved={changed} />}
      {arranging && (
        <ArrangePanel screen={LAYOUT_NAV} title="Care" items={areas.map((a) => ({ id: a.id, label: a.name }))} onClose={() => setArranging(false)}
          intro="Put what you look after in your order, and hide what you don't need here. Something due soon still comes first. The phone app follows the same order." />
      )}
    </div>
  );
}

/** One area: its header (icon, name, a line) opening its page, then what's going on. */
function AreaCard({ area, title, sub, children }: { area: CareAreaInfo; title?: string; sub?: string; children?: ReactNode }) {
  const feature = FEATURES.find((f) => f.id === area.module);
  const Icon = feature?.icon ?? ClipboardList;
  const tone = area.id === "FOOD" ? "accent" : "primary";
  return (
    <article className="care-card" aria-label={area.name}>
      <Link to={area.to} className="care-head" aria-label={`${title ?? area.name}${sub ? `: ${sub}` : ""}. Open ${area.name}`}>
        <span className={`care-icon tone-${tone}`} aria-hidden="true"><Icon size={20} /></span>
        <span className="care-head-text">
          <span className="care-title">{title ?? area.name}</span>
          {sub && <span className="care-sub">{sub}</span>}
        </span>
        <ChevronRight size={20} className="care-chevron" aria-hidden="true" />
      </Link>
      {children}
    </article>
  );
}

/** What needs doing in an area, in a tinted box, with its one button. */
function NowBox({ title, sub, action }: { title: string; sub?: string; action?: ReactNode }) {
  return (
    <div className="care-now">
      <span className="care-now-text">
        <span className="care-now-title">{title}</span>
        {sub && <span className="care-now-sub">{sub}</span>}
      </span>
      {action}
    </div>
  );
}

/** A week as small bars, today's in the theme's colour. */
function MiniBars({ values, today, label }: { values: number[]; today: boolean; label: string }) {
  const max = Math.max(1, ...values);
  return (
    <div className="mini-bars" role="img" aria-label={label}>
      {values.map((v, i) => <span key={i} className={today && i === values.length - 1 ? "is-today" : ""} style={{ height: `${Math.max(6, (v / max) * 100)}%` }} />)}
    </div>
  );
}

/** "14 briefs left · about a week". */
function supplyText(s: SupplyStatus): string {
  const days = s.daysLeft;
  const left = `${num(s.supply.onHand)} ${s.supply.name} left`;
  if (days == null) return left;
  return `${left} · ${days <= 1 ? "about a day" : days < 7 ? `about ${days} days` : days < 14 ? "about a week" : `about ${Math.round(days / 7)} weeks`}`;
}

/** How much of the next dose's medication is left: "18 days left" where the schedule says how fast it goes. */
function stockLeft(dose: DueDose, meds: Medication[] | null | undefined, today: DueDose[]): string | null {
  const stock = meds?.find((m) => m.id === dose.medicationId)?.stock;
  if (stock == null) return null;
  const perDay = today.filter((d) => d.medicationId === dose.medicationId).length;
  if (perDay === 0) return `${num(stock)} left`;
  const days = Math.floor(stock / perDay);
  return `${days} ${days === 1 ? "day" : "days"} left`;
}

/** "Thursday 24 November, 2:30 pm", or "Today, 2:30 pm". */
function whenText(at: number): string {
  const date = new Date(at);
  const sameDay = date.toDateString() === new Date().toDateString();
  const day = sameDay ? "Today" : date.toLocaleDateString(undefined, { weekday: "long", day: "numeric", month: "long" });
  return `${day}, ${clock(at)}`;
}
