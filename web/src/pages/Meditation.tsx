import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Bell, Cloud, Feather, Heart, Lock, MoonStar, Play, Trash2, Wind, type LucideIcon } from "lucide-react";
import { api } from "../lib/api";
import { F, setField, useFields } from "../lib/fields";
import { clock, dayLabel, today, useToday } from "../lib/format";
import { daysThisWeek, defaultLength, feltWord, meditationGroups, minutesText, type MeditationGroup } from "../lib/meditation";
import { NAMES } from "../lib/nav";
import { enablePush, pushEnabledHere, pushPermission, pushSupported } from "../lib/push";
import { useSession } from "../lib/session";
import { layoutKey, type Insight, type Meditation, type MeditationKind, type MeditationSession, type MeditationSettings } from "../lib/types";
import { useReadOnly, useViewing } from "../lib/viewing";
import { useArrange, type Section } from "../components/Arrange";
import { INSIGHT_ICONS } from "../components/LogDialogs";
import { loggedSomething, useLogged } from "../components/LogSheet";
import { Button, Card, ErrorNotice, Field, PageHeader, Segmented, Spinner, Stat, Toggle, errorToast, toast, useLoad } from "../components/ui";

/*
 * Meditation: guided meditations for winding down at night, read aloud by this device's own voice
 * (pages/MeditationPlayer.tsx plays them). Four sections, arranged under "web.meditation": this
 * week, the meditations (the one used last first, each at 5, 10 or 15 minutes), the recent
 * sessions, and the nightly reminder. Everyone's, whatever's on; a share sees it with Mind, to
 * look at (no meditations to start, and never the reminder).
 */

/** Each kind's icon. */
export const KIND_ICONS: Record<MeditationKind, LucideIcon> = {
  WIND_DOWN: MoonStar, BODY_SCAN: Feather, BREATHING: Wind, DRIFTING_OFF: Cloud, GRATITUDE: Heart,
};

let cached: Promise<Meditation[]> | null = null;

/**
 * The meditations (the same for everyone), asked for once and kept: a meditation that's begun
 * never needs the network again. The service worker keeps a copy too, for opening one offline.
 */
export function loadMeditations(): Promise<Meditation[]> {
  if (!cached) cached = api.meditations().catch((e: unknown) => { cached = null; throw e; });
  return cached;
}

/** Where a meditation plays. */
export const playPath = (id: string) => `/meditation/play/${encodeURIComponent(id)}`;

/** The service worker (public/sw.js) keeps the meditations for offline; it's the same one reminders use. */
function keepForOffline() {
  try {
    if (!("serviceWorker" in navigator)) return;
    void navigator.serviceWorker.getRegistration("/")
      .then((r) => r ?? navigator.serviceWorker.register("/sw.js", { scope: "/" }))
      .catch(() => undefined);
  } catch {
    // Not here (a private window, say): the page still works online.
  }
}

export function MeditationPage() {
  const readOnly = useReadOnly();
  const viewing = useViewing((s) => s.share);
  const navigate = useNavigate();
  const logged = useLogged();
  const date = useToday();
  const list = useLoad(() => loadMeditations(), []);
  const summary = useLoad(() => api.meditationSummary(30), [logged]);
  const sessions = useLoad(() => api.meditationSessions(30), [logged]);
  const settings = useLoad(() => (viewing ? Promise.resolve(null) : api.meditationSettings()), [viewing?.ownerId]);
  useEffect(() => { if (!viewing) keepForOffline(); }, [viewing]);
  const changed = () => { summary.reload(); sessions.reload(); loggedSomething(); };

  const s = summary.data;
  const last = s?.lastMeditationId;
  const groups = list.data ? meditationGroups(list.data, last) : [];
  const days = sessions.data ? daysThisWeek(sessions.data, date) : null;

  const remove = async (x: MeditationSession) => {
    try {
      await api.deleteMeditationSession(x.id!);
      changed();
      toast("Session deleted", "info", {
        label: "Undo",
        run: async () => {
          try {
            await api.saveMeditationSession({ startedAt: x.startedAt, minutes: x.minutes, finished: x.finished, meditationId: x.meditationId, feltAfter: x.feltAfter });
            changed();
          } catch (e) { errorToast(e); }
        },
      });
    } catch (e) { errorToast(e); }
  };

  const sections: Section[] = [
    { id: "summary", label: "This week", node: s && (
      <Card title="This week" icon={<MoonStar size={18} />} className="span-2 med-summary">
        {s.sessions === 0 && s.weekMinutes === 0 ? (
          <p className="muted">Nothing yet. A few quiet minutes before bed is a good place to start.</p>
        ) : (
          <div className="med-stats">
            <Stat label="Minutes" value={Math.round(s.weekMinutes)} unit="min" />
            {days != null && <Stat label="Days" value={days} unit="of 7" />}
            <Stat label="Last 30 days" value={s.sessions} unit={s.sessions === 1 ? "session" : "sessions"} />
          </div>
        )}
        {s.insights.length > 0 && <ul className="insight-rows med-insights">{s.insights.map((i) => <QuietInsight key={i.title} insight={i} />)}</ul>}
      </Card>
    ) },
    { id: "meditations", label: "Meditations", when: !readOnly, node: list.data && (
      <Card title="Meditations" className="span-2">
        <div className="med-choices">
          {groups.map((g, i) => (
            <Choice key={`${g.kind}-${last ?? ""}`} group={g} lastId={last} last={i === 0 && list.data!.some((m) => m.id === last)} onStart={(id) => navigate(playPath(id))} />
          ))}
        </div>
      </Card>
    ) },
    { id: "recent", label: "Recent", node: sessions.data && sessions.data.length > 0 && (
      <Card title="Recent">
        <ul className="rows">
          {sessions.data.slice(0, 10).map((x) => {
            const Icon = (x.kind && KIND_ICONS[x.kind]) || MoonStar;
            const felt = feltWord(x.feltAfter);
            return (
              <li key={x.id} className="row">
                <div className="row-icon"><Icon size={18} /></div>
                <div className="row-main">
                  <div className="row-title">{x.title ?? NAMES.meditation}</div>
                  <div className="row-sub">{[minutesText(x.minutes), `${x.date ? dayLabel(x.date) : ""} ${clock(x.startedAt)}`.trim(), !x.finished && "ended early", felt].filter(Boolean).join(" · ")}</div>
                </div>
                {!readOnly && x.id && (
                  <div className="row-actions">
                    <Button variant="ghost" aria-label={`Delete ${x.title ?? "this session"}`} onClick={() => void remove(x)}><Trash2 size={16} /></Button>
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      </Card>
    ) },
    { id: "reminder", label: "Nightly reminder", when: !viewing, node: settings.data && <ReminderCard initial={settings.data} /> },
  ];
  const arranged = useArrange(layoutKey("meditation"), NAMES.meditation, sections);
  const error = list.error ?? summary.error;

  return (
    <div className="page meditation-page">
      <PageHeader title={NAMES.meditation} subtitle={viewing ? `${viewing.ownerName}'s quiet time` : "Guided, unhurried, for winding down at night"} actions={arranged.button} />
      {error && <ErrorNotice message={error} onRetry={() => { list.reload(); summary.reload(); }} />}
      {!s && summary.loading && <div className="center-pad"><Spinner size={28} /></div>}
      {arranged.render()}
      {!viewing && (
        <p className="menu-hint med-privacy"><Lock size={14} aria-hidden="true" /> Read aloud by a voice on this device, never one online. What you do here stays on your server.</p>
      )}
    </div>
  );
}

/** One kind of meditation: what it's like, how long, and Start. */
function Choice({ group, lastId, last, onStart }: { group: MeditationGroup; lastId?: string; last: boolean; onStart: (id: string) => void }) {
  const [id, setId] = useState(() => defaultLength(group, lastId));
  const Icon = KIND_ICONS[group.kind] ?? MoonStar;
  const minutes = group.lengths.find((l) => l.id === id)?.minutes;
  return (
    <article className={`med-choice ${last ? "is-last" : ""}`} aria-label={group.title}>
      <div className="med-choice-head">
        <span className="med-choice-icon" aria-hidden="true"><Icon size={20} /></span>
        <div className="med-choice-text">
          <h3>{group.title}{last && <span className="badge">Last time</span>}</h3>
          <p>{group.about}</p>
        </div>
      </div>
      <div className="med-choice-actions">
        <Segmented label={`${group.title}: how long`} value={id} onChange={setId} options={group.lengths.map((l) => ({ value: l.id, label: `${l.minutes} min` }))} />
        <Button variant={last ? "primary" : "tonal"} onClick={() => onStart(id)} aria-label={`Start ${group.title}, ${minutes} minutes`}><Play size={16} aria-hidden="true" /> Start</Button>
      </div>
    </article>
  );
}

/** An insight about the page itself ("Sleep after meditating"): no link, as it's already here. */
function QuietInsight({ insight }: { insight: Insight }) {
  const Icon = INSIGHT_ICONS[insight.icon] ?? MoonStar;
  return (
    <li className={`insight-row tone-${insight.tone}`}>
      <div className="insight-main">
        <span className="insight-icon" aria-hidden="true"><Icon size={18} /></span>
        <span className="insight-text">
          <span className="insight-title">{insight.title}</span>
          <span className="insight-body">{insight.body}</span>
        </span>
      </div>
    </li>
  );
}

/** The nightly reminder: on or off, and when; it comes by this browser's notifications, where they're allowed. */
function ReminderCard({ initial }: { initial: MeditationSettings }) {
  const [s, setS] = useState(initial);
  const [time, setTime] = useState(initial.time);
  const [push, setPush] = useState<boolean | null>(null);
  const [busy, setBusy] = useState(false);
  const userId = useSession((x) => x.user?.id) ?? "";
  const { has } = useFields();
  useEffect(() => { void pushEnabledHere(userId).then(setPush).catch(() => setPush(false)); }, [userId]);

  const save = async (next: MeditationSettings) => {
    const before = s;
    setS(next);
    try {
      const saved = await api.saveMeditationSettings(next);
      setS(saved);
      setTime(saved.time);
      toast(saved.reminder ? `You'll be reminded at ${timeText(saved.time)}` : "Reminder off");
    } catch (e) {
      setS(before);
      setTime(before.time);
      errorToast(e);
    }
  };
  const allow = async () => {
    setBusy(true);
    try { await enablePush(userId); setPush(true); toast("Notifications on in this browser"); } catch (e) { errorToast(e); }
    setBusy(false);
  };

  const supported = pushSupported();
  const blocked = pushPermission() === "denied";
  const note = !supported ? "This browser can't show notifications. The phone app can remind you instead."
    : blocked ? "Notifications are blocked for this site. Allow them in the browser's settings to get it here."
    : push ? "It comes as a notification in this browser."
    : "It comes as a notification where this browser allows.";
  return (
    <Card title="Nightly reminder" icon={<Bell size={18} />}>
      <div className="form-stack med-reminder">
        <Toggle label="Remind me to wind down" checked={s.reminder} onChange={(v) => void save({ ...s, reminder: v })} />
        {s.reminder && (
          <>
            <Field label="At">
              <input type="time" value={time} onChange={(e) => setTime(e.target.value)}
                onBlur={() => { if (/^\d{2}:\d{2}$/.test(time) && time !== s.time) void save({ ...s, time }); else setTime(s.time); }} />
            </Field>
            <p className="muted small med-note">{note}</p>
            {supported && !blocked && push === false && (
              <div><Button variant="tonal" busy={busy} onClick={() => void allow()}><Bell size={16} aria-hidden="true" /> Allow in this browser</Button></div>
            )}
          </>
        )}
        <Toggle label="Ask how I feel afterwards" help="One tap at the end, or skip it." checked={has(F.MEDITATION_FELT_AFTER)}
          onChange={(v) => void setField(F.MEDITATION_FELT_AFTER, v).catch(errorToast)} />
      </div>
    </Card>
  );
}

/** "21:30" as this browser says times: "9:30 PM". */
function timeText(hhmm: string): string {
  const [h, m] = hhmm.split(":").map(Number);
  const d = new Date(`${today()}T12:00`);
  d.setHours(h, m, 0, 0);
  return clock(d.getTime());
}
