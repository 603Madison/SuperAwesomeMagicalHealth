import { Fragment, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import {
  Bell, CalendarClock, ChevronDown, ChevronRight, CircleDot, HeartPulse, Layers, Mic, Phone, Pill, RefreshCw, Salad, Sparkles, Thermometer, Timer, Watch, Wind,
  type LucideIcon,
} from "lucide-react";
import { FirstRunPrompt } from "./Conditions";
import { useModules } from "../App";
import { QuickChange } from "./Wearing";
import { useDigestRow } from "./CareLines";
import { api } from "../lib/api";
import { ALERT_LEVEL_TEXT } from "../lib/alerts";
import { useAlertBadge } from "../lib/alertBadge";
import { F, useFields } from "../lib/fields";
import { dayLabel, dayOf, dayZone, hm, num, today as todayDate, useDayPicker } from "../lib/format";
import { NAMES } from "../lib/nav";
import { useSession } from "../lib/session";
import {
  actionIds, clockTime, dotOf, fightingSomething, forYou, knownAction, leadAlert, leadFromFeed, nextTrip, nowLine, rightNow, scoreSentence, trainingMinutes,
  tripItem, type LeadAlert, type Say,
} from "../lib/today";
import { LAYOUT_TODAY, arrange, type DaySummary, type NowItem, type Score, type TimelineItem, type TodaySection } from "../lib/types";
import { useReadOnly, useViewing } from "../lib/viewing";
import { useScreenLayout } from "../lib/layout";
import { Meter } from "../components/charts";
import { Button, CountBadge, Modal, Spinner, ErrorNotice, errorToast, toast, useLoad } from "../components/ui";
import { ArrangeButton, ArrangePanel, type Section } from "../components/Arrange";
import { InsightRow, pagePath, usePageOpens } from "../components/Insights";
import { WorkoutDialog } from "../components/LogDialogs";
import { QuickCheckDialog } from "./EnergyFocus";
import { useLogged } from "../components/LogSheet";

/*
 * Today, top to bottom: the health alert that's still active, when there is one (always first, never
 * hidden); the three scores with one plain sentence; Right now, only what's live, each with one
 * thing to do; Your day, everything logged or recorded as one timeline, newest first; and at most
 * two things worth knowing. The four sections can be moved and hidden (Arrange, Layouts.TODAY: their
 * ids are shared with the phone app; the old cards' ids stay saved, unused here). The date picks
 * another day: then Right now goes, and Your day shows that day.
 *
 * Right now and Your day come from the server's day feed (GET /api/feed), shown as it says them.
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

const NOW_ICONS: Record<string, LucideIcon> = {
  WEARING: Layers, DOSE_DUE: Pill, DOSE_OVERDUE: Pill, FOOD_TODAY: Salad, APPOINTMENT_SOON: CalendarClock, ENERGY_TODAY: Sparkles,
  VOICE_PRACTICE_DUE: Mic, HEALTH_ALERT: HeartPulse,
};

export function Today() {
  // Today until another day is picked; left open overnight, it moves on to the new day.
  const [date, setDate, now] = useDayPicker();
  const logged = useLogged();
  const { data, error, loading, reload } = useLoad(() => api.day(date), [date, logged]);
  const feed = useLoad(() => api.feed(date), [date, logged]);
  const zone = useSession((s) => s.profile?.timezone) || undefined;
  const on = useModules();
  const { has } = useFields();
  const readOnly = useReadOnly();
  const viewing = useViewing((s) => s.share);
  const navigate = useNavigate();
  const opens = usePageOpens();
  const isToday = date === now;
  const [score, setScore] = useState<{ title: string; score: Score } | null>(null);
  const [arranging, setArranging] = useState(false);
  const [changing, setChanging] = useState(false);
  const [workout, setWorkout] = useState<string | null>(null);
  const [checking, setChecking] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);

  // Until something's picked in What you track: a gentle prompt, dismissed per browser.
  const [skipFirstRun, setSkipFirstRun] = useState(() => { try { return localStorage.getItem("samh.firstRun.skip") === "1"; } catch { return false; } });
  const conditions = useLoad(() => (viewing || skipFirstRun ? Promise.resolve(null) : api.conditions().catch(() => null)), [viewing, skipFirstRun]);
  const showFirstRun = !viewing && !skipFirstRun && conditions.data != null && conditions.data.conditions.length === 0;

  // Bladder training's next trip (the feed doesn't carry it): the person's own settings and today's trips.
  const training = isToday && !readOnly && on.includes("CONTINENCE") && has(F.CONTINENCE_BLADDER_TRAINING);
  const trips = useLoad(() => (training ? Promise.all([api.continenceSettings(), api.continence(1)]).catch(() => null) : Promise.resolve(null)), [training, date, logged]);

  const changed = () => { reload(); feed.reload(); trips.reload(); };
  const say: Say = { time: (ms) => clockTime(ms, zone), hm, num: (n) => num(n) };
  const at = Date.now();
  const hour = new Date().getHours();
  const greeting = hour < 5 ? "Good night" : hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening";

  // ---- The alert, and Right now ----
  const lead: LeadAlert | null = feed.data ? leadFromFeed(feed.data.now) : data && isToday && !readOnly ? leadAlert(data.insights) : null;
  const trip = (() => {
    if (!trips.data) return null;
    const [settings, entries] = trips.data;
    const times = entries.filter((e) => (e.kind === "URINATION" || e.kind === "CATHETER") && dayOf(e.at) === date).map((e) => e.at);
    const next = nextTrip(settings.bladderTraining, date, times, at, dayZone());
    return next == null ? null : { at: next, every: trainingMinutes(settings.bladderTraining, date) };
  })();
  const nowList = isToday && feed.data ? rightNow(feed.data.now, trip ? [tripItem(trip, at, say)] : [], lead?.id) : [];

  const act = async (item: NowItem) => {
    if (item.id === "trip") return run(item.id, async () => {
      await api.saveContinence({ at: Date.now(), kind: "URINATION", triggers: [], pain: false, blood: false, night: false });
      toast("Trip logged");
    });
    const action = knownAction(item);
    if (!action) return;
    const ids = actionIds(action);
    switch (action.action) {
      case "continence.change": setChanging(true); return;
      case "food.add": navigate(`/food?add=${ids.meal ?? "SNACK"}${ids.date && ids.date !== todayDate() ? `&date=${ids.date}` : ""}`); return;
      // Autism & ADHD's check-in reminder: the check-in, right here. Mind's own budget: its card, on Mind.
      case "energy.add":
        if (on.includes("NEURO") && has(F.NEURO_ENERGY)) setChecking(true);
        else navigate("/mind#energy");
        return;
      case "voice.start": navigate("/transition/voice"); return;
      case "appointment.open": navigate("/appointments"); return;
      case "alert.open": navigate("/alerts"); return;
      case "dose.take": {
        if (!ids.medicationId) return;
        const due = data?.modules.meds?.due.find((d) => d.medicationId === ids.medicationId && d.scheduledFor === ids.scheduledFor);
        return run(item.id, async () => {
          await api.logDose({ medicationId: ids.medicationId!, at: Date.now(), status: "TAKEN", scheduledFor: ids.scheduledFor, site: due?.nextSite });
          toast(`${item.title} taken`);
        });
      }
    }
  };
  const run = async (key: string, work: () => Promise<unknown>) => {
    setBusy(key);
    try { await work(); changed(); } catch (e) { errorToast(e); }
    setBusy(null);
  };

  // ---- Your day ----
  const line = data && isToday ? nowLine(data, at, say) : null;
  const open = (item: TimelineItem) => {
    if (item.kind === "WORKOUT" && item.ref && !readOnly && data?.workouts.some((w) => w.id === item.ref && w.manual)) return setWorkout(item.ref);
    if ((item.kind === "SLEEP" || item.kind === "NAP") && isToday && !readOnly) return navigate("/body");
    if (item.page && opens(item.page)) navigate(pagePath(item.page, date));
  };
  const canOpen = (item: TimelineItem) => !!item.page && opens(item.page);

  // ---- The scores' line and For you ----
  const rings = RINGS.filter((r) => (r.key === "readiness" ? on.includes("SLEEP") || on.includes("ACTIVITY") : r.key === "sleep" ? on.includes("SLEEP") : on.includes("ACTIVITY")));
  const unwell = isToday && (lead?.title.toLowerCase().includes("fighting") || (data ? fightingSomething(data.insights) : false));
  const sentence = data ? scoreSentence(data, unwell) : null;
  const digest = useDigestRow(!readOnly && isToday);
  const insights = data ? forYou(data.insights, on, digest ? 1 : 2) : [];

  const sections: Section[] = SECTIONS.map(({ id, label }) => {
    switch (id) {
      case "SCORES": return { id, label, when: rings.length > 0, node: data && <Scores day={data} rings={rings} sentence={sentence} readOnly={readOnly} onOpen={(title, s) => setScore({ title, score: s })} /> };
      case "UP_NEXT": return { id, label, node: nowList.length > 0 && <RightNow items={nowList} readOnly={readOnly} busy={busy} onAct={act} /> };
      case "DAY": return {
        id, label, node: (
          <YourDay items={feed.data?.items ?? null} error={feed.error} loading={feed.loading} onRetry={feed.reload} now={line} isToday={isToday} link={!readOnly && isToday}
            sleep={data?.sleepSummary} zone={zone} onOpen={open} canOpen={canOpen} />
        ),
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
            <input type="date" value={date} max={now} onChange={(e) => e.target.value && setDate(e.target.value)} onClick={(e) => { try { e.currentTarget.showPicker?.(); } catch { /* the browser opens it its own way */ } }} aria-label="Choose a day" />
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
      {(data || feed.data) && (
        <div className="today-stack" style={{ opacity: loading ? 0.6 : 1, transition: "opacity 0.2s" }}>
          {lead && isToday && !readOnly && <AlertCard alert={lead} />}
          {shown.map((s) => (s.node ? <Fragment key={s.id}>{s.node}</Fragment> : null))}
          {shown.length === 0 && !readOnly && <p className="muted arranged-empty">Everything on Today is hidden. <button type="button" className="link-btn" onClick={() => setArranging(true)}>Arrange</button> to show it again.</p>}
        </div>
      )}

      {arranging && <ArrangePanel screen={LAYOUT_TODAY} title="Today" items={offered} onClose={() => setArranging(false)}
        intro="Move what matters most to the top, and hide what you don't use. A health alert always comes first." />}
      {score && <ScoreDialog title={score.title} score={score.score} onClose={() => setScore(null)} />}
      {changing && <QuickChange onClose={() => setChanging(false)} onSaved={changed} />}
      {checking && <QuickCheckDialog onClose={() => setChecking(false)} onSaved={changed} />}
      {editing && <WorkoutDialog workout={editing} onClose={() => setWorkout(null)} onSaved={changed} />}
    </div>
  );
}

/** The health alert that's still active, most serious first: danger colours when it could need care now, a gentle tint otherwise. */
function AlertCard({ alert }: { alert: LeadAlert }) {
  const urgent = alert.level === "URGENT";
  const words = alert.title.toLowerCase();
  const Icon = urgent ? Phone : words.includes("oxygen") ? Wind : words.includes("fight") || words.includes("ill") ? Thermometer : HeartPulse;
  return (
    <Link to="/alerts" className={`alert-lead level-${alert.level.toLowerCase()}`} aria-label={`${ALERT_LEVEL_TEXT[alert.level]}: ${alert.title}. Open ${NAMES.alerts}`}>
      <span className="alert-lead-icon" aria-hidden="true"><Icon size={20} /></span>
      <span className="alert-lead-text">
        <span className="alert-lead-level">{ALERT_LEVEL_TEXT[alert.level]}</span>
        <span className="alert-lead-title">{alert.title}</span>
        {alert.body && <span className="alert-lead-body">{alert.body}</span>}
      </span>
      <ChevronRight size={20} className="alert-lead-chevron" aria-hidden="true" />
    </Link>
  );
}

/** A score's ring: the arc and the number. */
function Ring({ score, color }: { score?: number; color: string }) {
  const size = 64;
  const stroke = 6;
  const r = (size - stroke) / 2 - 1;
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
function RightNow({ items, readOnly, busy, onAct }: { items: NowItem[]; readOnly: boolean; busy: string | null; onAct: (i: NowItem) => void }) {
  return (
    <section className="today-section" aria-labelledby="right-now">
      <h2 id="right-now" className="today-heading">Right now</h2>
      <ul className="now-card">
        {items.map((item) => {
          const trip = item.id === "trip";
          const Icon = trip ? Timer : NOW_ICONS[item.kind] ?? CircleDot;
          const urgent = item.kind === "HEALTH_ALERT" && item.priority >= 100;
          const tone = urgent ? "danger" : item.kind === "HEALTH_ALERT" ? "warn" : item.kind === "FOOD_TODAY" ? "accent" : item.kind === "OTHER" && !trip ? "plain" : "primary";
          const action = trip ? { label: "Log trip" } : knownAction(item);
          return (
            <li key={item.id} className={`now-row tone-${tone} ${item.kind === "DOSE_OVERDUE" ? "is-late" : ""}`}>
              <span className={`now-icon tone-${tone}`} aria-hidden="true">{urgent ? <Phone size={20} /> : <Icon size={20} />}</span>
              <span className="now-text">
                <span className="now-title">{item.title}</span>
                {item.progress != null && <Meter value={item.progress * 100} max={100} color={item.kind === "FOOD_TODAY" ? "var(--accent)" : "var(--primary)"} label={`${item.title}: ${Math.round(item.progress * 100)}%`} />}
                {item.detail && <span className="now-detail">{item.detail}</span>}
              </span>
              {!readOnly && action && (
                <Button className={`now-action tone-${tone}`} busy={busy === item.id} onClick={() => onAct(item)} aria-label={`${action.label}: ${item.title}`}>{action.label}</Button>
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

/** Your day: one timeline card, newest first (the day's untimed things last), each row opening what it's about. */
function YourDay({ items, error, loading, onRetry, now, isToday, link, sleep, zone, onOpen, canOpen }: {
  items: TimelineItem[] | null; error: string | null; loading: boolean; onRetry: () => void; now: string | null; isToday: boolean; link: boolean;
  sleep?: DaySummary["sleepSummary"]; zone?: string; onOpen: (i: TimelineItem) => void; canOpen: (i: TimelineItem) => boolean;
}) {
  const [all, setAll] = useState(false);
  const list = items ? (all ? items : items.slice(0, DAY_FIRST)) : [];
  const row = (item: TimelineItem) => {
    // Last night's stages, as a thin bar under it.
    const stages = item.kind === "SLEEP" && sleep?.staged && sleep.stages.length > 0 && Math.abs(sleep.end - (item.end ?? item.at)) < 30 * 60_000 ? sleep.stages : null;
    const body = (
      <>
        <span className="day-time">{item.allDay ? "All day" : clockTime(item.at, zone)}</span>
        <span className="day-dot-col" aria-hidden="true"><span className={`day-dot dot-${dotOf(item.kind)}`} /></span>
        <span className="day-text">
          <span className="day-title">{item.title}</span>
          {item.detail && <span className="day-detail">{item.detail}</span>}
          {stages && <StageBar stages={stages} />}
        </span>
      </>
    );
    return (
      <li key={item.id} className="day-row">
        {canOpen(item) ? <button type="button" className="day-row-btn" onClick={() => onOpen(item)}>{body}</button> : <div className="day-row-btn">{body}</div>}
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
        {error && !items ? (
          <p className="day-empty day-failed" role="status">
            <span>Couldn't load your day.</span>
            <button type="button" className="link-btn" onClick={onRetry}><RefreshCw size={14} aria-hidden="true" /> Try again</button>
          </p>
        ) : !items ? (
          loading ? <div className="center-pad"><Spinner /></div> : null
        ) : (
          <ul className="day-rows">
            {now && (
              <li className="day-row">
                <Link to="/body" className="day-row-btn">
                  <span className="day-time is-now">Now</span>
                  <span className="day-dot-col" aria-hidden="true"><span className="day-dot dot-now" /></span>
                  <span className="day-text"><span className="day-now">{now}</span></span>
                </Link>
              </li>
            )}
            {list.map(row)}
            {items.length === 0 && !now && (
              <li className="day-empty muted">{isToday ? "Nothing yet today. What you log, and what your devices record, shows here." : "Nothing logged or recorded that day."}</li>
            )}
          </ul>
        )}
        {items && items.length > DAY_FIRST && (
          <button type="button" className="link-btn day-more" aria-expanded={all} onClick={() => setAll(!all)}>{all ? "Show less" : `Show all ${items.length}`}</button>
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
