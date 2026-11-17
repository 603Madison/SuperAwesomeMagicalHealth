import { Fragment, useState, type ReactNode } from "react";
import { Link, useNavigate } from "react-router-dom";
import {
  Bell, CalendarClock, ChevronDown, ChevronRight, HeartPulse, Layers, ListChecks, Package, Phone, Pill, Salad, Sparkles, Thermometer, Timer, Watch, Wind,
  type LucideIcon,
} from "lucide-react";
import { FirstRunPrompt } from "./Conditions";
import { useModules } from "../App";
import { FACE_LABELS } from "./Mind";
import { kindLabel } from "./Continence";
import { severityWord } from "./Symptoms";
import { RestockDialog } from "./Supplies";
import { QuickChange } from "./Wearing";
import { useMealie } from "./Mealie";
import { useDigestRow } from "./CareLines";
import { api } from "../lib/api";
import { ALERT_LEVEL_TEXT } from "../lib/alerts";
import { useAlertBadge } from "../lib/alertBadge";
import { wearState } from "../lib/continence";
import { F, useFields, useFoodNumbers } from "../lib/fields";
import { dayLabel, dayOf, dayStart, dayZone, hm, num, shiftDate, today as todayDate, useDayPicker } from "../lib/format";
import { NAMES } from "../lib/nav";
import { useSession } from "../lib/session";
import {
  clockTime, dayRows, fightingSomething, forYou, hourIn, leadAlert, nextTrip, nowRows, scoreSentence, trainingMinutes, type DayRow, type NowKind,
  type NowRow, type Say,
} from "../lib/today";
import { LAYOUT_TODAY, type DaySummary, type Score, type SupplyStatus, type TodaySection } from "../lib/types";
import { useReadOnly, useViewing } from "../lib/viewing";
import { Meter } from "../components/charts";
import { Button, CountBadge, ErrorNotice, Modal, Spinner, errorToast, toast, useLoad } from "../components/ui";
import { ArrangeButton, ArrangePanel, type Section } from "../components/Arrange";
import { useScreenLayout } from "../lib/layout";
import { arrange } from "../lib/types";
import { InsightRow } from "../components/Insights";
import { WorkoutDialog } from "../components/LogDialogs";
import { useLogged } from "../components/LogSheet";

/*
 * Today, top to bottom: the health alert that's still active, when there is one (always first, never
 * hidden); the three scores with one plain sentence; Right now, only what's live, each with one
 * thing to do; Your day, everything logged or recorded as one timeline, newest first; and at most
 * two things worth knowing. The four sections can be moved and hidden (Arrange, Layouts.TODAY: their
 * ids are shared with the phone app; the old cards' ids stay saved, unused here). The date picks
 * another day: then Right now goes, and Your day shows that day.
 *
 * Right now and Your day are made here from what the web loads (lib/today.ts) until the server's
 * GET /api/feed sends them.
 */

const RINGS: { key: "readiness" | "sleep" | "activity"; label: string; color: string }[] = [
  { key: "readiness", label: "Readiness", color: "var(--c2)" },
  { key: "sleep", label: "Sleep", color: "var(--c1)" },
  { key: "activity", label: "Activity", color: "var(--c3)" },
];

/** Today's sections, in the default order, with the names Arrange shows. */
const SECTIONS: { id: TodaySection; label: string }[] = [
  { id: "SCORES", label: "Scores" },
  { id: "UP_NEXT", label: "Right now" },
  { id: "DAY", label: "Your day" },
  { id: "INSIGHTS", label: "For you" },
];

const NOW_ICONS: Record<NowKind, LucideIcon> = {
  wearing: Layers, dose: Pill, food: Salad, appointment: CalendarClock, routine: ListChecks, energy: Sparkles, trip: Timer, restock: Package, voice: Sparkles,
};

export function Today() {
  // Today until another day is picked; left open overnight, it moves on to the new day.
  const [date, setDate, now] = useDayPicker();
  const logged = useLogged();
  const { data, error, loading, reload } = useLoad(() => api.day(date), [date, logged]);
  const zone = useSession((s) => s.profile?.timezone) || undefined;
  const on = useModules();
  const { has } = useFields();
  const foodNumbers = useFoodNumbers();
  const readOnly = useReadOnly();
  const viewing = useViewing((s) => s.share);
  const navigate = useNavigate();
  const is = (id: string) => on.includes(id as never);
  const isToday = date === now;
  const [score, setScore] = useState<{ title: string; score: Score } | null>(null);
  const [arranging, setArranging] = useState(false);
  const [changing, setChanging] = useState(false);
  const [restock, setRestock] = useState<SupplyStatus | null>(null);
  const [workout, setWorkout] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  // Until something's picked in What you track: a gentle prompt, dismissed per browser.
  const [skipFirstRun, setSkipFirstRun] = useState(() => { try { return localStorage.getItem("samh.firstRun.skip") === "1"; } catch { return false; } });
  const conditions = useLoad(() => (viewing || skipFirstRun ? Promise.resolve(null) : api.conditions().catch(() => null)), [viewing, skipFirstRun]);
  const showFirstRun = !viewing && !skipFirstRun && conditions.data != null && conditions.data.conditions.length === 0;

  // What Your day needs beyond the day's summary: the food diary, the bladder diary and moods for the day.
  const back = Math.min(30, Math.max(1, Math.round((dayStart(todayDate()) - dayStart(date)) / 86_400_000) + 1));
  const food = useLoad(() => (is("NUTRITION") ? api.foodDay(date).catch(() => null) : Promise.resolve(null)), [date, logged, is("NUTRITION")]);
  const bladder = useLoad(() => (is("CONTINENCE") ? api.continence(back).catch(() => null) : Promise.resolve(null)), [date, logged, is("CONTINENCE")]);
  const moods = useLoad(() => (is("MIND") ? api.moods(back).catch(() => null) : Promise.resolve(null)), [date, logged, is("MIND")]);
  // How serious the active alert is (the person's own only).
  const alerts = useLoad(() => (!readOnly && isToday ? api.alerts(3).catch(() => null) : Promise.resolve(null)), [readOnly, isToday, logged]);
  // Bladder training's next trip: the person's own settings, today only.
  const training = isToday && !readOnly && is("CONTINENCE") && has(F.CONTINENCE_BLADDER_TRAINING);
  const settings = useLoad(() => (training ? api.continenceSettings().catch(() => null) : Promise.resolve(null)), [training]);
  // Tonight's plan from Mealie, when it's connected and not paused.
  const mealie = useMealie();
  const plan = useLoad(() => (isToday && is("NUTRITION") && mealie?.connected && !mealie.paused ? api.mealiePlan(date).catch(() => null) : Promise.resolve(null)),
    [date, isToday, !!mealie?.connected, !!mealie?.paused]);

  const changed = () => { reload(); food.reload(); bladder.reload(); };
  const say: Say = { time: (ms) => clockTime(ms, zone), hm, num: (n) => num(n) };
  const at = Date.now();
  const hour = new Date().getHours();
  const greeting = hour < 5 ? "Good night" : hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening";

  // ---- Right now ----
  const trip = (() => {
    const t = settings.data?.bladderTraining;
    if (!t || !bladder.data) return null;
    const trips = bladder.data.filter((e) => (e.kind === "URINATION" || e.kind === "CATHETER") && dayOf(e.at) === date).map((e) => e.at);
    const next = nextTrip(t, date, trips, at, dayZone());
    return next == null ? null : { at: next, every: trainingMinutes(t, date) };
  })();
  const dinner = plan.data?.entries.find((e) => e.meal === "DINNER" && !e.logged)?.title ?? null;
  const nowList: NowRow[] = data && isToday ? nowRows({
    day: data, on, now: at, hour: hourIn(at, zone), dayFrom: dayStart(date), dayTo: dayStart(shiftDate(date, 1)), foodNumbers, trip, plan: dinner,
    wearingState: data.modules.continence?.wearing?.wear ? wearState(data.modules.continence.wearing.wear).toLowerCase() : null,
    meals: (food.data?.entries ?? []).map((e) => e.meal),
  }, say) : [];

  const act = async (row: NowRow) => {
    switch (row.kind) {
      case "wearing": setChanging(true); return;
      case "food": navigate(`/food?add=${row.meal}`); return;
      case "appointment": navigate("/appointments"); return;
      case "routine": navigate("/neuro#routines"); return;
      case "energy": case "voice": navigate(row.kind === "voice" ? "/transition/voice" : "/neuro"); return;
      case "restock": if (row.supply) setRestock(row.supply); return;
      case "dose": case "trip": {
        setBusy(row.key);
        try {
          if (row.kind === "dose" && row.dose) {
            const d = row.dose;
            await api.logDose({ medicationId: d.medicationId, at: Date.now(), status: "TAKEN", scheduledFor: d.scheduledFor, site: d.nextSite });
            toast(`${d.name} taken`);
          } else {
            await api.saveContinence({ at: Date.now(), kind: "URINATION", triggers: [], pain: false, blood: false, night: false });
            toast("Trip logged");
          }
          changed();
        } catch (e) { errorToast(e); }
        setBusy(null);
      }
    }
  };

  // ---- Your day ----
  const extra: DayRow[] = [];
  for (const e of bladder.data ?? []) {
    if (dayOf(e.at) !== date) continue;
    extra.push({ key: `bladder-${e.id ?? e.at}`, kind: "bladder", at: e.at, to: "/bladder", title: e.kind === "URINATION" ? "Bathroom" : kindLabel(e.kind), detail: e.note });
  }
  for (const m of moods.data ?? []) {
    if (dayOf(m.at) !== date) continue;
    extra.push({ key: `mood-${m.id ?? m.at}`, kind: "mind", at: m.at, to: "/mind", title: `Mood: ${FACE_LABELS[Math.min(4, Math.max(0, Math.round(m.mood) - 1))].toLowerCase()}`, detail: m.feelings.slice(0, 3).join(", ") || undefined });
  }
  for (const s of data?.modules.symptoms?.entries ?? []) {
    extra.push({ key: `symptom-${s.id ?? s.at}`, kind: "symptom", at: s.at, to: "/symptoms", title: s.symptom, detail: severityWord(s.severity) });
  }
  for (const mg of data?.modules.symptoms?.migraines ?? []) {
    extra.push({ key: `migraine-${mg.start}`, kind: "symptom", at: mg.start, to: "/symptoms", title: "Migraine", detail: mg.end ? hm((mg.end - mg.start) / 60_000) : "Still going" });
  }
  const timeline = data ? dayRows({ day: data, isToday, now: at, dayFrom: dayStart(date), foodNumbers, food: food.data?.entries, extra }, say) : [];

  // ---- The alert, the scores' line and For you ----
  const lead = data && isToday && !readOnly ? leadAlert(data.insights, alerts.data?.alerts) : null;
  const rings = RINGS.filter((r) => (r.key === "readiness" ? is("SLEEP") || is("ACTIVITY") : r.key === "sleep" ? is("SLEEP") : is("ACTIVITY")));
  const sentence = data ? scoreSentence(data, isToday && fightingSomething(data.insights)) : null;
  const digest = useDigestRow(!readOnly && isToday);
  const insights = data ? forYou(data.insights, on, digest ? 1 : 2) : [];

  const sections: Section[] = SECTIONS.map(({ id, label }) => {
    switch (id) {
      case "SCORES": return { id, label, when: rings.length > 0, node: data && <Scores day={data} rings={rings} sentence={sentence} readOnly={readOnly} onOpen={(title, s) => setScore({ title, score: s })} /> };
      case "UP_NEXT": return { id, label, node: nowList.length > 0 && <RightNow rows={nowList} readOnly={readOnly} busy={busy} onAct={act} /> };
      case "DAY": return {
        id, label, node: data && <YourDay rows={timeline} isToday={isToday} readOnly={readOnly} link={!readOnly && isToday}
          onOpen={(r) => (r.workoutId && !readOnly ? setWorkout(r.workoutId) : r.to && navigate(r.to))} />,
      };
      default: return {
        id, label, node: data && (insights.length > 0 || digest) && (
          <section className="today-insights" aria-label="For you">
            <ul className="insight-cards">
              {digest}
              {insights.map((i) => <InsightRow key={`${i.icon}-${i.title}`} insight={i} date={date} readOnly={readOnly} onChanged={changed} />)}
            </ul>
          </section>
        ),
      };
    }
  });
  const layout = useScreenLayout(LAYOUT_TODAY);
  const hidden = new Set(layout?.hidden ?? []);
  const offered = sections.filter((s) => s.when !== false);
  const shown = arrange(offered, (s) => s.id, layout).filter((s) => !hidden.has(s.id));
  const editing = workout ? data?.workouts.find((w) => w.id === workout) : undefined;

  const title = viewing ? `${viewing.ownerName}'s ${isToday ? "day" : dayLabel(date).toLowerCase()}` : isToday ? greeting : dayLabel(date);
  return (
    <div className="page today">
      <header className="today-header">
        <div className="today-title">
          <label className="today-date">
            <span>{new Date(`${date}T12:00`).toLocaleDateString(undefined, { weekday: "long", day: "numeric", month: "long" })}</span>
            <ChevronDown size={16} aria-hidden="true" />
            <input type="date" value={date} max={now} onChange={(e) => e.target.value && setDate(e.target.value)} aria-label="Choose a day" />
          </label>
          <h1>{title}</h1>
          {!isToday && <button type="button" className="link-btn today-back" onClick={() => setDate(now)}>Back to today</button>}
        </div>
        <div className="today-actions">
          {!readOnly && <AlertsBell />}
          <ArrangeButton title="Today" onClick={() => setArranging(true)} compact />
        </div>
      </header>

      {showFirstRun && isToday && <FirstRunPrompt onDismiss={() => { try { localStorage.setItem("samh.firstRun.skip", "1"); } catch { /* fine */ } setSkipFirstRun(true); }} />}
      {error && <ErrorNotice message={error} onRetry={reload} />}
      {!data && loading && <div className="center-pad"><Spinner size={28} /></div>}
      {data && (
        <div className="today-stack" style={{ opacity: loading ? 0.6 : 1, transition: "opacity 0.2s" }}>
          {lead && <AlertCard alert={lead} />}
          {shown.map((s) => s.node ? <Fragment key={s.id}>{s.node}</Fragment> : null)}
          {shown.length === 0 && !readOnly && <p className="muted arranged-empty">Everything on Today is hidden. <button type="button" className="link-btn" onClick={() => setArranging(true)}>Arrange</button> to show it again.</p>}
        </div>
      )}

      {arranging && <ArrangePanel screen={LAYOUT_TODAY} title="Today" items={offered} onClose={() => setArranging(false)}
        intro="Move what matters most to the top, and hide what you don't use. A health alert always comes first." />}
      {score && <ScoreDialog title={score.title} score={score.score} onClose={() => setScore(null)} />}
      {changing && <QuickChange onClose={() => setChanging(false)} onSaved={changed} />}
      {restock && <RestockDialog status={restock} onClose={() => setRestock(null)} onSaved={changed} />}
      {editing && <WorkoutDialog workout={editing} onClose={() => setWorkout(null)} onSaved={changed} />}
    </div>
  );
}

/** The health alert that's still active, most serious first: danger colours when it could need care now, a gentle tint otherwise. */
function AlertCard({ alert }: { alert: NonNullable<ReturnType<typeof leadAlert>> }) {
  const urgent = alert.level === "URGENT";
  const Icon = urgent ? Phone : alert.title.toLowerCase().includes("oxygen") ? Wind : alert.title.toLowerCase().includes("fight") ? Thermometer : HeartPulse;
  return (
    <Link to="/alerts" className={`alert-lead level-${alert.level.toLowerCase()}`} aria-label={`${ALERT_LEVEL_TEXT[alert.level]}: ${alert.title}. Open ${NAMES.alerts}`}>
      <span className="alert-lead-icon" aria-hidden="true"><Icon size={20} /></span>
      <span className="alert-lead-text">
        <span className="alert-lead-level">{ALERT_LEVEL_TEXT[alert.level]}</span>
        <span className="alert-lead-title">{alert.title}</span>
        <span className="alert-lead-body">{alert.body}</span>
      </span>
      <ChevronRight size={20} className="alert-lead-chevron" aria-hidden="true" />
    </Link>
  );
}

/** A score's ring: the arc and the number. */
function Ring({ score, color }: { score?: number; color: string }) {
  const size = 64;
  const stroke = 6;
  const r = (size - stroke * 2) / 2 + 1;
  const c = 2 * Math.PI * r;
  return (
    <svg className="score-ring" width={size} height={size} viewBox={`0 0 ${size} ${size}`} aria-hidden="true">
      <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--ring-track)" strokeWidth={stroke} />
      {score != null && (
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke={color} strokeWidth={stroke} strokeLinecap="round"
          strokeDasharray={`${(c * score) / 100} ${c}`} transform={`rotate(-90 ${size / 2} ${size / 2})`} className="ring-arc" />
      )}
      <text x="50%" y="50%" textAnchor="middle" dominantBaseline="central" className="ring-number">{score ?? "–"}</text>
    </svg>
  );
}

/** The three scores in one card, each opening what it's made of, and one plain sentence that opens Body. */
function Scores({ day, rings, sentence, readOnly, onOpen }: {
  day: DaySummary; rings: typeof RINGS; sentence: string | null; readOnly: boolean; onOpen: (title: string, s: Score) => void;
}) {
  if (!rings.some((r) => day[r.key] != null)) {
    return (
      <p className="score-line">
        <Watch size={16} aria-hidden="true" />
        <span>{readOnly ? "No scores for this day" : "Scores appear after a night with your ring or watch"}</span>
        {!readOnly && <Link to="/devices" className="score-line-link">Devices <ChevronRight size={16} aria-hidden="true" /></Link>}
      </p>
    );
  }
  return (
    <section className="scores-card" aria-label="Scores">
      <div className="scores" style={{ gridTemplateColumns: `repeat(${rings.length}, minmax(0, 1fr))` }}>
        {rings.map((r) => {
          const s = day[r.key];
          const body = <><Ring score={s?.score} color={r.color} /><span className="score-name">{r.label}</span></>;
          return s
            ? <button key={r.key} type="button" className="score" onClick={() => onOpen(r.label, s)} aria-label={`${r.label}: ${s.score}, ${s.label}. What it's made of`}>{body}</button>
            : <div key={r.key} className="score" role="img" aria-label={`${r.label}: not enough data yet`}>{body}</div>;
        })}
      </div>
      {sentence && (
        <Link to="/body" className="scores-sentence">
          <span>{sentence}</span>
          <ChevronRight size={18} aria-hidden="true" />
          <span className="sr-only">Open {NAMES.body}</span>
        </Link>
      )}
    </section>
  );
}

/** Right now: one card of rows, each with its icon, title, one line and one button. */
function RightNow({ rows, readOnly, busy, onAct }: { rows: NowRow[]; readOnly: boolean; busy: string | null; onAct: (r: NowRow) => void }) {
  return (
    <section className="today-section" aria-labelledby="right-now">
      <h2 id="right-now" className="today-heading">Right now</h2>
      <ul className="now-card">
        {rows.map((r) => {
          const Icon = NOW_ICONS[r.kind];
          const tone = r.kind === "food" ? "accent" : "primary";
          return (
            <li key={r.key} className={`now-row ${r.late ? "is-late" : ""}`}>
              <span className={`now-icon tone-${tone}`} aria-hidden="true"><Icon size={20} /></span>
              <span className="now-text">
                <span className="now-title">{r.title}</span>
                {r.progress != null && <Meter value={r.progress * 100} max={100} color="var(--accent)" label={`${Math.round(r.progress * 100)}% of the day's target`} />}
                {r.detail && <span className="now-detail">{r.detail}</span>}
              </span>
              {!readOnly && r.action && (
                <Button className={`now-action tone-${tone}`} busy={busy === r.key} onClick={() => onAct(r)} aria-label={`${r.action}: ${r.title}`}>{r.action}</Button>
              )}
            </li>
          );
        })}
      </ul>
    </section>
  );
}

/** How many of Your day's rows show before "Show all". */
const DAY_FIRST = 8;

/** Your day: one timeline card, newest first, each row opening what it's about. */
function YourDay({ rows, isToday, readOnly, link, onOpen }: { rows: DayRow[]; isToday: boolean; readOnly: boolean; link: boolean; onOpen: (r: DayRow) => void }) {
  const [all, setAll] = useState(false);
  const list = all ? rows : rows.slice(0, DAY_FIRST);
  const row = (r: DayRow): ReactNode => {
    const body = (
      <>
        <span className={`day-time ${r.at == null ? "is-now" : ""}`}>{r.at == null ? "Now" : clockTime(r.at, dayZone())}</span>
        <span className="day-dot-col" aria-hidden="true"><span className={`day-dot kind-${r.kind}`} /></span>
        <span className="day-text">
          <span className={r.at == null ? "day-now" : "day-title"}>{r.title}</span>
          {r.detail && <span className="day-detail">{r.detail}</span>}
          {r.stages && <StageBar stages={r.stages} />}
        </span>
      </>
    );
    const opens = (r.workoutId && !readOnly) || r.to;
    return (
      <li key={r.key} className="day-row">
        {opens ? <button type="button" className="day-row-btn" onClick={() => onOpen(r)}>{body}</button> : <div className="day-row-btn">{body}</div>}
      </li>
    );
  };
  return (
    <section className="today-section" aria-labelledby="your-day">
      <div className="today-heading-row">
        <h2 id="your-day" className="today-heading">Your day</h2>
        {link && <Link to="/body" className="today-heading-link">{NAMES.recent}</Link>}
      </div>
      <div className="day-card">
        {rows.length === 0 ? (
          <p className="muted day-empty">{isToday ? "Nothing yet today. What you log, and what your devices record, shows here." : "Nothing logged or recorded that day."}</p>
        ) : (
          <ul className="day-rows">{list.map(row)}</ul>
        )}
        {rows.length > DAY_FIRST && (
          <button type="button" className="link-btn day-more" aria-expanded={all} onClick={() => setAll(!all)}>{all ? "Show less" : `Show all ${rows.length}`}</button>
        )}
      </div>
    </section>
  );
}

/** A night as a thin bar of its stages, in their colours. */
function StageBar({ stages }: { stages: { stage: string; start: number; end: number }[] }) {
  const from = stages[0].start;
  const span = Math.max(1, stages[stages.length - 1].end - from);
  return (
    <span className="stage-bar" aria-hidden="true">
      {stages.map((s, i) => <span key={i} className={`stage-${s.stage === "ASLEEP" ? "light" : s.stage.toLowerCase()}`} style={{ width: `${((s.end - s.start) / span) * 100}%` }} />)}
    </span>
  );
}

export function ScoreDialog({ title, score, onClose }: { title: string; score: Score; onClose: () => void }) {
  const color = title === "Sleep" ? "var(--c1)" : title === "Readiness" ? "var(--c2)" : "var(--c3)";
  return (
    <Modal title={`${title}: ${score.score}`} onClose={onClose}>
      <p className="muted">{score.label}. What it's made of, each scored out of 100:</p>
      <ul className="contributors">
        {score.contributors.map((c) => (
          <li key={c.key} className="contributor">
            <span className="contributor-label">{c.label}</span>
            <span className="contributor-value">{c.value} · <strong>{c.score}</strong></span>
            <Meter value={c.score} max={100} color={color} label={`${c.label}: ${c.score} of 100`} />
            {c.detail && <span className="contributor-detail">{c.detail}</span>}
          </li>
        ))}
      </ul>
      <Link to="/body?span=week" className="link-btn" onClick={onClose}>Your week in Body <ChevronRight size={16} aria-hidden="true" /></Link>
    </Modal>
  );
}

/** Today's bell: Health alerts, with how many haven't been seen yet. */
function AlertsBell() {
  const unseen = useAlertBadge();
  return (
    <Link to="/alerts" className="icon-btn today-bell" title={NAMES.alerts} aria-label={unseen ? `${NAMES.alerts}, ${unseen} new` : NAMES.alerts}>
      <Bell size={22} aria-hidden="true" />
      <CountBadge count={unseen} className="bell-badge" />
    </Link>
  );
}
