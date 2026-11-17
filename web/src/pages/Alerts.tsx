import { useEffect, useId, useRef, useState } from "react";
import { HeartPulse, Info, Minus, Phone, Plus, Radar, SlidersHorizontal } from "lucide-react";
import { Link } from "react-router-dom";
import { api } from "../lib/api";
import { clearAlertBadge } from "../lib/alertBadge";
import {
  ALERT_DAYS, ALERT_LEVEL_TEXT, ALERT_RANGES, alertWhen, awayText, clampAlertNumber, clampAlertSettings, devicesText, emptyText, isAway, newestFirst,
  replaceAlert, settingsChanged, stepAlertNumber, unseenIds, watchState, WATCH_STATE_TEXT, type AlertClock, type AlertNumber,
} from "../lib/alerts";
import { dayLabel } from "../lib/format";
import { useSession } from "../lib/session";
import { clockTime, dateIn } from "../lib/today";
import { ALERT_FEEDBACK_LABELS, layoutKey, type AlertFeedback, type AlertSettings, type AlertWatch, type HealthAlert } from "../lib/types";
import { useReadOnly } from "../lib/viewing";
import { Button, Card, Empty, ErrorNotice, Menu, PageHeader, Spinner, Toggle, errorToast, toast, useLoad } from "../components/ui";
import { useArrange, type Section } from "../components/Arrange";

/*
 * Health alerts (GET /api/alerts): what the server noticed in the person's readings, newest first,
 * each saying how soon to act. One that could need care now leads with what to do, in the theme's
 * strongest colour. Opening the page marks them seen; putting one away keeps it in the list,
 * quieter, with why. Then what it's watching, and the settings. Only ever the person's own: never
 * while viewing a share.
 */

export function Alerts() {
  const readOnly = useReadOnly();
  const zone = useSession((s) => s.profile?.timezone) || undefined;
  const load = useLoad(() => (readOnly ? Promise.resolve(null) : api.alerts(ALERT_DAYS)), [readOnly]);
  const data = load.data ?? null;
  const [alerts, setAlerts] = useState<HealthAlert[] | null>(null);
  // What was new when the page opened: still "New" on this visit, though it's marked seen at once.
  const [fresh, setFresh] = useState<Set<string>>(new Set());
  const marked = useRef(false);

  useEffect(() => {
    if (!data) return;
    setAlerts(newestFirst(data.alerts));
    if (marked.current) return;
    marked.current = true;
    const unseen = unseenIds(data.alerts);
    setFresh(new Set(unseen));
    // Seen now: the badges (Today's bell, More, the sidebar) go too.
    clearAlertBadge();
    if (unseen.length > 0) void api.alertsSeen().catch(() => undefined);
  }, [data]);

  const clock: AlertClock = { time: (ms) => clockTime(ms, zone), day: (ms) => dayLabel(dateIn(ms, zone)) };
  const putAway = async (alert: HealthAlert, feedback?: AlertFeedback) => {
    try {
      const changed = await api.dismissAlert(alert.id, feedback);
      setAlerts((list) => (list ? replaceAlert(list, changed) : list));
      toast("Put away. It stays in the list, in case you want it later.");
    } catch (e) { errorToast(e); }
  };

  const sections: Section[] = [
    { id: "alerts", label: "Alerts", node: data && alerts && (
      alerts.length === 0 ? (
        <Card className="span-2">
          <Empty icon={<HeartPulse />} title="Nothing out of the ordinary">
            <p>{emptyText(data.settings, data.watching)}</p>
            {data.settings.enabled && data.watching.some((w) => w.watching) && (
              <ul className="tag-list alerts-watched" aria-label="Watching">
                {data.watching.filter((w) => w.watching).map((w) => <li key={w.what} className="chip is-static">{w.what}</li>)}
              </ul>
            )}
          </Empty>
        </Card>
      ) : (
        <section className="alert-list span-2" aria-label="Alerts">
          {alerts.map((a) => <AlertCard key={a.id} alert={a} isNew={fresh.has(a.id)} clock={clock} onPutAway={(f) => putAway(a, f)} />)}
        </section>
      )
    ) },
    { id: "watching", label: "What it's watching", node: data && <Watching watching={data.watching} /> },
    { id: "settings", label: "Settings", node: data && <SettingsLink /> },
  ];
  const arranged = useArrange(layoutKey("alerts"), "Health alerts", sections);

  return (
    <div className="page alerts-page">
      <PageHeader title="Health alerts" subtitle="A heads-up when your readings look out of the ordinary for you" actions={data ? arranged.button : null} />
      {load.error && <ErrorNotice message={load.error} onRetry={load.reload} />}
      {!data && load.loading && <div className="center-pad"><Spinner size={28} /></div>}
      {data?.limits && <Limits text={data.limits} />}
      {data && arranged.render()}
    </div>
  );
}

/** What alerts can and can't do, in plain words and plain view. */
function Limits({ text }: { text: string }) {
  return (
    <p className="alert-limits" role="note">
      <Info size={18} aria-hidden="true" />
      <span>{text}</span>
    </p>
  );
}

/** Each answer for putting an alert away, and putting it away without one. */
const FEEDBACK = Object.entries(ALERT_FEEDBACK_LABELS) as [AlertFeedback, string][];

/** One alert: how soon to act, what happened and when, the readings behind it, and what to do. */
function AlertCard({ alert: a, isNew, clock, onPutAway }: { alert: HealthAlert; isNew: boolean; clock: AlertClock; onPutAway: (f?: AlertFeedback) => void }) {
  const titleId = useId();
  const away = isAway(a);
  const urgent = a.level === "URGENT" && !away;
  const level = a.level.toLowerCase();
  const evidence = a.evidence ?? [];
  const from = devicesText(a.devices);
  const details = (
    <>
      <p className="alert-body">{a.body}</p>
      {evidence.length > 0 && (
        <ul className="alert-evidence" aria-label="The readings">
          {evidence.map((r) => <li key={`${r.label}-${r.value}`}><span>{r.label}</span> <strong>{r.value}</strong></li>)}
        </ul>
      )}
      {from && <p className="alert-from">{from}</p>}
      {!urgent && (
        <div className="alert-advice">
          <h4>What to do</h4>
          <p>{a.advice}</p>
        </div>
      )}
    </>
  );
  return (
    <article className={`alert-card level-${level} ${away ? "is-away" : ""}`} aria-labelledby={titleId}>
      {urgent && (
        <div className="alert-urgent">
          <Phone size={20} aria-hidden="true" />
          <div>
            <h4>What to do now</h4>
            <p>{a.advice}</p>
          </div>
        </div>
      )}
      <div className="alert-head">
        <span className={`alert-level level-${level}`}>{ALERT_LEVEL_TEXT[a.level]}</span>
        {isNew && !away && <span className="badge badge-accent">New</span>}
      </div>
      <h3 id={titleId} className="alert-title">{a.title}</h3>
      <p className="alert-when">{alertWhen(a, clock)}</p>
      {away ? (
        <>
          <p className="alert-away">{awayText(a, ALERT_FEEDBACK_LABELS)}</p>
          <details className="alert-details">
            <summary>What it said</summary>
            {details}
          </details>
        </>
      ) : (
        <>
          {details}
          <div className="alert-actions">
            <Menu label={`Put away: ${a.title}`} buttonClass="btn btn-ghost alert-put-away" trigger={<>Put away</>} items={[
              ...FEEDBACK.map(([id, label]) => ({ label, onSelect: () => onPutAway(id) })),
              { label: "Put it away without saying", onSelect: () => onPutAway() },
            ]} />
          </div>
        </>
      )}
    </article>
  );
}

/** The settings live with reminders: one way there. */
function SettingsLink() {
  return (
    <Card title="Settings" icon={<SlidersHorizontal size={18} />} link={{ to: "/settings#alerts", label: "Open the settings for health alerts" }}>
      <p className="muted">What it watches for, and how it tells you: in <Link to="/settings#alerts">Reminders, alerts and sharing</Link>.</p>
    </Card>
  );
}

/** What the alerts can watch with the person's devices, and how. */
function Watching({ watching }: { watching: AlertWatch[] }) {
  return (
    <Card title="What it's watching" icon={<Radar size={18} />}>
      {watching.length === 0 ? <p className="muted">Nothing yet: readings from a device bring it in.</p> : (
        <ul className="rows">
          {watching.map((w) => {
            const state = watchState(w);
            return (
              <li key={w.what} className="row alert-watch">
                <div className="row-main">
                  <div className="row-title">{w.what}</div>
                  <div className="row-sub wraps">{w.how}</div>
                </div>
                <span className={`tone-chip ${state === "on" ? "tone-good" : ""}`}>{WATCH_STATE_TEXT[state]}</span>
              </li>
            );
          })}
        </ul>
      )}
    </Card>
  );
}

/** A number within its range, with its name and help: − and + a step at a time, or typed. */
function Stepper({ name, label, help, value, onChange }: { name: AlertNumber; label: string; help: string; value: number; onChange: (v: number) => void }) {
  const r = ALERT_RANGES[name];
  const id = useId();
  const [text, setText] = useState<string | null>(null);
  return (
    <div className="field">
      <span className="field-label" id={`${id}-label`}>{label}</span>
      <div className="stepper">
        <button type="button" className="icon-btn" aria-label={`Lower: ${label}`} disabled={value <= r.min} onClick={() => onChange(stepAlertNumber(name, value, -1))}><Minus size={18} /></button>
        <input
          inputMode="numeric" value={text ?? String(value)} aria-labelledby={`${id}-label`} aria-describedby={`${id}-help`}
          onChange={(e) => setText(e.target.value)}
          onBlur={() => { if (text != null) onChange(clampAlertNumber(name, Number(text.replace(",", ".")))); setText(null); }}
          onKeyDown={(e) => { if (e.key === "Enter") (e.target as HTMLInputElement).blur(); }}
        />
        <span className="stepper-unit" aria-hidden="true">{r.unit}</span>
        <button type="button" className="icon-btn" aria-label={`Higher: ${label}`} disabled={value >= r.max} onClick={() => onChange(stepAlertNumber(name, value, 1))}><Plus size={18} /></button>
      </div>
      <span className="field-help" id={`${id}-help`}>{help}</span>
    </div>
  );
}

/** The person's alert settings, saved together (Settings › Reminders, alerts and sharing). */
export function AlertSettingsForm({ initial, onSaved }: { initial: AlertSettings; onSaved: () => void }) {
  const [saved, setSaved] = useState(initial);
  const [s, setS] = useState(initial);
  const [busy, setBusy] = useState(false);
  useEffect(() => { setSaved(initial); setS(initial); }, [initial]);
  const set = (change: Partial<AlertSettings>) => setS({ ...s, ...change });
  const save = async () => {
    setBusy(true);
    try {
      const next = await api.saveAlertSettings(clampAlertSettings(s));
      setSaved(next);
      setS(next);
      toast("Saved");
      onSaved();
    } catch (e) { errorToast(e); }
    setBusy(false);
  };
  const range = (name: AlertNumber) => {
    const r = ALERT_RANGES[name];
    return `${r.min}–${r.max}${r.unit === "%" ? "%" : ` ${r.unit}`}`;
  };
  return (
    <>
      <div className="form-stack alert-settings">
        <Toggle label="Health alerts" help="Watch your readings and say when something looks out of the ordinary for you." checked={s.enabled} onChange={(enabled) => set({ enabled })} />
        {s.enabled && (
          <>
            <Stepper name="highHeartRate" label="High heart rate at rest" value={s.highHeartRate} onChange={(highHeartRate) => set({ highHeartRate })}
              help={`When it stays at or above this for 10 minutes while you're resting (${range("highHeartRate")}).`} />
            <Stepper name="lowHeartRate" label="Low heart rate while awake" value={s.lowHeartRate} onChange={(lowHeartRate) => set({ lowHeartRate })}
              help={`When it stays at or below this for 10 minutes while you're awake (${range("lowHeartRate")}).`} />
            <Stepper name="lowOxygen" label="Low blood oxygen" value={s.lowOxygen} onChange={(lowOxygen) => set({ lowOxygen })}
              help={`When readings stay at or below this (${range("lowOxygen")}).`} />
            <Toggle label="Signs of getting ill" help="Resting heart rate, HRV, temperature and breathing together, against your own usual." checked={s.illness} onChange={(illness) => set({ illness })} />
            <Toggle label="Heart rhythm" help="From a chest strap's beat-to-beat timing. A ring or watch can't tell." checked={s.rhythm} onChange={(rhythm) => set({ rhythm })} />
            <Toggle label="Tell me by notification" help="On your phone, and in your browser if its notifications are on. Off, they still show here and on Today." checked={s.notify} onChange={(notify) => set({ notify })} />
          </>
        )}
        <div className="button-row">
          <Button variant="primary" busy={busy} disabled={!settingsChanged(s, saved)} onClick={save}>Save</Button>
          {settingsChanged(s, saved) && <Button variant="ghost" onClick={() => setS(saved)}>Undo changes</Button>}
        </div>
      </div>
    </>
  );
}
