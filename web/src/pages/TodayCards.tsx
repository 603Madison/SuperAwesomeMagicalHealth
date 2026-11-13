import { useNavigate } from "react-router-dom";
import {
  Baby, CalendarClock, CalendarHeart, Check, Droplet, FlaskConical, Gauge, Heart, HeartPulse, Inbox, ListChecks, Pill, Stethoscope, Syringe,
  ThermometerSun, Zap,
} from "lucide-react";
import { FACES } from "./Mind";
import { api, ApiError } from "../lib/api";
import { clock, hm, parseDate, shortDate, today } from "../lib/format";
import { useSession } from "../lib/session";
import { BP_TEXT, bpTone, glucoseText } from "../lib/vitals";
import type { DaySummary, Digest, LifeStageToday, Resilience, StressDay, StressState } from "../lib/types";
import { Meter } from "../components/charts";
import { Button, Card, Stat, errorToast, toast, useLoad } from "../components/ui";
import { HabitCard } from "./Habits";
import { severityWord } from "./Symptoms";
import { appointmentWhen } from "./Appointments";
import { weekText } from "./Digest";
import { VERDICT_LABEL } from "../lib/types";
import { LowSuppliesCard } from "./Supplies";
import { NeuroTodayCard } from "./Neuro";
import { TransitionTodayCard } from "./Transition";

/** The day's parts from the features that are on: doses, mood, cycle, bladder, vitals, habits… */
export function ModuleCards({ day, date, on, readOnly, onChanged, onMood, onSymptom }: {
  day: DaySummary; date: string; on: string[]; readOnly: boolean; onChanged: () => void; onMood: (n: number) => void; onSymptom: () => void;
}) {
  const navigate = useNavigate();
  // Only the features that are on (or shared): the server leaves the rest out too.
  const m = day.modules;
  const has = (id: string) => on.includes(id);
  const mind = has("MIND") ? m.mind : undefined;
  const cycle = has("CYCLE") ? m.cycle : undefined;
  const continence = has("CONTINENCE") ? m.continence : undefined;
  const meds = has("MEDICATIONS") ? m.meds : undefined;
  const vitals = has("VITALS") ? m.vitals : undefined;
  const habits = has("HABITS") ? m.habits : undefined;
  const symptoms = has("SYMPTOMS") ? m.symptoms : undefined;
  const hormones = has("HORMONES") ? m.hormones : undefined;
  const lifeStage = has("CYCLE") ? m.lifeStage : undefined;
  const appointments = has("APPOINTMENTS") ? m.appointments : undefined;
  const glucoseUnit = useSession((s) => s.profile?.vitalsTargets?.glucoseUnit) ?? vitals?.targets.glucoseUnit ?? "MMOL_L";
  const showMind = on.includes("MIND");
  const take = async (medicationId: string, scheduledFor: number, site?: string) => {
    try { await api.logDose({ medicationId, at: Date.now(), status: "TAKEN", scheduledFor, site }); toast("Taken"); onChanged(); } catch (e) { errorToast(e); }
  };
  const lifeStageShown = lifeStage && lifeStage.stage !== "CYCLING" && lifeStage.stage !== "TRYING";
  const lowSupplies = (has("CONTINENCE") || has("CYCLE")) ? m.lowSupplies ?? [] : [];
  const neuro = has("NEURO") ? m.neuro : undefined;
  const transition = has("TRANSITION") ? m.transition : undefined;
  const anything = showMind || cycle || continence || meds || vitals || habits?.length || symptoms || hormones || lifeStageShown || appointments?.length
    || lowSupplies.length || neuro || transition;
  if (!anything) return null;
  return (
    <div className="grid grid-2" style={{ marginBottom: 16 }}>
      {meds && meds.due.length > 0 && (
        <Card title="Medications" icon={<Pill size={18} />} action={<Button variant="ghost" onClick={() => navigate("/meds")}>All</Button>}>
          <ul className="rows">
            {meds.due.map((d) => (
              <li key={`${d.medicationId}-${d.scheduledFor}`} className="row">
                <div className={`row-icon ${d.log?.status === "TAKEN" ? "is-done" : ""}`}>{d.log?.status === "TAKEN" ? <Check size={18} /> : <Pill size={18} />}</div>
                <div className="row-main">
                  <div className="row-title">{d.name}{d.dose ? ` · ${d.dose}` : ""}</div>
                  <div className="row-sub">{clock(d.scheduledFor)}{d.log ? ` · ${d.log.status === "TAKEN" ? "taken" : "skipped"}` : d.nextSite ? ` · ${d.nextSite} next` : ""}</div>
                </div>
                {!d.log && date === today() && !readOnly && <Button onClick={() => take(d.medicationId, d.scheduledFor, d.nextSite)}><Check size={16} /> Take</Button>}
              </li>
            ))}
          </ul>
          {meds.adherence7d != null && <p className="muted small">{meds.adherence7d}% of doses taken this week</p>}
        </Card>
      )}
      {habits && habits.length > 0 && (
        <Card title="Habits" icon={<ListChecks size={18} />} action={<Button variant="ghost" onClick={() => navigate("/habits")}>All</Button>}>
          <ul className="rows">{habits.map((s) => <HabitCard key={s.habit.id} compact status={s} date={date} readOnly={readOnly} onChanged={onChanged} />)}</ul>
        </Card>
      )}
      {showMind && (
        <Card title="Mood" icon={<Heart size={18} />} action={<Button variant="ghost" onClick={() => navigate("/mind")}>Mind</Button>}>
          {mind?.mood != null ? <div className="big-number">{FACES[Math.round(mind.mood) - 1]}<small>{mind.mood.toFixed(1)} of 5 · {mind.entries} {mind.entries === 1 ? "entry" : "entries"}</small></div> : <p className="muted">{readOnly ? "No mood logged." : "How are you feeling?"}</p>}
          {!readOnly && <div className="scale" role="group" aria-label="Log your mood">{FACES.map((f, i) => <button key={i} type="button" aria-label={`Mood ${i + 1} of 5`} onClick={() => onMood(i + 1)}>{f}</button>)}</div>}
        </Card>
      )}
      {vitals && (vitals.bloodPressure || vitals.glucose) && (
        <Card title="Blood pressure & glucose" icon={<HeartPulse size={18} />} onClick={() => navigate("/vitals")}>
          <div className="stats">
            {vitals.bloodPressure && <Stat label="Blood pressure" value={`${vitals.bloodPressure.systolic}/${vitals.bloodPressure.diastolic}`} unit="mmHg" sub={vitals.category ? <span className={`tone-chip tone-${bpTone(vitals.category)}`}>{BP_TEXT[vitals.category]}</span> : clock(vitals.bloodPressure.at)} />}
            {vitals.glucose && <Stat label="Glucose" value={glucoseText(vitals.glucose.mmolL, glucoseUnit).split(" ")[0]} unit={glucoseText(vitals.glucose.mmolL, glucoseUnit).split(" ")[1]} sub={`at ${clock(vitals.glucose.at)}`} />}
            {vitals.timeInRange != null && <Stat label="In range today" value={vitals.timeInRange} unit="%" />}
          </div>
        </Card>
      )}
      {symptoms && (symptoms.entries.length > 0 || symptoms.migraines.length > 0 || on.includes("SYMPTOMS")) && (
        <Card title="Symptoms" icon={<Stethoscope size={18} />} action={!readOnly && <Button variant="ghost" onClick={onSymptom}>Log</Button>}>
          {symptoms.entries.length === 0 && symptoms.migraines.length === 0 ? <p className="muted">Nothing logged today{readOnly ? "." : ". Hope it stays that way."}</p> : (
            <ul className="rows">
              {symptoms.migraines.map((m) => (
                <li key={m.id ?? m.start} className="row">
                  <div className="row-icon severity-severe"><Zap size={18} /></div>
                  <div className="row-main"><div className="row-title">Migraine{m.end ? "" : " · still going"}</div><div className="row-sub">since {clock(m.start)}{m.end ? ` · ${hm((m.end - m.start) / 60_000)}` : ""}</div></div>
                </li>
              ))}
              {symptoms.entries.map((e) => (
                <li key={e.id ?? e.at} className="row">
                  <div className={`row-icon severity-${severityWord(e.severity).toLowerCase()}`}>{e.severity}</div>
                  <div className="row-main"><div className="row-title">{e.symptom}</div><div className="row-sub">{clock(e.at)} · {severityWord(e.severity).toLowerCase()}</div></div>
                </li>
              ))}
            </ul>
          )}
        </Card>
      )}
      {cycle && !lifeStageShown && (
        <Card title="Cycle" icon={<CalendarHeart size={18} />} onClick={() => navigate("/cycle")}>
          <div className="stats">
            <Stat label="Cycle day" value={cycle.cycleDay ?? "–"} sub={cycle.phase === "UNKNOWN" ? "Log a period to start" : cycle.phase === "FERTILE" ? "Fertile window (estimate)" : cycle.phase[0] + cycle.phase.slice(1).toLowerCase()} />
            <Stat label="Next period" value={cycle.daysUntilPeriod == null ? "–" : cycle.daysUntilPeriod === 0 ? "Today" : cycle.daysUntilPeriod > 0 ? `${cycle.daysUntilPeriod} days` : `${-cycle.daysUntilPeriod} days late`} />
          </div>
          {cycle.logged && <p className="muted small">Logged: {[cycle.logged.flow?.toLowerCase(), ...cycle.logged.symptoms].filter(Boolean).join(", ")}</p>}
        </Card>
      )}
      {lifeStage && lifeStageShown && <LifeStageCard stage={lifeStage} onOpen={() => navigate("/cycle")} />}
      {hormones && (hormones.medications > 0 || hormones.nextLabsDue || hormones.outOfRange.length > 0) && (
        <Card title="Hormone therapy" icon={<Syringe size={18} />} onClick={() => navigate("/hormones")}>
          <div className="stats">
            {hormones.nextLabsDue && <Stat label="Labs due" value={labsDueText(hormones.nextLabsDue)} sub={shortDate(hormones.nextLabsDue)} icon={<FlaskConical size={14} />} />}
            <Stat label="Medications" value={hormones.medications} />
          </div>
          {hormones.outOfRange.length > 0 && <p className="small">Outside your target: {hormones.outOfRange.join(", ")}. Worth mentioning at your next appointment.</p>}
        </Card>
      )}
      {appointments && appointments.length > 0 && (
        <Card title="Coming up" icon={<CalendarClock size={18} />} onClick={() => navigate("/appointments")}>
          <ul className="rows">
            {appointments.slice(0, 3).map((a) => (
              <li key={a.id ?? a.at} className="row">
                <div className="row-icon"><CalendarClock size={18} /></div>
                <div className="row-main"><div className="row-title">{a.title}{a.with ? ` · ${a.with}` : ""}</div><div className="row-sub">{appointmentWhen(a.at)}{a.questions.length ? ` · ${a.questions.length} ${a.questions.length === 1 ? "question" : "questions"}` : ""}</div></div>
              </li>
            ))}
          </ul>
        </Card>
      )}
      {continence && (
        <Card title="Bladder & bowel" icon={<Droplet size={18} />} onClick={() => navigate("/bladder")}>
          <div className="stats">
            <Stat label="Trips" value={continence.voids} sub={continence.nightVoids ? `${continence.nightVoids} at night` : undefined} />
            <Stat label="Leaks" value={continence.leaks} />
            <Stat label="Pelvic floor" value={continence.pelvicFloor} sub="sessions" />
            {(continence.products ?? 0) > 0 && <Stat label="Products" value={continence.products} sub={continence.lossG != null ? `about ${Math.round(continence.lossG)} g lost` : "changed"} />}
            {(continence.bowelLeaks ?? 0) > 0 && <Stat label="Bowel accidents" value={continence.bowelLeaks} />}
            {(continence.catheters ?? 0) > 0 && <Stat label="Catheter" value={continence.catheters} sub={continence.catheterMl != null ? `${continence.catheterMl} ml` : undefined} />}
          </div>
          {date === today() && <ContinenceVerdict />}
        </Card>
      )}
      {lowSupplies.length > 0 && <LowSuppliesCard items={lowSupplies} />}
      {neuro && <NeuroTodayCard data={neuro} />}
      {transition && <TransitionTodayCard {...transition} />}
    </div>
  );
}

/** This week against the weeks before, in a line: getting better, about the same, or worse. */
function ContinenceVerdict() {
  const { data } = useLoad(() => api.continenceProgress(8).catch(() => null), []);
  if (!data || data.verdict === "NOT_ENOUGH") return null;
  return <p className={`verdict-line verdict-${data.verdict.toLowerCase()}`}><strong>{VERDICT_LABEL[data.verdict]}</strong> · {data.headline}</p>;
}

function labsDueText(date: string): string {
  const days = Math.round((parseDate(date).getTime() - parseDate(today()).getTime()) / 86_400_000);
  return days <= 0 ? "Now" : days < 14 ? `${days} days` : `${Math.round(days / 7)} weeks`;
}

function LifeStageCard({ stage, onOpen }: { stage: LifeStageToday; onOpen: () => void }) {
  const p = stage.pregnancy;
  const Icon = stage.stage === "PREGNANT" || stage.stage === "POSTPARTUM" ? Baby : ThermometerSun;
  return (
    <Card title={stage.stage === "PREGNANT" ? "Pregnancy" : stage.stage === "POSTPARTUM" ? "After the birth" : stage.stage === "PERIMENOPAUSE" ? "Perimenopause" : "After menopause"} icon={<Icon size={18} />} onClick={onOpen}>
      <p className="life-headline">{stage.headline}</p>
      <div className="stats">
        {p && <Stat label="Week" value={p.week} sub={`day ${p.day} · trimester ${p.trimester}`} />}
        {p && <Stat label="Due" value={p.daysToGo > 0 ? `${p.daysToGo} days` : "Any day"} sub={shortDate(p.dueDate)} />}
        {stage.stage === "POSTPARTUM" && <Stat label="Feeds today" value={stage.feedsToday} sub={stage.lastFeed ? `last at ${clock(stage.lastFeed.start)}` : undefined} />}
        {(stage.stage === "PERIMENOPAUSE" || stage.stage === "MENOPAUSE") && <Stat label="Hot flashes today" value={stage.hotFlashesToday} />}
      </div>
    </Card>
  );
}

const STATE_COLOR: Record<StressState, string> = {
  STRESSED: "var(--c3)",
  ENGAGED: "color-mix(in srgb, var(--c3) 40%, var(--surface-3))",
  RELAXED: "color-mix(in srgb, var(--c2) 40%, var(--surface-3))",
  RESTORED: "var(--c2)",
};
const STATE_TEXT: Record<StressState, string> = { STRESSED: "Stressed", ENGAGED: "Engaged", RELAXED: "Relaxed", RESTORED: "Restored" };

/** The day while awake: stressed and restored minutes, the day as a strip, and resilience. */
export function StressCard({ stress, resilience, className = "" }: { stress?: StressDay; resilience?: Resilience; className?: string }) {
  if (!stress && !resilience) return null;
  const spans = stress?.spans ?? [];
  const from = spans.length ? Math.min(...spans.map((s) => s.start)) : 0;
  const to = spans.length ? Math.max(...spans.map((s) => s.end)) : 0;
  return (
    <Card title="Stress and recovery" icon={<Gauge size={18} />} className={className} action={stress && <span className={`tone-chip tone-${stress.summary === "Stressful" ? "warn" : stress.summary === "Restored" ? "good" : "info"}`}>{stress.summary}</span>}>
      {stress && (
        <>
          <div className="stats">
            <Stat label="Stressed" value={hm(stress.stressedMinutes)} />
            <Stat label="Restored" value={hm(stress.restoredMinutes)} sub="calm, recovering time" />
            <Stat label="Relaxed" value={hm(stress.relaxedMinutes)} />
          </div>
          {spans.length > 0 && to > from && (
            <div>
              <div className="stress-strip" role="img" aria-label={`From ${clock(from)} to ${clock(to)}: ${stress.stressedMinutes} minutes stressed, ${stress.restoredMinutes} restored`}>
                {spans.map((s, i) => <span key={i} title={`${STATE_TEXT[s.state]} ${clock(s.start)}–${clock(s.end)}`} style={{ left: `${((s.start - from) / (to - from)) * 100}%`, width: `${((s.end - s.start) / (to - from)) * 100}%`, background: STATE_COLOR[s.state] }} />)}
              </div>
              <div className="stress-axis muted small"><span>{clock(from)}</span><span>{clock(to)}</span></div>
              <ul className="legend">
                {(Object.keys(STATE_TEXT) as StressState[]).map((s) => <li key={s}><span className="legend-key" style={{ background: STATE_COLOR[s] }} />{STATE_TEXT[s]}</li>)}
              </ul>
            </div>
          )}
        </>
      )}
      {resilience && (
        <div className="resilience">
          <div className="resilience-head">
            <span className="muted small">Resilience, over two weeks</span>
            <strong>{resilience.level[0] + resilience.level.slice(1).toLowerCase()}</strong>
            <span className="muted small">{resilience.trend === "up" ? "↑ better than before" : resilience.trend === "down" ? "↓ lower than before" : "steady"}</span>
          </div>
          <ul className="contributors">
            {resilience.contributors.map((c) => (
              <li key={c.key} className="contributor">
                <span className="contributor-label">{c.label}</span>
                <span className="contributor-value">{c.value}</span>
                <Meter value={c.score} max={100} color="var(--c2)" label={`${c.label}: ${c.score} of 100`} />
              </li>
            ))}
          </ul>
        </div>
      )}
    </Card>
  );
}

/** On the digest's day and the day after: the week's headline, a tap from the whole thing. */
export function DigestCard() {
  const prefs = useSession((s) => s.profile?.digest);
  const navigate = useNavigate();
  const now = new Date();
  const iso = ((now.getDay() + 6) % 7) + 1; // 1 Monday … 7 Sunday
  const day = prefs?.day ?? 7;
  const show = !!prefs?.enabled && (iso === day || iso === (day % 7) + 1);
  const { data } = useLoad<Digest | null>(() => (show ? api.latestDigest().catch((e) => (e instanceof ApiError ? null : Promise.reject(e))) : Promise.resolve(null)), [show]);
  if (!show || !data) return null;
  // Only a digest made in the last two days counts as new.
  if (Date.now() - data.generatedAt > 2 * 86_400_000) return null;
  return (
    <Card className="digest-card" onClick={() => navigate("/digest")}>
      <div className="digest-card-body">
        <div className="insight-icon"><Inbox size={20} /></div>
        <div>
          <h4>Your week, {weekText(data)}</h4>
          <p>{data.headline}</p>
        </div>
      </div>
    </Card>
  );
}
